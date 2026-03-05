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

import android.content.Context
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.P
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.lifecycle.LifecycleObserver
import androidx.multidex.MultiDex
import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
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
import com.nextcloud.talk.dagger.modules.BusModule
import com.nextcloud.talk.dagger.modules.ContextModule
import com.nextcloud.talk.dagger.modules.DaosModule
import com.nextcloud.talk.dagger.modules.DatabaseModule
import com.nextcloud.talk.dagger.modules.ManagerModule
import com.nextcloud.talk.dagger.modules.RepositoryModule
import com.nextcloud.talk.dagger.modules.RestModule
import com.nextcloud.talk.dagger.modules.UtilsModule
import com.nextcloud.talk.dagger.modules.ViewModelModule
import com.nextcloud.talk.filebrowser.webdav.DavUtils
import com.nextcloud.talk.jobs.AccountRemovalWorker
import com.nextcloud.talk.jobs.CapabilitiesWorker
import com.nextcloud.talk.jobs.SignalingSettingsWorker
import com.nextcloud.talk.jobs.clps.TalkBackgroundWorker
import com.nextcloud.talk.jobs.WebsocketConnectionsWorker
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
        val securityKeyConfig = SecurityKeyManagerConfig.Builder()
            .setEnableDebugLogging(BuildConfig.DEBUG)
            .build()
        securityKeyManager.init(this, securityKeyConfig)

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

        // val talkBackgroundWorker = OneTimeWorkRequest.Builder(TalkBackgroundWorker::class.java).build()
        // WorkManager.getInstance(applicationContext).enqueue(talkBackgroundWorker)
        val delayedWorker = OneTimeWorkRequest.Builder(TalkBackgroundWorker::class.java)
            .setInitialDelay(TALK_WORK_DELAY, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            TALK_WORK_ID,
            // 如果已存在则不再执行
            ExistingWorkPolicy.REPLACE,
            delayedWorker
        )
        // val constraints = Constraints.Builder()
        //     .setRequiredNetworkType(NetworkType.CONNECTED)
        //     .setRequiresCharging(false) // 根据需要调整
        //     .build()
        // // 周期时间太短，无法重复执行，一般15分钟
        // val talkBackgroundWorker = PeriodicWorkRequest.Builder(TalkBackgroundWorker::class.java, 45, TimeUnit.SECONDS)
        //     .setConstraints(constraints)
        //     .build()
        // // WorkManager.getInstance(applicationContext).getWorkInfoById(talkBackgroundWorker.id)
        // // WorkManager.getInstance(applicationContext)
        // //     .getWorkInfoByIdLiveData(talkBackgroundWorker.id)
        // //     .observe(this, Observer {
        // //         workInfo -> Log.d("WorkManager", "WorkInfo: $workInfo")
        // //     })
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
