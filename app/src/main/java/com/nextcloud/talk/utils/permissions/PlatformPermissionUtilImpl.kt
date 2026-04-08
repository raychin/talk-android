/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Álvaro Brey <alvaro@alvarobrey.com>
 * SPDX-FileCopyrightText: 2022 Nextcloud GmbH
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils.permissions

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.PermissionChecker
import com.nextcloud.talk.BuildConfig

class PlatformPermissionUtilImpl(private val context: Context) : PlatformPermissionUtil {
    override val privateBroadcastPermission: String =
        "${BuildConfig.APPLICATION_ID}.${BuildConfig.PERMISSION_LOCAL_BROADCAST}"

    override fun isCameraPermissionGranted(): Boolean =
        PermissionChecker.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PermissionChecker.PERMISSION_GRANTED

    @RequiresApi(Build.VERSION_CODES.S)
    override fun isBluetoothPermissionGranted(): Boolean =
        PermissionChecker.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PermissionChecker.PERMISSION_GRANTED

    override fun isMicrophonePermissionGranted(): Boolean {
        val hasPermission = PermissionChecker.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PermissionChecker.PERMISSION_GRANTED

        if (hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val canAccess = canActuallyRecordAudio()
            Log.d(TAG, "Microphone permission check: hasPermission=$hasPermission, canAccess=$canAccess")
            return canAccess
        }

        return hasPermission
    }

    private fun canActuallyRecordAudio(): Boolean {
        // return try {
        //     val bufferSize = AudioRecord.getMinBufferSize(
        //         8000,
        //         AudioFormat.CHANNEL_IN_MONO,
        //         AudioFormat.ENCODING_PCM_16BIT
        //     )
        //
        //     if (bufferSize <= 0) {
        //         Log.w(TAG, "Invalid buffer size: $bufferSize")
        //         return false
        //     }
        //
        //     val audioRecord = AudioRecord(
        //         MediaRecorder.AudioSource.MIC,
        //         8000,
        //         AudioFormat.CHANNEL_IN_MONO,
        //         AudioFormat.ENCODING_PCM_16BIT,
        //         bufferSize
        //     )
        //
        //     val state = audioRecord.state
        //     audioRecord.release()
        //
        //     state == AudioRecord.STATE_INITIALIZED
        // } catch (e: SecurityException) {
        //     Log.w(TAG, "Security exception when testing audio record", e)
        //     false
        // } catch (e: Exception) {
        //     Log.w(TAG, "Exception when testing audio record", e)
        //     false
        // }
        var retryCount = 0
        val maxRetries = 3 // 华为/荣耀设备可增加重试次数

        while (retryCount < maxRetries) {
            try {
                if (retryCount > 0) {
                    // 增加延迟，等待系统权限同步
                    Thread.sleep(200L * retryCount)
                }

                // 尝试创建 AudioRecord
                val bufferSize = AudioRecord.getMinBufferSize(
                    8000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (bufferSize <= 0) {
                    Log.w(TAG, "Invalid buffer size: $bufferSize")
                    retryCount++
                    continue
                }

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    8000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                val state = audioRecord.state
                audioRecord.release()

                if (state == AudioRecord.STATE_INITIALIZED) {
                    return true
                }

            } catch (e: SecurityException) {
                Log.w(TAG, "Security exception when testing audio record", e)
            } catch (e: Exception) {
                Log.w(TAG, "Exception when testing audio record", e)
            }

            retryCount++
        }

        return false
    }

    override fun isFilesPermissionGranted(): Boolean =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (
                    PermissionChecker.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
                    == PermissionChecker.PERMISSION_GRANTED ||
                    PermissionChecker.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
                    == PermissionChecker.PERMISSION_GRANTED ||
                    PermissionChecker.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
                    == PermissionChecker.PERMISSION_GRANTED
                ) {
                    Log.d(TAG, "Permission is granted (SDK 33 or greater)")
                    true
                } else {
                    Log.d(TAG, "Permission is revoked (SDK 33 or greater)")
                    false
                }
            }
            Build.VERSION.SDK_INT > Build.VERSION_CODES.Q -> {
                if (PermissionChecker.checkSelfPermission(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PermissionChecker.PERMISSION_GRANTED
                ) {
                    Log.d(TAG, "Permission is granted (SDK 30 or greater)")
                    true
                } else {
                    Log.d(TAG, "Permission is revoked (SDK 30 or greater)")
                    false
                }
            }
            else -> {
                if (PermissionChecker.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PermissionChecker.PERMISSION_GRANTED
                ) {
                    Log.d(TAG, "Permission is granted")
                    true
                } else {
                    Log.d(TAG, "Permission is revoked")
                    false
                }
            }
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun isPostNotificationsPermissionGranted(): Boolean =
        PermissionChecker.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PermissionChecker.PERMISSION_GRANTED

    override fun isLocationPermissionGranted(): Boolean =
        PermissionChecker.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PermissionChecker.PERMISSION_GRANTED

    companion object {
        private val TAG = PlatformPermissionUtilImpl::class.simpleName
    }
}
