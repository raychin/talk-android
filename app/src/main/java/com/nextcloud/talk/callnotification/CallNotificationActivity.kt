/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2017-2018 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.callnotification

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.CallActivity
import com.nextcloud.talk.activities.CallBaseActivity
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.databinding.CallNotificationActivityBinding
import com.nextcloud.talk.extensions.loadUserAvatar
import com.nextcloud.talk.models.json.participants.Participant
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.CapabilitiesUtil.hasSpreedFeatureCapability
import com.nextcloud.talk.utils.NotificationUtils
import com.nextcloud.talk.utils.SpreedFeatures
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_CALL_VOICE_ONLY
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_ONE_TO_ONE
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import okhttp3.Cache
import java.io.IOException
import javax.inject.Inject

@SuppressLint("LongLogTag")
@AutoInjector(NextcloudTalkApplication::class)
class CallNotificationActivity : CallBaseActivity() {
    @JvmField
    @Inject
    var ncApi: NcApi? = null

    @JvmField
    @Inject
    var cache: Cache? = null

    @Inject
    lateinit var userManager: UserManager

    private var roomToken: String? = null
    private var notificationTimestamp: Int? = null
    private var displayName: String? = null
    private var callFlag: Int = 0
    private var isOneToOneCall: Boolean = true
    private var conversationName: String? = null
    private var internalUserId: Long = -1

    private var userBeingCalled: User? = null
    private var leavingScreen = false
    private var handler: Handler? = null
    private var binding: CallNotificationActivityBinding? = null
    private var isResumed = false
    private var pendingProceedToCall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeUpScreen()

        sharedApplication!!.componentApplication.inject(this)
        binding = CallNotificationActivityBinding.inflate(layoutInflater)
        setContentView(binding!!.root)
        hideNavigationIfNoPipAvailable()

        handleExtras()
        userBeingCalled = userManager.getUserWithId(internalUserId).blockingGet()

        setupCallTypeDescription()
        binding!!.conversationNameTextView.text = displayName
        setupAvatar(isOneToOneCall, conversationName)
        initClickListeners()
        setupNotificationCanceledRoutine()
    }

    private fun wakeUpScreen() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)

            // 使用 Activity 实例来 dismiss Keyguard
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    Log.d(TAG, "Keyguard dismissed successfully")
                }

                override fun onDismissError() {
                    Log.e(TAG, "Error dismissing keyguard")
                }
            })
        }
    }

    private fun handleExtras() {
        val extras = intent.extras!!
        roomToken = extras.getString(KEY_ROOM_TOKEN, "")
        notificationTimestamp = extras.getInt(BundleKeys.KEY_NOTIFICATION_TIMESTAMP)
        displayName = extras.getString(BundleKeys.KEY_CONVERSATION_DISPLAY_NAME, "")
        callFlag = extras.getInt(BundleKeys.KEY_CALL_FLAG)
        isOneToOneCall = extras.getBoolean(KEY_ROOM_ONE_TO_ONE)
        conversationName = extras.getString(BundleKeys.KEY_CONVERSATION_NAME, "")
        internalUserId = extras.getLong(BundleKeys.KEY_INTERNAL_USER_ID)
    }

    private fun setupAvatar(isOneToOneCall: Boolean, conversationName: String?) {
        if (isOneToOneCall) {
            binding!!.avatarImageView.loadUserAvatar(
                userBeingCalled!!,
                conversationName!!,
                true,
                false
            )
        } else {
            binding!!.avatarImageView.setImageResource(R.drawable.ic_circular_group)
        }
    }

    private fun setupCallTypeDescription() {
        val apiVersion = ApiUtils.getConversationApiVersion(
            userBeingCalled!!,
            intArrayOf(
                ApiUtils.API_V4,
                ApiUtils.API_V3,
                1
            )
        )

        if (apiVersion >= ApiUtils.API_V3) {
            val hasCallFlags = hasSpreedFeatureCapability(
                userBeingCalled?.capabilities?.spreedCapability!!,
                SpreedFeatures.CONVERSATION_CALL_FLAGS
            )
            if (hasCallFlags) {
                if (isInCallWithVideo(callFlag)) {
                    binding!!.incomingCallVoiceOrVideoTextView.text = String.format(
                        resources.getString(R.string.nc_call_video),
                        resources.getString(R.string.nc_app_name)
                    )
                    binding!!.callAnswerVoiceOnlyView.visibility = View.GONE
                } else {
                    binding!!.incomingCallVoiceOrVideoTextView.text = String.format(
                        resources.getString(R.string.nc_call_voice),
                        resources.getString(R.string.nc_app_name)
                    )
                    binding!!.callAnswerCameraView.visibility = View.GONE
                }
            }
        } else {
            val callDescriptionWithoutTypeInfo = String.format(
                resources.getString(R.string.nc_call_unknown),
                resources.getString(R.string.nc_app_name)
            )
            binding!!.incomingCallVoiceOrVideoTextView.text = callDescriptionWithoutTypeInfo
        }
    }

    private fun setupNotificationCanceledRoutine() {
        val notificationHandler = Handler(Looper.getMainLooper())
        notificationHandler.post(object : Runnable {
            override fun run() {
                if (NotificationUtils.isNotificationVisible(context, notificationTimestamp!!.toInt())) {
                    notificationHandler.postDelayed(this, ONE_SECOND)
                } else if (isResumed) {
                    finish()
                } else {
                    notificationHandler.post(this)
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (handler == null) {
            handler = Handler()
            try {
                cache!!.evictAll()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to evict cache")
            }
        }
    }

    private fun initClickListeners() {
        binding!!.callAnswerVoiceOnlyView.setOnClickListener {
            Log.d(TAG, "accept call (voice only)")
            intent.putExtra(KEY_CALL_VOICE_ONLY, true)
            proceedToCall()
        }
        binding!!.callAnswerCameraView.setOnClickListener {
            Log.d(TAG, "accept call (with video)")
            intent.putExtra(KEY_CALL_VOICE_ONLY, false)
            proceedToCall()
        }
        binding!!.hangupButton.setOnClickListener { hangup() }
    }

    private fun hangup() {
        leavingScreen = true

        // 挂断时也取消通知
        cancelIncomingCallNotification()

        finish()
    }

    private fun proceedToCall() {
        if (isResumed) {
            doProceedToCall()
        } else {
            pendingProceedToCall = true
        }
    }

    private fun doProceedToCall() {
        // 修复锁屏、其他应用及app内部接听崩溃问题
        val callIntent = Intent(sharedApplication, CallActivity::class.java)
        intent.putExtra(KEY_ROOM_ONE_TO_ONE, isOneToOneCall)
        callIntent.putExtras(intent.extras!!)
        startActivity(callIntent)

        // 立即取消通知，不要等到 onStop
        cancelIncomingCallNotification()

        // 完成当前 Activity
        finish()
    }

    /**
     * 取消来电通知
     */
    private fun cancelIncomingCallNotification() {
        if (notificationTimestamp != null) {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.cancel(notificationTimestamp!!)
            Log.d(TAG, "Cancelled incoming call notification with ID: $notificationTimestamp")

            // 同时取消可能存在的其他相关通知
            cancelExistingNotificationsForRoom()
        } else {
            Log.w(TAG, "notificationTimestamp is null, cannot cancel notification")
        }
    }

    /**
     * 取消与当前房间相关的所有通知
     */
    private fun cancelExistingNotificationsForRoom() {
        if (!roomToken.isNullOrEmpty() && userBeingCalled != null) {
            try {
                NotificationUtils.cancelExistingNotificationsForRoom(
                    applicationContext,
                    userBeingCalled!!,
                    roomToken!!
                )
                Log.d(TAG, "Cancelled all notifications for room: $roomToken")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel notifications for room", e)
            }
        }
    }

    private fun isInCallWithVideo(callFlag: Int): Boolean = (callFlag and Participant.InCallFlags.WITH_VIDEO) > 0

    override fun onStop() {
        // val notificationManager = NotificationManagerCompat.from(context)
        // notificationManager.cancel(notificationTimestamp!!)
        // 确保在 onStop 时也取消通知（作为备用）
        cancelIncomingCallNotification()

        super.onStop()
    }

    public override fun onDestroy() {
        leavingScreen = true
        if (handler != null) {
            handler!!.removeCallbacksAndMessages(null)
            handler = null
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        if (pendingProceedToCall) {
            pendingProceedToCall = false
            doProceedToCall()
        }
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            updateUiForPipMode()
        } else {
            updateUiForNormalMode()
        }
    }

    override fun updateUiForPipMode() {
        binding!!.callAnswerButtons.visibility = View.INVISIBLE
        binding!!.incomingCallRelativeLayout.visibility = View.INVISIBLE
    }

    override fun updateUiForNormalMode() {
        binding!!.callAnswerButtons.visibility = View.VISIBLE
        binding!!.incomingCallRelativeLayout.visibility = View.VISIBLE
    }

    override fun suppressFitsSystemWindows() {
        binding!!.callNotificationLayout.fitsSystemWindows = false
    }

    companion object {
        private val TAG = CallNotificationActivity::class.simpleName
        const val ONE_SECOND: Long = 1000
    }
}
