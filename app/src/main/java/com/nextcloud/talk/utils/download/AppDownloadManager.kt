/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 CLPS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils.download

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.liulishuo.okdownload.DownloadTask
import com.liulishuo.okdownload.OkDownload
import com.liulishuo.okdownload.SpeedCalculator
import com.liulishuo.okdownload.StatusUtil
import com.liulishuo.okdownload.core.breakpoint.BlockInfo
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo
import com.liulishuo.okdownload.core.cause.EndCause
import com.liulishuo.okdownload.core.listener.DownloadListener4
import com.liulishuo.okdownload.core.listener.assist.Listener4Assist
import java.io.File
import java.lang.ref.WeakReference

class AppDownloadManager private constructor(private val context: Context) {

    companion object {
        const val TAG = "AppDownloadManager"
        const val NOTIFICATION_CHANNEL_ID = "ota_download"
        const val NOTIFICATION_CHANNEL_NAME = "OTA下载"
        const val NOTIFICATION_ID = 0x1001
        const val DOWNLOAD_ACTION_CANCEL = "com.nextcloud.talk.DOWNLOAD_CANCEL"
        const val DOWNLOAD_ACTION_BACKGROUND = "com.nextcloud.talk.DOWNLOAD_BACKGROUND"
        const val DOWNLOAD_EXTRA_URL = "download_url"
        const val ACTION_DOWNLOAD_COMPLETE = "com.nextcloud.talk.DOWNLOAD_COMPLETE"
        const val ACTION_DOWNLOAD_ERROR = "com.nextcloud.talk.DOWNLOAD_ERROR"
        const val ACTION_DOWNLOAD_PROGRESS = "com.nextcloud.talk.DOWNLOAD_PROGRESS"

        private const val UPDATE_INTERVAL_MS = 300L

        @Volatile
        private var instance: AppDownloadManager? = null

        fun getInstance(context: Context): AppDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: AppDownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentTask: DownloadTask? = null
    private var activityRef: WeakReference<Activity>? = null
    private var downloadUrl: String? = null
    private var destFile: File? = null
    private var speedCalculator = SpeedCalculator()
    private var totalContentLength: Long = 0L
    private var isPaused = false

    // OkDownload 单例初始化（必须在属性初始化之后）
    init {
        if (OkDownload.with() == null) {
            OkDownload.Builder(context).build()
        }
        createNotificationChannel()
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DOWNLOAD_ACTION_CANCEL -> cancelDownload()
            }
        }
    }

    init {
        val filter = IntentFilter(DOWNLOAD_ACTION_CANCEL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(downloadReceiver, filter)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "应用升级下载通知"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 开始下载APK文件
     * @param url 下载地址
     * @param fileName 文件名
     * @param activity 当前Activity（用于回调更新UI）
     */
    fun startDownload(url: String, fileName: String, activity: Activity?) {
        this.downloadUrl = url
        this.activityRef = WeakReference(activity)
        isPaused = false

        val dir = File(context.getExternalFilesDir(null), "updates")
        if (!dir.exists()) dir.mkdirs()

        val parentPath = dir.absolutePath
        val file = File(parentPath, fileName)
        destFile = file

        // 已完成则直接安装
        val checkTask = DownloadTask.Builder(url, parentPath, fileName).build()
        if (StatusUtil.getStatus(checkTask) == StatusUtil.Status.COMPLETED) {
            installApk(file)
            return
        }

        val task = DownloadTask.Builder(url, parentPath, fileName)
            .setMinIntervalMillisCallbackProcess(30)
            .setPassIfAlreadyCompleted(false)
            .build()

        currentTask = task
        showDownloadProgress(0, 0, 0, 0)
        speedCalculator.reset()

        task.execute(object : DownloadListener4() {

            override fun taskStart(task: DownloadTask) {
                Log.d(TAG, "taskStart -> 下载开始: ${task.getUrl()}")
                broadcastProgress(0, 0L, 0, 0)
            }

            override fun connectStart(
                task: DownloadTask,
                blockIndex: Int,
                requestHeaderFields: Map<String?, List<String?>?>
            ) {
                Log.d(TAG, "connectStart -> block=$blockIndex")
            }

            override fun connectEnd(
                task: DownloadTask,
                blockIndex: Int,
                responseCode: Int,
                responseHeaderFields: Map<String?, List<String?>?>
            ) {
                Log.d(TAG, "connectEnd -> block=$blockIndex, code=$responseCode")
            }

            override fun infoReady(
                task: DownloadTask?,
                info: BreakpointInfo,
                reuseBreakpointFromNetwork: Boolean,
                model: Listener4Assist.Listener4Model
            ) {
                totalContentLength = info.totalLength
                Log.d(TAG, "infoReady -> 总长度=$totalContentLength")
            }

            override fun progressBlock(task: DownloadTask?, blockIndex: Int, currentBlockOffset: Long) {}

            override fun progress(task: DownloadTask?, currentOffset: Long) {
                val total = totalContentLength
                val progress = if (total > 0) ((currentOffset * 100 / total).toInt()) else 0
                speedCalculator.downloading(currentOffset)
                val speedStr = speedCalculator.speed()
                val speed = speedStr.toLongOrNull() ?: 0L
                updateDownloadProgress(progress, speed, currentOffset, total)
            }

            override fun blockEnd(task: DownloadTask?, blockIndex: Int, info: BlockInfo?) {
                Log.d(TAG, "blockEnd -> block=$blockIndex 完成")
            }

            override fun taskEnd(
                task: DownloadTask?,
                cause: EndCause?,
                realCause: Exception?,
                model: Listener4Assist.Listener4Model
            ) {
                when (cause) {
                    EndCause.COMPLETED -> {
                        Log.d(TAG, "taskEnd -> 下载完成")
                        cancelNotification()
                        destFile?.let { file ->
                            if (file.exists() && file.length() > 0) {
                                installApk(file)
                            } else {
                                showErrorNotification("下载文件异常，请重试")
                            }
                        }
                        broadcastComplete(true)
                    }
                    EndCause.CANCELED -> {
                        Log.d(TAG, "taskEnd -> 下载已取消")
                        cancelNotification()
                        notifyActivityCancel()
                        broadcastComplete(false)
                    }
                    EndCause.ERROR, EndCause.SAME_TASK_BUSY, EndCause.FILE_BUSY,
                    EndCause.PRE_ALLOCATE_FAILED -> {
                        Log.e(TAG, "taskEnd -> 下载错误 cause=${cause}, error=${realCause?.message}", realCause)
                        // HttpStream NPE 特殊处理：网络连接层面问题
                        val errorMsg = if (realCause is NullPointerException &&
                            (realCause.message?.contains("HttpStream") == true ||
                             realCause.stackTraceToString().contains("HttpStream"))
                        ) {
                            "网络连接异常，请检查网络后重试"
                        } else {
                            "下载失败: ${realCause?.message ?: "未知错误"}"
                        }
                        showErrorNotification(errorMsg)
                        notifyActivityError(realCause)
                        broadcastError(errorMsg)
                    }
                    else -> {
                        Log.w(TAG, "taskEnd -> 未处理状态 $cause")
                    }
                }
                currentTask = null
            }
        })
    }

    /**
     * 暂停下载
     */
    fun pauseDownload() {
        currentTask?.let { task ->
            try {
                isPaused = true
                // okdownload 不支持真正的 pause，用 cancel 替代（断点信息保留在SQLite中）
                task.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "暂停下载失败", e)
            }
        }
    }

    /**
     * 恢复下载
     */
    fun resumeDownload(activity: Activity?) {
        val url = downloadUrl ?: run {
            Log.w(TAG, "恢复下载失败：无可用URL")
            return
        }
        destFile?.let { file ->
            startDownload(url, file.name, activity)
        }
    }

    /**
     * 取消当前下载（同时清除断点记录）
     */
    fun cancelDownload() {
        isPaused = false
        currentTask?.let { task ->
            try {
                // 清除断点信息
                OkDownload.with().breakpointStore().remove(task.id)
                task.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "取消下载失败", e)
            }
        }
        currentTask = null
        cancelNotification()
    }

    /**
     * 是否正在下载中
     */
    fun isDownloading(): Boolean = currentTask != null && !isPaused

    /**
     * 是否可以恢复（存在未完成的断点记录）
     */
    fun hasPendingDownload(): Boolean {
        val dir = File(context.getExternalFilesDir(null), "updates")
        if (!dir.exists()) return false
        val parentPath = dir.absolutePath
        return dir.listFiles()?.any { file ->
            val checkTask = DownloadTask.Builder("", parentPath, file.name).build()
            val status = StatusUtil.getStatus(checkTask)
            status == StatusUtil.Status.IDLE || status == StatusUtil.Status.UNKNOWN
        } == true
    }

    // ==================== 内部方法 ====================

    private fun showDownloadProgress(progress: Int, speed: Long, currentOffset: Long, totalLength: Long) {
        updateDownloadProgress(progress, speed, currentOffset, totalLength)
    }

    private fun updateDownloadProgress(progress: Int, speed: Long, currentOffset: Long, totalLength: Long) {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载新版本...")
            .setContentText(formatProgress(progress, speed))
            .setProgress(100, progress, false)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "取消", createCancelPendingIntent())
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)

        // 通知 Activity 更新 UI
        activityRef?.get()?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.runOnUiThread {
                    (activity as? OnDownloadProgressListener)?.onDownloadProgressChanged(
                        progress, speed, currentOffset, totalLength
                    )
                }
            }
        }

        // 广播进度
        broadcastProgress(progress, speed, currentOffset, totalLength)
    }

    private fun formatProgress(progress: Int, speed: Long): String {
        val speedStr = formatSize(speed)
        return "${progress}% ($speedStr/s)"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
        }
    }

    private fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("下载失败")
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun createCancelPendingIntent(): PendingIntent {
        val intent = Intent(DOWNLOAD_ACTION_CANCEL).apply {
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun installApk(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setDataAndType(
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    ),
                    "application/vnd.android.package-archive"
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "安装APK失败", e)
            showErrorNotification("安装失败: ${e.message}")
        }
    }

    // ==================== Activity 通知 ====================

    private fun notifyActivityCancel() {
        activityRef?.get()?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.runOnUiThread {
                    (activity as? OnDownloadProgressListener)?.onDownloadCanceled()
                }
            }
        }
    }

    private fun notifyActivityError(error: Exception?) {
        activityRef?.get()?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.runOnUiThread {
                    (activity as? OnDownloadProgressListener)?.onDownloadError(error?.message ?: "未知错误")
                }
            }
        }
    }

    // ==================== LocalBroadcast ====================

    private fun broadcastProgress(progress: Int, speed: Long, currentOffset: Long, totalLength: Long) {
        val intent = Intent(ACTION_DOWNLOAD_PROGRESS).apply {
            putExtra("progress", progress)
            putExtra("speed", speed)
            putExtra("current_offset", currentOffset)
            putExtra("total_length", totalLength)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private fun broadcastComplete(success: Boolean) {
        val intent = Intent(ACTION_DOWNLOAD_COMPLETE).apply {
            putExtra("success", success)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private fun broadcastError(errorMsg: String) {
        val intent = Intent(ACTION_DOWNLOAD_ERROR).apply {
            putExtra("error_message", errorMsg)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    // ==================== 接口 ====================

    interface OnDownloadProgressListener {
        fun onDownloadProgressChanged(progress: Int, speed: Long, currentOffset: Long, totalLength: Long)
        /** 下载被取消 */
        fun onDownloadCanceled() {}
        /** 下载出错 */
        fun onDownloadError(errorMessage: String) {}
    }

    /**
     * 销毁资源，应在 Application 或退出时调用
     */
    fun destroy() {
        try {
            context.unregisterReceiver(downloadReceiver)
        } catch (_: Exception) {}
        currentTask = null
        activityRef = null
    }
}
