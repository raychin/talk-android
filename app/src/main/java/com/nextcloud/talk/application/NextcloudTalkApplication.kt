/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Andy Scherzinger <info@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2022 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2022 Tim Krüger <t@timkrueger.me>
 * SPDX-FileCopyrightText: 2017 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.application

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.P
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.multidex.MultiDex
import androidx.multidex.MultiDexApplication
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import autodagger.AutoComponent
import autodagger.AutoInjector
import cn.jpush.android.api.JPushInterface
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.memory.MemoryCache
import coil.util.DebugLogger
import com.nextcloud.talk.BuildConfig
import com.nextcloud.talk.account.AccountVerificationActivity
import com.nextcloud.talk.account.BrowserLoginActivity
import com.nextcloud.talk.dagger.modules.BusModule
import com.nextcloud.talk.dagger.modules.ContextModule
import com.nextcloud.talk.dagger.modules.DaosModule
import com.nextcloud.talk.dagger.modules.DatabaseModule
import com.nextcloud.talk.dagger.modules.ManagerModule
import com.nextcloud.talk.dagger.modules.RepositoryModule
import com.nextcloud.talk.dagger.modules.RestModule
import com.nextcloud.talk.dagger.modules.UtilsModule
import com.nextcloud.talk.dagger.modules.ViewModelModule
import com.nextcloud.talk.data.network.NetworkMonitorImpl
import com.nextcloud.talk.filebrowser.webdav.DavUtils
import com.nextcloud.talk.jobs.AccountRemovalWorker
import com.nextcloud.talk.jobs.CapabilitiesWorker
import com.nextcloud.talk.jobs.SignalingSettingsWorker
import com.nextcloud.talk.jobs.WebsocketConnectionsWorker
import com.nextcloud.talk.jobs.clps.TalkBackgroundWorker
import com.nextcloud.talk.ui.theme.ThemeModule
import com.nextcloud.talk.utils.ClosedInterfaceImpl
import com.nextcloud.talk.utils.DeviceUtils
import com.nextcloud.talk.utils.NotificationUtils
import com.nextcloud.talk.utils.database.arbitrarystorage.ArbitraryStorageModule
import com.nextcloud.talk.utils.database.user.UserModule
import com.nextcloud.talk.utils.preferences.AppPreferences
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.google.GoogleEmojiProvider
import de.cotech.hw.SecurityKeyManager
import de.cotech.hw.SecurityKeyManagerConfig
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import org.webrtc.PeerConnectionFactory
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@AutoComponent(
    modules = [
        BusModule::class,
        ContextModule::class,
        DatabaseModule::class,
        RestModule::class,
        UserModule::class,
        ArbitraryStorageModule::class,
        ViewModelModule::class,
        RepositoryModule::class,
        UtilsModule::class,
        ThemeModule::class,
        ManagerModule::class,
        DaosModule::class
    ]
)
@Singleton
@AutoInjector(NextcloudTalkApplication::class)
class NextcloudTalkApplication :
    MultiDexApplication(),
    LifecycleObserver,
    Configuration.Provider {
    //region Fields (components)
    lateinit var componentApplication: NextcloudTalkApplicationComponent
        private set
    //endregion

    //region Getters

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var okHttpClient: OkHttpClient
    //endregion

    //region private methods
    private fun initializeWebRtc() {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                    .createInitializationOptions()
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, e)
        }
        try {
            val builder = PeerConnectionFactory.InitializationOptions.builder(this)
                .setFieldTrials("WebRTC-Audio-Red-For-Opus/Enabled/")

            if (isHuaweiDevice()) {
                builder.setFieldTrials(
                    "WebRTC-Audio-Red-For-Opus/Enabled/" +
                        "WebRTC-Audio-SpeculativeUpscale/Disabled/"
                )
                Log.d("Ray", "Applied Huawei-specific WebRTC optimizations")
            }

            PeerConnectionFactory.initialize(builder.createInitializationOptions())
            Log.d("Ray", "WebRTC initialized successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("Ray", "WebRTC initialization failed: UnsatisfiedLinkError", e)
        } catch (e: Exception) {
            Log.e("Ray", "WebRTC initialization failed", e)
        }
    }
    private fun isHuaweiDevice(): Boolean {
        return Build.MANUFACTURER.lowercase().contains("huawei") ||
            Build.MANUFACTURER.lowercase().contains("honor") ||
            Build.BRAND.lowercase().contains("huawei") ||
            Build.BRAND.lowercase().contains("honor")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG) // 可选：设置日志级别
            .build()

    //region Overridden methods
    override fun onCreate() {
        Log.d(TAG, "onCreate")
        sharedApplication = this

        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            Log.e("UncaughtException", "Thread: ${thread.name}, Error: ${exception.message}")
            // TODO RAY 保存或上报异常详情
        }

        val securityKeyManager = SecurityKeyManager.getInstance()
        val securityKeyConfigBuilder = SecurityKeyManagerConfig.Builder()
            .setEnableDebugLogging(BuildConfig.DEBUG)

        try {
            val packageManager = packageManager
            val activities = packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES).activities
            activities?.forEach { activityInfo ->
                try {
                    val activityClass = Class.forName(activityInfo.name)
                    if (activityClass != AccountVerificationActivity::class.java &&
                        activityClass != BrowserLoginActivity::class.java
                    ) {
                        @Suppress("UNCHECKED_CAST")
                        securityKeyConfigBuilder.addExcludedActivityClass(activityClass as Class<out Activity>)
                    }
                } catch (exception: ClassNotFoundException) {
                    Log.e(TAG, "Couldn't disable activity for security key listener", exception)
                }
            }
        } catch (exception: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Couldn't disable activities for security key listener", exception)
        }

        securityKeyManager.init(this, securityKeyConfigBuilder.build())
        initializeWebRtc()
        buildComponent()
        DavUtils.registerCustomFactories()

        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        componentApplication.inject(this)

        Coil.setImageLoader(buildDefaultImageLoader())
        setAppTheme(appPreferences.theme)
        super.onCreate()

        ClosedInterfaceImpl().providerInstallerInstallIfNeededAsync()
        DeviceUtils.ignoreSpecialBatteryFeatures()

        initPush()

        initWorkers()

        val config = BundledEmojiCompatConfig(this)
        config.setReplaceAll(true)
        val emojiCompat = EmojiCompat.init(config)

        EmojiManager.install(GoogleEmojiProvider())

        NotificationUtils.registerNotificationChannels(applicationContext, appPreferences)
    }

    private fun initPush() {
        JPushInterface.setDebugMode(BuildConfig.DEBUG)
        JPushInterface.init(this)
        // 主进程重启后立即检查推送状态
        JPushInterface.getPushStatus(this)

        // 监听应用前后台切换
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // 应用回到前台时检查推送状态
                JPushInterface.getPushStatus(applicationContext)
            }
        })

        val networkMonitor = NetworkMonitorImpl(applicationContext)
        // 监听网络从离线变为在线
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            var wasOnline = false
            networkMonitor.isOnline.collect { isOnline ->
                if (isOnline && !wasOnline) {
                    // 网络从离线恢复为在线，检查推送状态
                    JPushInterface.getPushStatus(applicationContext)
                }
                wasOnline = isOnline
            }
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)       // 有网络才执行
            .setRequiresDeviceIdle(false)                          // 不需要设备空闲
            .setRequiresBatteryNotLow(true)                        // 电量不低时才执行
            .build()

        val periodicPushCheckWork = PeriodicWorkRequest.Builder(
            TalkBackgroundWorker::class.java,
            // 改为15分钟
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(         // 失败后指数退避
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "PushStatusCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,  // 已有则保留，不重复创建
            periodicPushCheckWork
        )
    }

    private fun initWorkers() {
        val accountRemovalWork = OneTimeWorkRequest.Builder(AccountRemovalWorker::class.java).build()
        val capabilitiesUpdateWork = OneTimeWorkRequest.Builder(CapabilitiesWorker::class.java).build()
        val signalingSettingsWork = OneTimeWorkRequest.Builder(SignalingSettingsWorker::class.java).build()
        val websocketConnectionsWorker = OneTimeWorkRequest.Builder(WebsocketConnectionsWorker::class.java).build()

        WorkManager.getInstance(applicationContext)
            .beginWith(accountRemovalWork)
            .then(capabilitiesUpdateWork)
            .then(signalingSettingsWork)
            .then(websocketConnectionsWorker)
            .enqueue()

        val periodicCapabilitiesUpdateWork = PeriodicWorkRequest.Builder(
            CapabilitiesWorker::class.java,
            HALF_DAY,
            TimeUnit.HOURS
        ).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "DailyCapabilitiesUpdateWork",
            ExistingPeriodicWorkPolicy.REPLACE,
            periodicCapabilitiesUpdateWork
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        sharedApplication = null
    }
    //endregion

    //region Protected methods
    protected fun buildComponent() {
        componentApplication = DaggerNextcloudTalkApplicationComponent.builder()
            .busModule(BusModule())
            .contextModule(ContextModule(applicationContext))
            .databaseModule(DatabaseModule())
            .restModule(RestModule(applicationContext))
            .arbitraryStorageModule(ArbitraryStorageModule())
            .build()
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    private fun buildDefaultImageLoader(): ImageLoader {
        val imageLoaderBuilder = ImageLoader.Builder(applicationContext)
            .memoryCache {
                // Use 50% of the application's available memory.
                MemoryCache.Builder(applicationContext).maxSizePercent(FIFTY_PERCENT).build()
            }
            .crossfade(true) // Show a short crossfade when loading images from network or disk into an ImageView.
            .components {
                if (SDK_INT >= P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
            }

        if (BuildConfig.DEBUG) {
            imageLoaderBuilder.logger(DebugLogger())
        }

        imageLoaderBuilder.okHttpClient(okHttpClient)

        return imageLoaderBuilder.build()
    }

    companion object {
        private val TAG = NextcloudTalkApplication::class.java.simpleName
        const val FIFTY_PERCENT = 0.5
        const val HALF_DAY: Long = 12
        const val CIPHER_V4_MIGRATION: Int = 7
        //region Singleton
        //endregion

        var sharedApplication: NextcloudTalkApplication? = null
            protected set
        //endregion

        //region Setters
        fun setAppTheme(theme: String) {
            when (theme) {
                "night_no" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "night_yes" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                "battery_saver" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
                else ->
                    // will be "follow_system" only for now
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        const val TALK_WORK_ID = "talk_work_id_by_ray"
        // 原来45S -> 60S
        const val TALK_WORK_DELAY: Long = 60
    }
    //endregion
}
