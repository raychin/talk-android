/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.jpush

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import cn.jpush.android.api.*
import cn.jpush.android.service.JPushMessageReceiver
import com.nextcloud.talk.jobs.NotificationWorker
import com.nextcloud.talk.utils.bundle.BundleKeys
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CJPushMessageReceiver : JPushMessageReceiver() {

    override fun onMessage(context: Context, customMessage: CustomMessage) {
        super.onMessage(context, customMessage)
        Log.d("Ray", "Received custom message: $customMessage}")

        // 此方法接收自定义消息，不会有Notification
        /**
         * {messageId='18102837983815159', extra='{"signature":"123131231231231","subject":"324334234234234234"}', message='Ray自定义消息15:39', contentType='', title='', senderId='37674722073737cd729faf02', appId='com.nextcloud.talk2.clps', platform='0'}}
         */
        // 处理推送消息
        handlePushMessage(context, customMessage)
    }

    override fun onNotifyMessageArrived(context: Context, notificationMessage: NotificationMessage) {
        super.onNotifyMessageArrived(context, notificationMessage)
        // 此方法接收通知消息，会有Notification
        /**
         * {notificationId=525271935, msgId='18102837965808291', appkey='37674722073737cd729faf02', notificationContent='新的消息15:34', notificationAlertType=7, notificationTitle='RayExtra', notificationSmallIcon='', notificationLargeIcon='', notificationExtras='{"signature":"signature23424234","subject":"subject121431313213"}', notificationStyle=0, notificationBuilderId=0, notificationBigText='', notificationBigPicPath='', notificationInbox='', notificationPriority=0, notificationImportance=-1, notificationCategory='', developerArg0='', platform=0, notificationChannelId='', displayForeground='', notificationType=0', inAppMsgType=1', inAppMsgShowType=2', inAppMsgShowPos=0', inAppMsgTitle=, inAppMsgContentBody=, inAppType=0, inAppShowTarget=, inAppClickAction=, inAppExtras=, customButtons=null}}
         */
        Log.d("Ray", "Received notification message: $notificationMessage}")

        // notificationExtras
        val msgObject = CustomMessage ()
        msgObject.extra = notificationMessage.notificationExtras
        handlePushMessage(context, msgObject)
    }

    override fun onConnected(context: Context, isConnected: Boolean) {
        super.onConnected(context, isConnected)
        Log.d("Ray", "onConnected isConnected: $isConnected")
    }

    override fun onAliasOperatorResult(context: Context?, jPushMessage: JPushMessage?) {
        super.onAliasOperatorResult(context, jPushMessage)
        Log.d("Ray", "onAliasOperatorResult jPushMessage: ${jPushMessage.toString()}")
    }

    override fun onCommandResult(context: Context?, cmdMessage: CmdMessage?) {
        super.onCommandResult(context, cmdMessage)
        /**
         * cmd: 2003
         * errorCode: 0 表示未停止，1 表示已停止，其他code 表示其他异常
         * msg: "not stop" 表示未停止，"stopped" 表示已停止
         */
        Log.d("Ray", "onCommandResult errorCode = ${cmdMessage?.errorCode}, ${
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        }")
        if (0 != cmdMessage?.errorCode) {
            JPushInterface.resumePush(context)
        }
    }

    override fun onNotifyMessageOpened(context: Context, notificationMessage: NotificationMessage) {
        super.onNotifyMessageOpened(context, notificationMessage)
        Log.d("Ray", "Notification clicked, extras: ${notificationMessage.toString()}")

        // 处理通知点击事件
        handleNotificationClick(context, notificationMessage)
    }

    private fun jsonToMap(jsonString: String): Map<String, Any> {
        val jsonObject = JSONObject(jsonString)
        val map = mutableMapOf<String, Any>()
        jsonObject.keys().forEach { key ->
            map[key] = jsonObject.get(key)
        }
        return map
    }

    private fun handlePushMessage(context: Context, message: CustomMessage) {
        // // 实现消息处理逻辑
        // val title = bundle.getString(JPushInterface.EXTRA_TITLE)
        // val content = bundle.getString(JPushInterface.EXTRA_ALERT)
        // val extras = bundle.getString(JPushInterface.EXTRA_EXTRA)

        // 这里需要根据 Nextcloud Talk 的推送格式处理消息
        Log.d("Ray", "handlePushMessage custom message: $message}")

        // 同服务端确定subject和signature从哪里取值
        var dataJson = message.extra
        if (dataJson.isNullOrEmpty()) {
            dataJson = "{}"
        }
        val extraData = jsonToMap(dataJson)
        Log.d("Ray", "data: $extraData")
        val subject = extraData[KEY_NOTIFICATION_SUBJECT] as? String
        val signature = extraData[KEY_NOTIFICATION_SIGNATURE] as? String

        Log.e("Ray", "subject: $subject")
        Log.e("Ray", "signature: $signature")
        if (!subject.isNullOrEmpty() && !signature.isNullOrEmpty()) {
            Log.d("Ray", "-------------------------------")
            val messageData = Data.Builder()
                .putString(BundleKeys.KEY_NOTIFICATION_SUBJECT, subject)
                .putString(BundleKeys.KEY_NOTIFICATION_SIGNATURE, signature)
                .build()
            val notificationWork =
                OneTimeWorkRequest.Builder(NotificationWorker::class.java).setInputData(messageData)
                    .build()
            try {
                WorkManager.getInstance(context.applicationContext).enqueue(notificationWork)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue work: ${e.message}", e)
            }
        }
    }

    // TODO RAY 这个是com.nextcloud.talk.services.firebase.NCFirebaseMessagingService token过期逻辑
    // override fun onNewToken(token: String) {
    //     super.onNewToken(token)
    //     Log.d(TAG, "onNewToken. token = $token")
    //
    //     appPreferences.pushToken = token
    //     appPreferences.pushTokenLatestGeneration = System.currentTimeMillis()
    //
    //     val data: Data =
    //         Data.Builder().putString(PushRegistrationWorker.ORIGIN, "NCFirebaseMessagingService#onNewToken").build()
    //     val pushRegistrationWork = OneTimeWorkRequest.Builder(PushRegistrationWorker::class.java)
    //         .setInputData(data)
    //         .build()
    //     WorkManager.getInstance().enqueue(pushRegistrationWork)
    // }

    companion object {
        private val TAG = CJPushMessageReceiver::class.simpleName
        const val KEY_NOTIFICATION_SUBJECT = "subject"
        const val KEY_NOTIFICATION_SIGNATURE = "signature"
    }

    private fun handleNotificationClick(context: Context, notificationMessage: NotificationMessage) {
        // // 处理通知点击，跳转到相应页面
        // val title = bundle.getString(JPushInterface.EXTRA_TITLE)
        // val content = bundle.getString(JPushInterface.EXTRA_ALERT)

        // 实现跳转逻辑
        // 可能需要打开特定的聊天会话
    }
}
