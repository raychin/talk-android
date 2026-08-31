/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import cn.jpush.android.api.JPushInterface
import com.nextcloud.talk.utils.preferences.AppPreferencesImpl

/**
 * 静默保活管理器。
 *
 * 三路保活互备，无需前台通知：
 *  - JobScheduler：系统周期性调度
 *  - AlarmManager：9 分钟闹钟兜底，穿透 Doze
 *  - 系统广播：网络恢复 / 充电 / 解锁时快速恢复
 *
 * 进程存活时，任意一路触发都能恢复推送。
 * 手动杀死进程后，取决于厂商策略，无法保证。
 */
object KeepAliveManager {

    private const val TAG = "KeepAliveManager"
    private const val JOB_ID = 48003
    private const val ALARM_REQUEST_CODE = 48004
    private const val ALARM_INTERVAL_MS = 9 * 60 * 1000L
    const val ACTION_PUSH_KEEP_ALIVE = "com.nextcloud.talk.action.PUSH_KEEP_ALIVE"

    private var systemReceiverRegistered = false

    fun start(context: Context) {
        Log.d(TAG, "Starting keep-alive services")
        startJobScheduler(context)
        scheduleAlarm(context)
        registerSystemReceivers(context)
    }

    fun stop(context: Context) {
        Log.d(TAG, "Stopping keep-alive services")
        stopJobScheduler(context)
        cancelAlarm(context)
        unregisterSystemReceivers(context)
    }

    fun isEnabled(context: Context): Boolean {
        return try {
            AppPreferencesImpl(context).isKeepAliveEnabled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read keep-alive pref, defaulting to enabled", e)
            true
        }
    }

    private fun registerSystemReceivers(context: Context) {
        if (systemReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(systemEventReceiver, filter)
            systemReceiverRegistered = true
            Log.d(TAG, "System receivers registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register system receivers", e)
        }
    }

    private fun unregisterSystemReceivers(context: Context) {
        if (!systemReceiverRegistered) return
        try {
            context.unregisterReceiver(systemEventReceiver)
            systemReceiverRegistered = false
            Log.d(TAG, "System receivers unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister system receivers", e)
        }
    }

    /**
     * 系统广播接收器：进程存活时触发快速恢复。
     */
    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    val noConnectivity = intent.getBooleanExtra(
                        ConnectivityManager.EXTRA_NO_CONNECTIVITY, false
                    )
                    if (!noConnectivity) {
                        Log.d(TAG, "Connectivity restored, resuming push")
                        resumePushOnly(context)
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    Log.d(TAG, "Power connected, resuming push (MIUI low-power exit)")
                    resumePushOnly(context)
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!powerManager.isDeviceIdleMode) {
                        Log.d(TAG, "Exited Doze mode, resuming push")
                        resumePushOnly(context)
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "User present (unlock), resuming push")
                    resumePushOnly(context)
                }
            }
        }
    }

    private fun startJobScheduler(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        for (info in scheduler.allPendingJobs) {
            if (info.id == JOB_ID) {
                Log.d(TAG, "JobScheduler already scheduled")
                return
            }
        }
        val componentName = ComponentName(context, KeepAliveJobService::class.java)
        val builder = JobInfo.Builder(JOB_ID, componentName)
            .setMinimumLatency(ALARM_INTERVAL_MS)
            .setOverrideDeadline(ALARM_INTERVAL_MS + 60_000)
            .setPersisted(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setRequiresDeviceIdle(false)
        }
        val result = scheduler.schedule(builder.build())
        if (result == JobScheduler.RESULT_SUCCESS) {
            Log.d(TAG, "KeepAliveJobService scheduled")
        } else {
            Log.w(TAG, "KeepAliveJobService schedule failed")
        }
    }

    private fun stopJobScheduler(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancel(JOB_ID)
        Log.d(TAG, "KeepAliveJobService cancelled")
    }

    private fun scheduleAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_PUSH_KEEP_ALIVE)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                pendingIntent
            )
        }
        Log.d(TAG, "Alarm keep-alive scheduled, interval=${ALARM_INTERVAL_MS}ms")
    }

    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_PUSH_KEEP_ALIVE)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
        Log.d(TAG, "Alarm keep-alive cancelled")
    }
}

/**
 * JobScheduler 保活任务。系统拉起后 init + resume，确保 JPush 就绪。
 */
class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "KeepAliveJobService triggered")
        initAndResumePush(this)
        jobFinished(params, false)
        if (KeepAliveManager.isEnabled(this)) {
            KeepAliveManager.start(this)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "KeepAliveJobService stopped")
        return true
    }

    companion object {
        private const val TAG = "KeepAliveJobService"
    }
}

/**
 * AlarmManager 闹钟广播接收器。进程被杀后系统拉起时触发，强制初始化 JPush。
 */
class PushKeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != KeepAliveManager.ACTION_PUSH_KEEP_ALIVE) return
        Log.d(TAG, "PushKeepAliveReceiver triggered")
        initAndResumePush(context)
        if (KeepAliveManager.isEnabled(context)) {
            KeepAliveManager.start(context)
        }
    }

    companion object {
        private const val TAG = "PushKeepAliveReceiver"
    }
}

/**
 * 进程被杀重建场景：强制初始化 JPush 并恢复推送。
 */
private fun initAndResumePush(context: Context) {
    try {
        if (!isNetworkAvailable(context)) {
            Log.w(TAG, "initAndResumePush: no network, skip")
            return
        }
        JPushInterface.init(context)
        JPushInterface.resumePush(context)
        val rid = JPushInterface.getRegistrationID(context)
        Log.d(TAG, "initAndResumePush done, registrationId=${rid?.take(8) ?: "null"}...")
        JPushInterface.getPushStatus(context)
    } catch (e: Exception) {
        Log.e(TAG, "initAndResumePush failed", e)
    }
}

/**
 * 进程存活场景：轻量恢复推送（JPush 已初始化过）。
 */
private fun resumePushOnly(context: Context) {
    try {
        if (!isNetworkAvailable(context)) return
        JPushInterface.resumePush(context)
        Log.d(TAG, "resumePushOnly done")
    } catch (e: Exception) {
        Log.e(TAG, "resumePushOnly failed", e)
    }
}

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork
    val caps = cm.getNetworkCapabilities(network)
    return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private const val TAG = "KeepAlive"
