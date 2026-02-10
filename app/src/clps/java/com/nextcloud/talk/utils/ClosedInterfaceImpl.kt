/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Andy Scherzinger <info@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2022 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2017-2019 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.text.TextUtils
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import autodagger.AutoInjector
import cn.jpush.android.api.JPushInterface
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.interfaces.ClosedInterface
import com.nextcloud.talk.jobs.clps.GetJPushTokenWorker

@AutoInjector(NextcloudTalkApplication::class)
class ClosedInterfaceImpl : ClosedInterface {

    override val isGooglePlayServicesAvailable: Boolean = isJPushAvailable()
    override fun providerInstallerInstallIfNeededAsync() {
        // TODO RAY 不适用firebase推送
    }

    private fun isJPushAvailable(): Boolean {
        // 集成极光，重写这里当作谷歌服务可用
        val regId = JPushInterface.getRegistrationID(sharedApplication)
        return !TextUtils.isEmpty(regId)
    }

    override fun setUpPushTokenRegistration() {
        val jPushTokenWorker = OneTimeWorkRequest.Builder(GetJPushTokenWorker::class.java).build()
        WorkManager.getInstance(sharedApplication!!.applicationContext).enqueue(jPushTokenWorker)
    }

    companion object {
        private val TAG = ClosedInterfaceImpl::class.java.simpleName
        const val DAILY: Long = 24
        const val FLEX_INTERVAL: Long = 10
    }
}
