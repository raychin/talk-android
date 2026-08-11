# AGENTS.md

## 1. 项目概览

**Nextcloud Talk Android** 是 Nextcloud Talk 的官方 Android 客户端，提供即时通讯、音视频通话、文件共享、投票、日程等功能。

- 包名：`com.nextcloud.talk`（构建产物 `com.nextcloud.talk2`）
- 版本：30.4.10（versionCode 230000027）
- 渠道（productFlavors）：`generic`、`gplay`、`qa`、`clps`
- 授权：GPL-3.0-or-later（所有源码文件必须带 SPDX 头注释）

## 2. 技术栈与版本

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.3.10 |
| JVM | Java | 17 |
| AGP | Android Gradle Plugin | 8.13.2 |
| SDK | compileSdk / minSdk / targetSdk | 36 / 26 / 36 |
| UI | Jetpack Compose（BOM）+ Material3 + ViewBinding | 2026.03.00 / 1.4.0 |
| DI | Dagger + autodagger2 | 2.57.1 |
| 网络 | Retrofit + OkHttp + RxJava2 | 3.0.0 / 4.12.0 / 2.2.21 |
| 数据库 | Room | 2.8.0 |
| 图片 | Coil（+ Glide） | 2.7.0 |
| 序列化 | kotlinx-serialization / Gson / LoganSquare | 1.10.0 |
| 状态 | StateFlow / MutableStateFlow（MVVM） | coroutines 1.10.2 |
| 后台 | WorkManager / JPush（极光推送） | 2.10.4 / 6.1.0 |

Compose 已启用：`buildFeatures { compose = true }`、`composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }`。

## 3. 构建 / 测试 / 质量命令

> Windows 使用 `.\gradlew.bat`，Linux/macOS 使用 `./gradlew`。以下统一以 `./gradlew` 表示。

### 构建 APK（按渠道）

```bash
./gradlew assembleGplayDebug      # Google Play 渠道 Debug
./gradlew assembleClpsRelease     # CLPS 渠道 Release
./gradlew buildAllDebug           # 所有渠道 Debug
./gradlew buildAllRelease         # 所有渠道 Release
```

### 单元测试

```bash
./gradlew testGplayDebugUnit      # 指定渠道单元测试
./gradlew testDebugUnitTest       # 全部渠道单元测试
```

### 质量检查（CI 门禁 `check.dependsOn`）

```bash
./gradlew detekt                  # 静态分析（规则见 detekt.yml）
./gradlew ktlintCheck             # Kotlin 代码风格（android_studio 风格）
./gradlew spotbugsGplayDebug      # 字节码缺陷分析
./gradlew lint                    # Android Lint
```

提交代码前必须保证以上 4 项全部通过。

## 4. 架构约定（核心）

### 4.1 首选方案：Compose + MVVM（强制优先）

**所有新增功能必须优先使用 Jetpack Compose + MVVM（ViewModel + StateFlow）实现。** 只有需要嵌入尚未迁移的既有 View 体系（如 `ChatActivity` 的消息气泡、`MessageInputFragment` 输入框）时才允许沿用 View System，且**新的业务逻辑仍须放入 ViewModel**，不得直接写在 Activity/Fragment 中。

### 4.2 禁止事项

- ❌ 新增功能默认不允许新建传统 `Activity`（`onCreate` + `setContentView(R.layout...)`）页面；应使用 `setContent` + Compose。
- ❌ 禁止新增基于 `RecyclerView` + `ViewHolder` 的 UI 组件（已有消息列表迁移中，新增列表一律使用 `LazyColumn`）。
- ❌ 禁止把网络/数据库访问直接写在 Composable 或 Activity 中；必须经 ViewModel → Repository。
- ❌ 禁止在 ViewModel 中持有 Android View 或 `Context`（应使用 `AndroidViewModel` 或注入所需的最小依赖）。
- ❌ 禁止直接 `new ViewModel()`；必须通过 Dagger 的 `ViewModelFactory` 创建。

### 4.3 分层规范

```
┌─────────────────────────────────────────────┐
│ UI 层：Composable Screen / @Preview          │  ← 只做渲染 + 转发用户事件
├─────────────────────────────────────────────┤
│ ViewModel 层：StateFlow + sealed UiState     │  ← 持有 UI 状态，调用 UseCase/Repository
├─────────────────────────────────────────────┤
│ Repository 层：接口 + Impl                   │  ← 数据聚合、离线优先策略
├─────────────────────────────────────────────┤
│ 数据源层：Network (Retrofit) / Database (Room)│  ← 纯数据访问
└─────────────────────────────────────────────┘
```

- **单向数据流**：UI 只观察 `StateFlow`；用户操作 → 调 ViewModel 方法 → ViewModel 更新 `MutableStateFlow` → UI 重组。
- **状态封装**：用 `sealed interface/class UiState` 表达 `Loading / Success / Error`，禁止把网络异常直接抛到 UI。

## 5. Compose + MVVM 参考实现范式

以 `ConversationCreationActivity` + `ConversationCreationViewModel`、`LocationPickerActivity` + `LocationPickerViewModel` 为标杆。标准模板如下。

### 5.1 ViewModel（模板）

```kotlin
package com.nextcloud.talk.xxx   // 按 feature 分包

class XxxViewModel @Inject constructor(
    private val repository: XxxRepository,
    private val currentUserProvider: CurrentUserProviderOld
) : ViewModel() {

    private val _uiState = MutableStateFlow<XxxUiState>(XxxUiState.Loading)
    val uiState: StateFlow<XxxUiState> = _uiState

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = XxxUiState.Loading
            runCatching { repository.fetch(...) }
                .onSuccess { _uiState.value = XxxUiState.Success(it) }
                .onFailure { _uiState.value = XxxUiState.Error(it.message ?: "") }
        }
    }
}

sealed interface XxxUiState {
    data object Loading : XxxUiState
    data class Success(val data: ...) : XxxUiState
    data class Error(val message: String) : XxxUiState
}
```

### 5.2 Compose Screen（模板）

```kotlin
@Composable
fun XxxScreen(viewModel: XxxViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    when (val state = uiState) {
        XxxUiState.Loading -> CircularProgressIndicator()
        is XxxUiState.Success -> Column { /* 渲染 state.data */ }
        is XxxUiState.Error -> Text(state.message)
    }
}
```

### 5.3 Activity 装配（模板）

```kotlin
@AutoInjector(NextcloudTalkApplication::class)
class XxxActivity : BaseActivity() {

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)
        val viewModel = ViewModelProvider(this, viewModelFactory)[XxxViewModel::class.java]

        setContent {
            MaterialTheme(colorScheme = viewThemeUtils.getColorScheme(this)) {
                XxxScreen(viewModel)
            }
        }
    }
}
```

### 5.4 Dagger 注册（必须）

新建 ViewModel 后，必须在 `app/src/main/java/com/nextcloud/talk/dagger/modules/ViewModelModule.kt` 中注册：

```kotlin
@Binds
@IntoMap
@ViewModelKey(XxxViewModel::class)
abstract fun xxxViewModel(viewModel: XxxViewModel): ViewModel
```

### 5.5 主题与 Preview 规范

- Compose 配色统一使用 `viewThemeUtils.getColorScheme(context)` 包裹 `MaterialTheme`。
- 颜色资源用 `colorResource(R.color.xxx)`，字符串用 `stringResource(R.string.xxx)`，禁止硬编码。
- 图片加载用 Coil：`AsyncImage`（`io.coil-kt:coil-compose`）。
- 每个重要 Screen 提供 `@Preview`（Light / Dark / RTL 三个模式），通过 `ComposePreviewUtils` 获取测试用 `ViewThemeUtils`（参考 `ScheduleMessageCompose.kt` 末尾）。

## 6. DI 约定（Dagger + autodagger2）

- 依赖注入方式：`@Inject constructor` + `@AutoInjector(NextcloudTalkApplication::class)`。
- Activity / Fragment / ViewHolder / ViewModel 需要注入时，在 `onCreate`/`init` 中调用：
  `NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)`。
- 所有 ViewModel 必须在 `ViewModelModule.kt` 注册（见 5.4）。
- Repository 遵循「接口 + Impl」模式（参考 `repositories/conversations/`），实现类标 `@Inject constructor`。

## 7. 代码风格

- 缩进 4 空格、行宽 ≤ 120（`.editorconfig`），`ktlint_code_style = android_studio`。
- 文件头必须含 SPDX 版权注释：
  ```
  /* SPDX-FileCopyrightText: 2024 xxx <xxx@example.com> */
  /* SPDX-License-Identifier: GPL-3.0-or-later */
  ```
- detekt 规则见根目录 `detekt.yml`（`ComplexMethod` 阈值 10 等），新增代码需满足。
- 功能包命名：按 feature 分包，如 `chat/`、`polls/`、`location/`、`conversationcreation/`。
- 日志规范：`Log.d/e(TAG, ...)`，TAG 用 `ClassName::class.java.simpleName`；不打印敏感信息。

## 8. 测试约定

- **单元测试**（`app/src/test/java/`）：JUnit4（vintage 引擎）+ Mockito/MockitoKotlin + `kotlinx-coroutines-test` + Robolectric。
  - ViewModel 测试继承 `viewmodels/AbstractViewModelTest.kt`。
  - 运行：`./gradlew testGplayDebugUnit`。
- **UI 测试**（`app/src/androidTest/`）：Espresso + Compose `ui-test-junit4`。
- **Fake 实现**：放 `app/src/test/java/com/nextcloud/talk/test/fakes/`（参考 `FakeCallRecordingRepository.kt`）。
- 数据库迁移测试：`data/database/migrations/MigrationsTest.kt`，Room schema 存于 `app/schemas/`。

## 9. 关键参考文件索引

| 用途 | 文件 |
|------|------|
| Compose + MVVM 标杆（Activity） | `app/src/main/java/com/nextcloud/talk/conversationcreation/ConversationCreationActivity.kt` |
| Compose + MVVM 标杆（ViewModel） | `app/src/main/java/com/nextcloud/talk/conversationcreation/ConversationCreationViewModel.kt` |
| Compose + MVVM 标杆（Location） | `app/src/main/java/com/nextcloud/talk/location/LocationPickerActivity.kt` |
| ViewModel 注册中心 | `app/src/main/java/com/nextcloud/talk/dagger/modules/ViewModelModule.kt` |
| Compose 主题封装 | `app/src/main/java/com/nextcloud/talk/ui/theme/ViewThemeUtils.kt` |
| Preview 工具 | `app/src/main/java/com/nextcloud/talk/utils/preview/ComposePreviewUtils.kt` |
| Compose 弹窗示例 | `app/src/main/java/com/nextcloud/talk/chat/ScheduleMessageCompose.kt` |
| Repository 接口 + Impl | `app/src/main/java/com/nextcloud/talk/repositories/conversations/` |
| 聊天数据层（离线优先） | `app/src/main/java/com/nextcloud/talk/chat/data/network/OfflineFirstChatRepository.kt` |
| 传统 View 体系（迁移中，勿扩展） | `app/src/main/java/com/nextcloud/talk/chat/MessageInputFragment.kt`、`ChatActivity.kt` |

## 10. 项目目录结构图

```
talk-android/
├── build.gradle                  # 根构建脚本（Kotlin/AGP 版本、仓库）
├── settings.gradle               # 模块声明
├── gradle.properties             # JVM/构建参数
├── detekt.yml                    # 静态分析规则
├── .editorconfig                 # 代码风格（4空格/120列）
├── app/                          # 主模块
│   └── src/
│       ├── main/
│       │   ├── java/com/nextcloud/talk/
│       │   │   ├── activities/        # 旧 Activity（BaseActivity/MainActivity/CallActivity）
│       │   │   ├── chat/              # 聊天核心（ChatActivity/MessageInputFragment/
│       │   │   │   ├── data/          #   ├─ 数据层：ChatMessageRepository +
│       │   │   │   │   ├── model/     #   │        OfflineFirstChatRepository
│       │   │   │   │   ├── network/   #   │        RetrofitChatNetwork
│       │   │   │   │   └── io/        #   │
│       │   │   │   └── viewmodels/    #   └─ ChatViewModel/MessageInputViewModel
│       │   │   ├── conversationcreation/  # Compose+MVVM 标杆（Activity+VM+Screen）
│       │   │   ├── conversationlist/      # 会话列表
│       │   │   ├── polls/                 # 投票（VM + ui/ 下 Compose）
│       │   │   ├── location/              # 位置（VM + components/ 下 Compose）
│       │   │   ├── contextchat/           # 上下文聊天（Compose）
│       │   │   ├── diagnosis/             # 诊断（Compose）
│       │   │   ├── repositories/          # 领域 Repository（接口+Impl）
│       │   │   │   ├── conversations/
│       │   │   │   ├── callrecording/
│       │   │   │   ├── reactions/
│       │   │   │   └── unifiedsearch/
│       │   │   ├── data/                  # 数据库/存储（Room DAO/Entity/Converter）
│       │   │   ├── models/                # 领域模型 / JSON 模型
│       │   │   ├── api/                   # Retrofit 接口（NcApi/NcApiCoroutines）
│       │   │   ├── dagger/                # DI（modules/ViewModelModule.kt 等）
│       │   │   ├── ui/                    # Compose 组件 / 对话框 / ComposeChatAdapter
│       │   │   ├── components/            # 通用 Compose 组件
│       │   │   ├── utils/                 # 工具（DisplayUtils/ApiUtils/MessageUtils）
│       │   │   ├── jobs/                  # WorkManager 后台任务
│       │   │   ├── viewmodels/            # 全局/跨模块 ViewModel
│       │   │   └── ...                    # 其他 feature 包（settings/contacts/call/...）
│       │   ├── res/                       # 资源
│       │   └── AndroidManifest.xml
│       ├── test/java/                     # 单元测试（JUnit4+Mockito+Robolectric）
│       └── androidTest/java/              # 仪器测试（Espresso+Compose）
├── picture-editor/               # 图片编辑模块
├── picture-selector/             # 图片选择模块
└── multimedia/                   # 多媒体模块
```
