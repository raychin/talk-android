/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 CLPS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils.download

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nextcloud.talk.R
import com.nextcloud.talk.events.OtaUpgradeEvent
import com.nextcloud.talk.models.json.ota.OtaUpgradeData
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局 OTA 升级管理器，独立于 Activity 生命周期运行。
 *
 * 通过 Application 初始化，永久注册 EventBus，监听 [OtaUpgradeEvent]。
 * 使用 ActivityLifecycleCallbacks 追踪当前前台 Activity，实现跨页面弹框连续显示：
 * - Activity 暂停时 dismiss 弹框（避免 window leak）
 * - Activity 恢复时根据保存的状态重建弹框
 */
class OtaUpgradeManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "OtaUpgradeManager"
        @Volatile
        private var instance: OtaUpgradeManager? = null

        fun getInstance(context: Context): OtaUpgradeManager {
            return instance ?: synchronized(this) {
                instance ?: OtaUpgradeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    //region 弹框状态枚举
    private enum class DialogState {
        NONE,               // 无弹框
        NORMAL_UPDATE,      // 普通更新提示弹框
        FORCE_UPDATE,       // 强制更新弹框
        DOWNLOADING         // 下载进度弹框
    }
    //endregion

    //region 状态保存（Activity切换后用于恢复）
    private var dialogState = DialogState.NONE
    private var pendingEventData: OtaUpgradeData? = null
    private var isForceUpdate = false
    private var currentDownloadUrl: String? = null
    private var currentProgress = 0
    private var currentSpeed = 0L
    private var supportBackgroundDownload = false
    //endregion

    //region UI 引用（弱引用避免内存泄漏）
    private var activityRef: WeakReference<Activity>? = null
    private var progressDialog: AlertDialog? = null
    private var downloadProgressBar: ProgressBar? = null
    private var downloadProgressText: TextView? = null
    private var downloadSpeedText: TextView? = null
    //endregion

    //region 下载管理器
    private val appDownloadManager: AppDownloadManager by lazy { AppDownloadManager.getInstance(appContext) }
    //endregion

    private val eventBus: EventBus = EventBus.getDefault()

    /**
     * 初始化：注册 EventBus 和 ActivityLifecycleCallbacks
     * 必须在 Application.onCreate() 中调用
     */
    fun initialize() {
        if (!eventBus.isRegistered(this)) {
            eventBus.register(this)
        }
    }

    /**
     * 是否有活跃的弹框（用于外部判断是否已处理了更新事件）
     */
    fun hasActiveDialog(): Boolean = dialogState != DialogState.NONE

    /**
     * 销毁资源，在 Application.onTerminate() 中调用
     */
    fun destroy() {
        if (eventBus.isRegistered(this)) {
            eventBus.unregister(this)
        }
        dismissAllDialogs()
        cancelDownload()
    }

    // ==================== EventBus 订阅 ====================

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: OtaUpgradeEvent) {
        if (!event.tryConsume()) return
        val data = event.otaUpgradeData
        isForceUpdate = data.forceUpdate
        currentDownloadUrl = data.downloadUrl
        pendingEventData = data

        val activity = activityRef?.get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            showUpdateDialog(activity, data)
        } else {
            // 无可用Activity，保存状态等待恢复
            dialogState = if (data.forceUpdate) DialogState.FORCE_UPDATE else DialogState.NORMAL_UPDATE
        }
    }

    // ==================== ActivityLifecycleCallbacks ====================

    /** 当前 Activity resume 时调用 */
    fun onActivityResumed(activity: Activity) {
        activityRef = WeakReference(activity)
        // 同步下载管理器的 Activity 引用
        appDownloadManager.updateActivityRef(activity)
        when (dialogState) {
            DialogState.NORMAL_UPDATE -> {
                pendingEventData?.let { showNormalUpdateDialog(activity, it) }
            }
            DialogState.FORCE_UPDATE -> {
                pendingEventData?.let { showForceUpdateDialog(activity, it) }
            }
            DialogState.DOWNLOADING -> {
                restoreProgressDialog(activity)
            }
            DialogState.NONE -> { /* 无需操作 */ }
        }
    }

    /** 当前 Activity pause 时调用 */
    fun onActivityPaused(activity: Activity) {
        // dismiss 所有弹框避免 window leak
        dismissAllDialogs()
    }

    /** Activity 销毁时清除引用 */
    fun onActivityDestroyed(activity: Activity) {
        activityRef?.get()?.let {
            if (it === activity) {
                activityRef = null
            }
        }
    }

    // ==================== 弹框显示逻辑 ====================

    private fun showUpdateDialog(activity: Activity, data: OtaUpgradeData) {
        if (data.forceUpdate) {
            showForceUpdateDialog(activity, data)
        } else {
            showNormalUpdateDialog(activity, data)
        }
    }

    private fun showNormalUpdateDialog(activity: Activity, data: OtaUpgradeData) {
        dialogState = DialogState.NORMAL_UPDATE
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ota_update_title)
            .setMessage(activity.getString(R.string.ota_update_message, data.latestVersion ?: ""))
            .setPositiveButton(R.string.nc_yes) { dialog, _ ->
                dialog.dismiss()
                dialogState = DialogState.NONE
                startDownload(activity, supportBackgroundDownload = true)
            }
            .setNegativeButton(R.string.nc_no) { dialog, _ ->
                dialog.dismiss()
                resetState()
                saveOtaCheckDate()
            }
            .setCancelable(false)
            .show()
    }

    private fun showForceUpdateDialog(activity: Activity, data: OtaUpgradeData) {
        dialogState = DialogState.FORCE_UPDATE
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ota_force_update_title)
            .setMessage(activity.getString(R.string.ota_force_update_message, data.latestVersion ?: ""))
            .setPositiveButton(R.string.nc_yes) { dialog, _ ->
                // 跳转到浏览器下载，暂不关闭
                // dialog.dismiss()
                // dialogState = DialogState.NONE
                startDownload(activity, supportBackgroundDownload = false)
            }
            .setNegativeButton(R.string.nc_no) { dialog, _ ->
                dialog.dismiss()
                resetState()
                activity.finishAffinity()
            }
            .setCancelable(false)
            .show()
    }

    // ==================== 下载逻辑（AppDownloadManager / okdownload）====================

    /**
     * 使用 [AppDownloadManager] 进行 APK 下载。
     * 通过 [AppDownloadManager.OnDownloadProgressListener] 接收进度回调。
     */
    private fun startDownload(activity: Activity, supportBackgroundDownload: Boolean) {
        val url = currentDownloadUrl ?: return

        // TODO RAY 跳转到系统自带下载管理器，使用url进行下载
        openBrowserForDownload(activity, url, supportBackgroundDownload)
        return

        this.supportBackgroundDownload = supportBackgroundDownload
        dialogState = DialogState.DOWNLOADING

        val fileName = extractFileName(url) ?: "update_${System.currentTimeMillis()}.apk"

        // 显示进度弹框
        showProgressDialog(activity, supportBackgroundDownload)

        // 使用 AppDownloadManager 开始下载，传入进度监听器
        appDownloadManager.updateActivityRef(activity)
        appDownloadManager.startDownload(url, fileName, activity, downloadProgressListener)
    }

    /**
     * 跳转到浏览器进行下载
     */
    private fun openBrowserForDownload(activity: Activity, url: String, supportBackgroundDownload: Boolean) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)

            android.widget.Toast.makeText(
                activity,
                activity.getString(R.string.ota_browser_download_hint),
                android.widget.Toast.LENGTH_LONG
            ).show()

            if (supportBackgroundDownload) {
                dismissAllDialogs()
                resetState()
                saveOtaCheckDate()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(
                activity,
                activity.getString(R.string.ota_open_browser_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 下载进度监听器 */
    private val downloadProgressListener = object : AppDownloadManager.OnDownloadProgressListener {
        override fun onDownloadProgressChanged(progress: Int, speed: Long, currentOffset: Long, totalLength: Long) {
            currentProgress = progress
            currentSpeed = speed
            updateProgressDialogUI(progress, speed)
        }

        override fun onDownloadCanceled() {
            handleDownloadCanceled()
        }

        override fun onDownloadError(errorMessage: String) {
            handleDownloadError(errorMessage)
        }
    }

    /**
     * 显示下载进度弹框，包含进度条、百分比、速度、取消按钮。
     */
    private fun showProgressDialog(activity: Activity, supportBgDownload: Boolean) {
        val inflater = android.view.LayoutInflater.from(activity)
        val dialogView = inflater.inflate(R.layout.dialog_download_progress, null)
        downloadProgressBar = dialogView.findViewById(R.id.progress_bar)
        downloadProgressText = dialogView.findViewById(R.id.progress_text)
        downloadSpeedText = dialogView.findViewById(R.id.speed_text)

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.ota_downloading_title)
            .setView(dialogView)
            .setCancelable(false)

        if (supportBgDownload) {
            builder.setPositiveButton(R.string.ota_download_background) { dialog, _ ->
                dialog.dismiss()
                progressDialog = null
                clearDownloadViewRefs()
                // 后台下载继续（AppDownloadManager 在通知栏运行）
            }
            builder.setNegativeButton(R.string.ota_download_cancel) { dialog, _ ->
                dialog.dismiss()
                progressDialog = null
                clearDownloadViewRefs()
                cancelDownload()
                if (isForceUpdate) {
                    activity.finishAffinity()
                } else {
                    saveOtaCheckDate()
                }
                resetState()
            }
        } else {
            builder.setNegativeButton(R.string.ota_download_cancel) { dialog, _ ->
                dialog.dismiss()
                progressDialog = null
                clearDownloadViewRefs()
                cancelDownload()
                activity.finishAffinity()
            }
        }

        progressDialog = builder.show()

        // 恢复之前的进度（跨 Activity 切换场景）
        downloadProgressBar?.progress = currentProgress
        downloadProgressText?.text = "${currentProgress}%"
        downloadSpeedText?.text = "${formatSize(currentSpeed)}/s"
    }

    /** 在新 Activity 上重建下载进度弹框（从暂停状态恢复） */
    private fun restoreProgressDialog(activity: Activity) {
        showProgressDialog(activity, supportBackgroundDownload)
    }

    /** 取消下载 */
    private fun cancelDownload() {
        appDownloadManager.cancelDownload()
    }

    /** 处理下载被取消 */
    private fun handleDownloadCanceled() {
        val activity = activityRef?.get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            dismissAllDialogs()
            resetState()
        }
    }

    /** 处理下载出错 */
    private fun handleDownloadError(errorMessage: String) {
        val activity = activityRef?.get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            dismissAllDialogs()
            android.widget.Toast.makeText(
                activity, errorMessage, android.widget.Toast.LENGTH_SHORT
            ).show()
            resetState()
        }
    }

    private fun updateProgressDialogUI(progress: Int, speed: Long) {
        if (progressDialog != null && !progressDialog!!.isShowing) return
        downloadProgressBar?.progress = progress
        downloadProgressText?.text = "${progress}%"
        downloadSpeedText?.text = "${formatSize(speed)}/s"
    }

    // ==================== 工具方法 ====================

    private fun dismissAllDialogs() {
        try {
            progressDialog?.dismiss()
        } catch (_: Exception) { }
        progressDialog = null
        clearDownloadViewRefs()
    }

    private fun clearDownloadViewRefs() {
        downloadProgressBar = null
        downloadProgressText = null
        downloadSpeedText = null
    }

    private fun resetState() {
        dialogState = DialogState.NONE
        pendingEventData = null
        isForceUpdate = false
        currentDownloadUrl = null
        currentProgress = 0
        currentSpeed = 0L
        supportBackgroundDownload = false
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
        }
    }

    private fun extractFileName(url: String): String? {
        return try {
            val pathSegments = java.net.URI(url).path.split("/")
            pathSegments.lastOrNull()?.takeIf { it.isNotEmpty() && it.contains(".") }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveOtaCheckDate() {
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        appContext.getSharedPreferences("ota_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString("ota_check_date", currentDate)
            .apply()
    }
}
