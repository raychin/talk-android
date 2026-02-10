/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.jobs.clps

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import autodagger.AutoInjector
import cn.jpush.android.api.JPushInterface
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.jobs.PushRegistrationWorker
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.preferences.AppPreferences
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class GetJPushTokenWorker(val context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    @Inject
    lateinit var userManager: UserManager

    private var currentUser: User? = null
    @Inject
    lateinit var appPreferences: AppPreferences

    @SuppressLint("LongLogTag")
    override fun doWork(): Result {
        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)

        val registrationId = JPushInterface.getRegistrationID(applicationContext)
        Log.d(TAG, "GetJPushTokenWorker: getRegistrationID: $registrationId")

        currentUser = userManager.currentUser.blockingGet()

        // 获取当前服务并拼成toke
        appPreferences.pushToken = "$registrationId"
        // TODO RAY alias有延时，暂时不用
        // appPreferences.pushToken = "${registrationId}|${currentUser!!.id}"
        // val sequence = 0
        // JPushInterface.deleteAlias(applicationContext, sequence)
        // // 同时将本设备设置极光alias
        // JPushInterface.setAlias(applicationContext, sequence, appPreferences.pushToken)

        appPreferences.pushTokenLatestFetch = System.currentTimeMillis()
        val data: Data =
            Data.Builder().putString(PushRegistrationWorker.ORIGIN, "GetJPushTokenWorker").build()
        val pushRegistrationWork = OneTimeWorkRequest.Builder(PushRegistrationWorker::class.java)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(pushRegistrationWork)

        return Result.success()
    }

    companion object {
        private val TAG = GetJPushTokenWorker::class.simpleName
    }
}
