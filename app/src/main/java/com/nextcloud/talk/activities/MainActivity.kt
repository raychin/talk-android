/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2023 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2023 Ezhil Shanmugham <ezhil56x.contact@gmail.com>
 * SPDX-FileCopyrightText: 2021 Andy Scherzinger <infoi@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2017 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.activities

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import autodagger.AutoInjector
import cn.jpush.android.api.JPushInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.talk.R
import com.nextcloud.talk.account.BrowserLoginActivity
import com.nextcloud.talk.account.ServerSelectionActivity
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.chat.ChatActivity
import com.nextcloud.talk.conversationlist.ConversationsListActivity
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.databinding.ActivityMainBinding
import com.nextcloud.talk.invitation.InvitationsActivity
import com.nextcloud.talk.lock.LockedActivity
import com.nextcloud.talk.models.json.ota.OtaUpgradeData
import com.nextcloud.talk.models.json.ota.OtaUpgradeOverall
import com.nextcloud.talk.models.json.conversations.RoomOverall
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.ClosedInterfaceImpl
import com.nextcloud.talk.utils.SecurityUtils
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import com.nextcloud.talk.utils.download.AppDownloadManager
import io.reactivex.Observer
import io.reactivex.SingleObserver
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class MainActivity :
    BaseActivity(),
    ActionBarProvider,
    AppDownloadManager.OnDownloadProgressListener {

    lateinit var binding: ActivityMainBinding

    @Inject
    lateinit var ncApi: NcApi

    @Inject
    lateinit var userManager: UserManager

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            finish()
        }
    }

    private var progressDialog: AlertDialog? = null
    private var downloadManager: AppDownloadManager? = null
    private var isForceUpdate = false
    private var currentDownloadUrl: String? = null
    private var downloadProgressBar: ProgressBar? = null
    private var downloadProgressText: TextView? = null
    private var downloadSpeedText: TextView? = null

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val PREF_KEY_OTA_CHECK_DATE = "ota_check_date"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate: Activity: " + System.identityHashCode(this).toString())

        super.onCreate(savedInstanceState)

        Log.d(TAG, "onStart: getRegistrationID: " + JPushInterface.getRegistrationID(applicationContext))
        Log.d(TAG, "onStart: getRegistrationID isGooglePlayServicesAvailable: " + ClosedInterfaceImpl().isGooglePlayServicesAvailable)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lockScreenIfConditionsApply()
            }
        })

        // Set the default theme to replace the launch screen theme.
        setTheme(R.style.AppTheme)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)

        setSupportActionBar(binding.toolbar)

        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
        
        // 检查OTA更新
        checkOtaUpgrade()
    }

    fun lockScreenIfConditionsApply() {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardSecure && appPreferences.isScreenLocked) {
            if (!SecurityUtils.checkIfWeAreAuthenticated(appPreferences.screenLockTimeout)) {
                val lockIntent = Intent(context, LockedActivity::class.java)
                startActivity(lockIntent)
            }
        }
    }

    private fun launchServerSelection() {
        if (isBrandingUrlSet()) {
            val intent = Intent(context, BrowserLoginActivity::class.java)
            val bundle = Bundle()
            bundle.putString(BundleKeys.KEY_BASE_URL, resources.getString(R.string.weblogin_url))
            intent.putExtras(bundle)
            startActivity(intent)
        } else {
            val intent = Intent(context, ServerSelectionActivity::class.java)
            startActivity(intent)
        }
    }

    private fun isBrandingUrlSet() = !TextUtils.isEmpty(resources.getString(R.string.weblogin_url))

    override fun onStart() {
        Log.d(TAG, "onStart: Activity: " + System.identityHashCode(this).toString())
        super.onStart()
    }

    override fun onResume() {
        Log.d(TAG, "onResume: Activity: " + System.identityHashCode(this).toString())
        super.onResume()

        if (appPreferences.isScreenLocked) {
            SecurityUtils.createKey(appPreferences.screenLockTimeout)
        }
    }

    override fun onPause() {
        Log.d(TAG, "onPause: Activity: " + System.identityHashCode(this).toString())
        super.onPause()
    }

    override fun onStop() {
        Log.d(TAG, "onStop: Activity: " + System.identityHashCode(this).toString())
        super.onStop()
        
        // Activity停止时取消下载监听
        downloadManager = null
    }
    
    /**
     * 检查OTA更新
     */
    private fun checkOtaUpgrade() {
        // TODO RAY 测试暂时去掉 检查是否同日内已经检查过
        // if (isSameDayOtaChecked()) {
        //     Log.d("Ray", "同日内已检查过更新，跳过")
        //     return
        // }

        val currentUser = currentUserProviderOld.currentUser.blockingGet() ?: return

        // 构建请求URL: baseUrl + /api/v1/ota/checkUpgrade?version=${versionName}&clientType=android
        val baseUrl = currentUser.baseUrl ?: return
        val credentials: String = ApiUtils.getCredentials(currentUser.username, currentUser.token)!!

        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: return

            val checkUrl = ApiUtils.getUrlForOtaUpgrade(baseUrl, "v${versionName}")
            
            Log.d("Ray", "检查OTA更新: $checkUrl")
            
            ncApi.checkOtaUpgrade(credentials, checkUrl)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(object : Observer<OtaUpgradeOverall> {
                    override fun onSubscribe(d: Disposable) {
                        // unused atm
                    }

                    override fun onNext(otaResult: OtaUpgradeOverall) {
                        // // TODO RAY 测试暂时去掉
                        // otaResult.ocs?.data?.needUpdate = true
                        // otaResult.ocs?.data?.latestVersion = "v36.3.3"
                        // otaResult.ocs?.data?.forceUpdate = true
                        // otaResult.ocs?.data?.downloadUrl = "https://bgithub.xyz/https://github.com/raychin/talk-android/releases/download/v30.4.6/Talk_clps_30.4.6_230000023_2026-05-19.apk"
                        handleOtaResult(otaResult)
                    }

                    override fun onError(e: Throwable) {
                        Log.e("Ray", "检查OTA更新失败", e)
                    }

                    override fun onComplete() {
                        // unused atm
                    }
                })
        } catch (e: Exception) {
            Log.e("Ray", "检查OTA更新异常", e)
        }
    }

    /**
     * 处理OTA检测结果
     */
    private fun handleOtaResult(result: OtaUpgradeOverall) {
        // val ocsData = result.ocs?.data ?: run {
        //     Log.d("Ray", "OTA数据为空")
        //     return
        // }
        val ocsData = result.ocs?.data
        if (ocsData == null) {
            Log.d("Ray", "OTA数据为空")
            return
        }
        
        if (!ocsData.needUpdate) {
            // needUpdate=false，没有升级，缓存当前日期，同日内不再调用
            saveOtaCheckDate()
            Log.d("Ray", "无需更新")
            return
        }

        // needUpdate=true，存在更新
        isForceUpdate = ocsData.forceUpdate
        currentDownloadUrl = ocsData.downloadUrl
        
        if (isForceUpdate) {
            // 强制更新弹框：取消按钮关闭弹框并退出应用；确定按钮开始下载（只有取消按钮）
            showForceUpdateDialog(ocsData.latestVersion ?: "")
        } else {
            // 非强制更新弹框：取消按钮关闭弹框并缓存日期；确定按钮下载（有取消和后台下载按钮）
            showNormalUpdateDialog(ocsData.latestVersion ?: "")
        }
    }

    /**
     * 显示非强制更新对话框
     */
    private fun showNormalUpdateDialog(latestVersion: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ota_update_title)
            .setMessage(getString(R.string.ota_update_message, latestVersion))
            .setPositiveButton(R.string.nc_yes) { dialog, _ ->
                dialog.dismiss()
                // 开始下载，支持后台下载
                startDownload(supportBackgroundDownload = true)
            }
            .setNegativeButton(R.string.nc_no) { dialog, _ ->
                dialog.dismiss()
                // 缓存当前日期，同日内不再提示
                saveOtaCheckDate()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 显示强制更新对话框
     */
    private fun showForceUpdateDialog(latestVersion: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ota_force_update_title)
            .setMessage(getString(R.string.ota_force_update_message, latestVersion))
            .setPositiveButton(R.string.nc_yes) { dialog, _ ->
                dialog.dismiss()
                // 开始下载，不支持后台下载（只有取消按钮）
                startDownload(supportBackgroundDownload = false)
            }
            .setNegativeButton(R.string.nc_no) { dialog, _ ->
                dialog.dismiss()
                // 退出应用
                finishAffinity()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 开始下载APK
     * @param supportBackgroundDownload 是否支持后台下载
     */
    private fun startDownload(supportBackgroundDownload: Boolean) {
        val url = currentDownloadUrl ?: return
        
        // 获取文件名（从URL中提取或使用默认名称）
        val fileName = extractFileName(url) ?: "update_${System.currentTimeMillis()}.apk"
        
        // 显示下载进度对话框
        showProgressDialog(supportBackgroundDownload)
        
        // 使用okdownload库进行下载
        downloadManager = AppDownloadManager.getInstance(this)
        downloadManager?.startDownload(url, fileName, this)
    }

    /**
     * 显示下载进度对话框
     */
    private fun showProgressDialog(supportBackgroundDownload: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_download_progress, null)
        downloadProgressBar = dialogView.findViewById(R.id.progress_bar)
        downloadProgressText = dialogView.findViewById(R.id.progress_text)
        downloadSpeedText = dialogView.findViewById(R.id.speed_text)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.ota_downloading_title)
            .setView(dialogView)
            .setCancelable(false)

        if (supportBackgroundDownload) {
            builder.setPositiveButton(R.string.ota_download_background) { dialog, _ ->
                dialog.dismiss()
                progressDialog = null
                clearDownloadViewRefs()
                // 后台下载：下载任务继续运行
                downloadManager = null
            }
            builder.setNegativeButton(R.string.ota_download_cancel) { dialog, _ ->
                dialog.dismiss()
                progressDialog = null
                clearDownloadViewRefs()
                // 取消下载
                downloadManager?.cancelDownload()
                downloadManager = null

                if (isForceUpdate) {
                    // 强制更新时取消则退出应用
                    finishAffinity()
                } else {
                    // 非强制更新时缓存当前日期
                    saveOtaCheckDate()
                }
            }
        } else {
            // 只有取消按钮（强制更新场景）
            builder.setNegativeButton(R.string.ota_download_cancel) { dialog, _ ->
                dialog.dismiss()
                progressDialog = null
                clearDownloadViewRefs()
                // 取消下载
                downloadManager?.cancelDownload()
                downloadManager = null

                // 强制更新取消后退出应用
                finishAffinity()
            }
        }

        progressDialog = builder.show()
        // 初始化显示 0%
        downloadProgressBar?.progress = 0
        downloadProgressText?.text = "0%"
        downloadSpeedText?.text = "0 B/s"
    }

    private fun clearDownloadViewRefs() {
        downloadProgressBar = null
        downloadProgressText = null
        downloadSpeedText = null
    }

    override fun onDownloadProgressChanged(progress: Int, speed: Long, currentOffset: Long, totalLength: Long) {
        runOnUiThread {
            if (progressDialog != null && !progressDialog!!.isShowing) return@runOnUiThread
            downloadProgressBar?.progress = progress
            downloadProgressText?.text = "${progress}%"
            val speedStr = formatSize(speed)
            downloadSpeedText?.text = "${speedStr}/s"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
        }
    }

    /**
     * 从URL中提取文件名
     */
    private fun extractFileName(url: String): String? {
        try {
            val pathSegments = java.net.URI(url).path.split("/")
            return pathSegments.lastOrNull()?.takeIf { it.isNotEmpty() && it.contains(".") }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 检查是否同日内已检查过OTA更新
     */
    private fun isSameDayOtaChecked(): Boolean {
        val prefs = getSharedPreferences("ota_preferences", Context.MODE_PRIVATE)
        val lastCheckDate = prefs.getString(PREF_KEY_OTA_CHECK_DATE, null) ?: return false

        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return currentDate == lastCheckDate
    }

    /**
     * 保存OTA检查日期
     */
    private fun saveOtaCheckDate() {
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        getSharedPreferences("ota_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_OTA_CHECK_DATE, currentDate)
            .apply()
    }

    private fun openConversationList() {
        val intent = Intent(this, ConversationsListActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtras(Bundle())
        startActivity(intent)
    }

    private fun handleActionFromContact(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val cursor = contentResolver.query(intent.data!!, null, null, null, null)

            var userId = ""
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    // userId @ server
                    userId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1))
                }

                cursor.close()
            }

            when (intent.type) {
                "vnd.android.cursor.item/vnd.com.nextcloud.talk2.chat" -> {
                    val user = userId.substringBeforeLast("@")
                    val baseUrl = userId.substringAfterLast("@")

                    if (currentUserProviderOld.currentUser.blockingGet()?.baseUrl!!.endsWith(baseUrl) == true) {
                        startConversation(user)
                    } else {
                        Snackbar.make(
                            binding.root,
                            R.string.nc_phone_book_integration_account_not_found,
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun startConversation(userId: String) {
        val roomType = "1"

        val currentUser = currentUserProviderOld.currentUser.blockingGet()

        val apiVersion = ApiUtils.getConversationApiVersion(currentUser, intArrayOf(ApiUtils.API_V4, 1))
        val credentials = ApiUtils.getCredentials(currentUser?.username, currentUser?.token)
        val retrofitBucket = ApiUtils.getRetrofitBucketForCreateRoom(
            version = apiVersion,
            baseUrl = currentUser?.baseUrl!!,
            roomType = roomType,
            invite = userId
        )

        ncApi.createRoom(
            credentials,
            retrofitBucket.url,
            retrofitBucket.queryMap
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : Observer<RoomOverall> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onNext(roomOverall: RoomOverall) {
                    val bundle = Bundle()
                    bundle.putString(KEY_ROOM_TOKEN, roomOverall.ocs!!.data!!.token)

                    val chatIntent = Intent(context, ChatActivity::class.java)
                    chatIntent.putExtras(bundle)
                    startActivity(chatIntent)
                }

                override fun onError(e: Throwable) {
                    // unused atm
                }

                override fun onComplete() {
                    // unused atm
                }
            })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent Activity: " + System.identityHashCode(this).toString())
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        // // TODO RAY 测试暂时去掉
        // return

        handleActionFromContact(intent)

        val internalUserId = intent.extras?.getLong(BundleKeys.KEY_INTERNAL_USER_ID)

        var user: User? = null
        if (internalUserId != null) {
            user = userManager.getUserWithId(internalUserId).blockingGet()
        }

        if (user != null && userManager.setUserAsActive(user).blockingGet()) {
            if (intent.hasExtra(BundleKeys.KEY_REMOTE_TALK_SHARE)) {
                if (intent.getBooleanExtra(BundleKeys.KEY_REMOTE_TALK_SHARE, false)) {
                    val invitationsIntent = Intent(this, InvitationsActivity::class.java)
                    startActivity(invitationsIntent)
                }
            } else {
                val chatIntent = Intent(context, ChatActivity::class.java)
                chatIntent.putExtras(intent.extras!!)
                startActivity(chatIntent)
            }
        } else {
            userManager.users.subscribe(object : SingleObserver<List<User>> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onSuccess(users: List<User>) {
                    if (users.isNotEmpty()) {
                        ClosedInterfaceImpl().setUpPushTokenRegistration()
                        runOnUiThread {
                            openConversationList()
                        }
                    } else {
                        runOnUiThread {
                            launchServerSelection()
                        }
                    }
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "Error loading existing users", e)
                    Snackbar.make(
                        binding.root,
                        context.resources.getString(R.string.nc_common_error_sorry),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            })
        }
    }
}
