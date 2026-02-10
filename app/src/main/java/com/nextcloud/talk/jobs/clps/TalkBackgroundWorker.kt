/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.jobs.clps

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import autodagger.AutoInjector
import cn.jpush.android.api.JPushInterface
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.TALK_WORK_DELAY
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.TALK_WORK_ID
import com.nextcloud.talk.utils.preferences.AppPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class TalkBackgroundWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    @Inject
    lateinit var appPreferences: AppPreferences
    private var context: Context? = null

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun doWork(): Result {
        NextcloudTalkApplication.Companion.sharedApplication!!.componentApplication.inject(this)
        context = applicationContext

        Log.e("Ray", "TalkBackgroundWorker 任务 - ${
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")

        val pushStopped = JPushInterface.isPushStopped(applicationContext)
        Log.e("Ray", "TalkBackgroundWorker PushStopped = $pushStopped")
        if (pushStopped) {
            JPushInterface.resumePush(applicationContext)
            Log.e("Ray", "TalkBackgroundWorker PushStopped2= ${JPushInterface.isPushStopped(applicationContext)}")
        }

        // makeGetRequest()

        // 创建 xx 秒后执行的一次性任务 45S可行
        val delayedWorker = OneTimeWorkRequest.Builder(TalkBackgroundWorker::class.java)
            .setInitialDelay(TALK_WORK_DELAY, TimeUnit.SECONDS)
            .build()
        // WorkManager.getInstance(applicationContext).enqueue(delayedWorker)
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            TALK_WORK_ID,
            // 如果已存在则不再执行
            ExistingWorkPolicy.REPLACE,
            delayedWorker
        )

        // return Result.failure()
        return Result.success()
    }

    private fun makeGetRequest() {
        val appKey = "37674722073737cd729faf02"
        val masterSecret = "1a938464d5c1db479a218618"
        val credentials = "$appKey:$masterSecret"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())

        val httpUrl = "https://api.jpush.cn/v3/messages".toHttpUrlOrNull()?.newBuilder()
        httpUrl?.addQueryParameter("registration_id", appPreferences.pushToken)

        // val request = Request.Builder()
        //     .url("https://api.jpush.cn/v3/messages")
        val request = Request.Builder()
            .url(httpUrl?.build().toString())
            .header("Authorization", "Basic $encodedCredentials")
            .get()
            .build()

        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Request failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.body?.let { responseBody ->
                    Log.d(TAG, "Response: ${responseBody.string()}")
                }
            }
        })
    }

    companion object {
        val TAG = TalkBackgroundWorker::class.simpleName
    }
}
