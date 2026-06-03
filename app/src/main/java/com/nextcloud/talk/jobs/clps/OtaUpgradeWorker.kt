/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.jobs.clps

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import autodagger.AutoInjector
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.events.OtaUpgradeEvent
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.database.user.CurrentUserProviderOld
import org.greenrobot.eventbus.EventBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.core.content.edit

@AutoInjector(NextcloudTalkApplication::class)
class OtaUpgradeWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    @Inject
    lateinit var ncApi: NcApi

    @Inject
    lateinit var currentUserProviderOld: CurrentUserProviderOld

    @Inject
    lateinit var eventBus: EventBus

    companion object {
        private val TAG = OtaUpgradeWorker::class.java.simpleName
        const val PREF_KEY_OTA_CHECK_DATE = "ota_check_date"
        const val PREFS_NAME = "ota_preferences"
    }

    override fun doWork(): Result {
        Log.d(TAG, "=== OtaUpgradeWorker started ===")
        NextcloudTalkApplication.Companion.sharedApplication!!.componentApplication.inject(this)
        try {

            val currentUser = currentUserProviderOld.currentUser.blockingGet()
            if (currentUser == null) {
                Log.w(TAG, "No current user, skip OTA check")
                return Result.success()
            }

            // 同日内已检查过则跳过
            if (isSameDayOtaChecked(applicationContext)) {
                Log.d(TAG, "Already checked today, skip")
                return Result.success()
            }

            val baseUrl = currentUser.baseUrl ?: run {
                Log.w(TAG, "No base url, skip OTA check")
                return Result.success()
            }
            val credentials =
                ApiUtils.getCredentials(currentUser.username, currentUser.token) ?: run {
                    Log.w(TAG, "No credentials, skip OTA check")
                    return Result.success()
                }

            val versionName = try {
                applicationContext.packageManager.getPackageInfo(
                    applicationContext.packageName,
                    0
                ).versionName
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get version name", e)
                return Result.success()
            } ?: run {
                Log.w(TAG, "Version name is null, skip OTA check")
                return Result.success()
            }

            val checkUrl = ApiUtils.getUrlForOtaUpgrade(baseUrl, "v$versionName")
            Log.d(TAG, "Checking OTA upgrade: $checkUrl")

            val result = ncApi.checkOtaUpgrade(credentials, checkUrl).blockingFirst()

            val ocsData = result.ocs?.data

            if (ocsData == null) {
                Log.d(TAG, "OTA data is null")
                saveOtaCheckDate(applicationContext)
                return Result.success()
            }

            if (!ocsData.needUpdate) {
                // 无需更新，缓存日期
                Log.d(TAG, "No update available")
                saveOtaCheckDate(applicationContext)
                return Result.success()
            }

            // 需要更新，通过EventBus通知前台Activity显示弹框
            Log.d(
                TAG,
                "Update available: ${ocsData.latestVersion}, forceUpdate=${ocsData.forceUpdate}"
            )
            eventBus.post(OtaUpgradeEvent(ocsData))

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "OTA check failed", e)
            return Result.retry()
        }
    }

    private fun isSameDayOtaChecked(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheckDate = prefs.getString(PREF_KEY_OTA_CHECK_DATE, null) ?: return false
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return currentDate == lastCheckDate
    }

    private fun saveOtaCheckDate(context: Context) {
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(PREF_KEY_OTA_CHECK_DATE, currentDate)
            }
    }
}
