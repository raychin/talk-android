/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.jobs.clps

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import autodagger.AutoInjector
import cn.jpush.android.api.JPushInterface
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.jobs.PushRegistrationWorker
import com.nextcloud.talk.utils.preferences.AppPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class TalkBackgroundWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    @Inject
    lateinit var appPreferences: AppPreferences

    override suspend fun doWork(): Result {
        NextcloudTalkApplication.Companion.sharedApplication!!.componentApplication.inject(this)

        Log.d(TAG, "TalkBackgroundWorker 推送保活检查 - ${
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        }")

        // 网络不可用时直接跳过，AlarmManager 保活会处理网络恢复后的恢复逻辑
        if (!isNetworkAvailable(applicationContext)) {
            Log.w(TAG, "Network not available, skip this round")
            return Result.success()
        }

        // 1. 主动恢复推送（如果被系统/用户停止）
        JPushInterface.resumePush(applicationContext)

        // 2. 获取 RegistrationID，为空说明推送服务未就绪
        val registrationId = JPushInterface.getRegistrationID(applicationContext)
        if (registrationId.isNullOrEmpty()) {
            Log.w(TAG, "RegistrationID is null, reinitializing JPush")
            JPushInterface.init(applicationContext)
        }

        // 3. 检查厂商通道 token 状态
        JPushInterface.getPushStatus(applicationContext)

        // 4. 如果 RegistrationID 变化了，重新注册到 Nextcloud 服务端
        if (!registrationId.isNullOrEmpty() && registrationId != appPreferences.pushToken) {
            Log.d(TAG, "RegistrationID changed: ${registrationId.take(8)}..., re-registering")
            appPreferences.pushToken = registrationId
            val data = Data.Builder()
                .putString(PushRegistrationWorker.ORIGIN, "TalkBackgroundWorker")
                .build()
            val work = OneTimeWorkRequest.Builder(PushRegistrationWorker::class.java)
                .setInputData(data)
                .build()
            WorkManager.getInstance(applicationContext).enqueue(work)
        }

        return Result.success()
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private val TAG = TalkBackgroundWorker::class.simpleName
    }
}
