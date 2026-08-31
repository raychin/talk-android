# 屏幕共享接收功能 Compose 独立实现计划

> 目标：将通话中接收屏幕共享的全屏展示功能，从现有的 `CallActivity` 耦合实现剥离为**独立的 Compose 功能模块**，减少与旧 View System 的耦合，为后续全面 Compose 化做准备。

---

## 1. 现状分析

### 1.1 当前架构

```
CallActivity (View System + Compose 混合)
 ├── XML: call_activity.xml
 │    ├── screenShareFullscreenView (ComposeView, 全屏覆盖层)
 │    └── composeParticipantGrid (ComposeView, 参与者网格)
 │
 ├── screenShareFullscreenView.setContent { ... }
 │    └── ScreenShareComponent (Compose, 但通过绑定引用)
 │         └── WebRTCScreenShareComponent (Compose + AndroidView)
 │
 └── 耦合点:
      - 直接操作 XML View (selfVideoViewWrapper, requestedOrientation)
      - 通过 callViewModel.activeScreenShareSession 直接暴露
      - 屏幕共享关闭回调直接在 CallActivity 中写恢复逻辑
```

### 1.2 现有代码位置

| 文件 | 作用 | 状态 |
|------|------|------|
| `call/components/screenshare/ScreenShareComponent.kt` | 屏幕共享全屏 UI（顶部控制栏 + 旋转） | Compose, 可用 |
| `call/components/screenshare/WebRtcScreenShareComponent.kt` | WebRTC 视频渲染 + 手势缩放 | Compose, 可用 |
| `activities/CallViewModel.kt` | 全局 Call ViewModel (含 activeScreenShareSession) | 已存在 |
| `activities/ParticipantHandler.kt` | 参与者状态处理 (含 screenMediaStream) | 已存在 |
| `activities/ParticipantUiState.kt` | 参与者 UI 状态 data class | 已存在 |

### 1.3 耦合问题

1. **`CallActivity` 中通过 `binding!!.screenShareFullscreenView.setContent` 直接设置 Compose 内容**，回调函数直接操作 Activity 属性（`requestedOrientation`、`savedOrientationBeforeScreenShare`、`initViews()`）
2. **屏幕共享的逻辑判断（显示/隐藏）混在 `CallActivity.onCreate` 中**，与参与者网格的可见性逻辑交叉
3. **没有独立的 ViewModel**，完全依赖 `CallViewModel.activeScreenShareSession` 共享状态
4. **关闭处理**依赖 `CallActivity.initViews()` 恢复 View System 控件

---

## 2. 设计方案

### 2.1 新包结构（独立 Feature 包）

```
app/src/main/java/com/nextcloud/talk/screenshare/receive/
├── ScreenShareReceiveViewModel.kt       # 独立的 ViewModel
├── ScreenShareReceiveScreen.kt          # 顶层 Composable Screen
├── ScreenShareReceiveActivity.kt        # (可选) 独立 Activity / 或 Compose 容器
└── components/
    ├── ScreenShareVideoView.kt          # 视频渲染组件 (复用 WebRtcScreenShareComponent 逻辑)
    └── ScreenShareTopBar.kt             # 顶部控制栏
```

### 2.2 架构分层

```
┌─────────────────────────────────────────────────┐
│  ScreenShareReceiveActivity (可选独立)           │
│    或 CallActivity 中 Compose 容器区域            │
├─────────────────────────────────────────────────┤
│  ScreenShareReceiveScreen (Composable)           │  ← UI 层
│   ├─ ScreenShareVideoView (视频 + 手势)          │
│   └─ ScreenShareTopBar (昵称 + 关闭 + 旋转)     │
├─────────────────────────────────────────────────┤
│  ScreenShareReceiveViewModel                     │  ← ViewModel 层
│   ├─ 观察 CallViewModel 的 activeScreenShare    │
│   ├─ 管理方向锁定/恢复 (不依赖 Activity 属性)    │
│   └─ 暴露 UiState: ScreenShareReceiveUiState    │
├─────────────────────────────────────────────────┤
│  CallActivity (集成点) / CallViewModel           │  ← 现有基础设施
│   提供: CallViewModel.activeScreenShareSession   │
└─────────────────────────────────────────────────┘
```

### 2.3 解耦策略

| 耦合点 | 当前 | 改造后 |
|--------|------|--------|
| 方向锁定 | `CallActivity.requestedOrientation` | `ScreenShareReceiveViewModel` 通过 `Context(Activity)` 发送 Intent 或通过回调通知 Activity |
| `selfVideoViewWrapper` 隐藏 | `binding!!.selfVideoViewWrapper.visibility` | 通过回调 `onScreenShareStateChanged(active: Boolean)` 通知父容器 |
| 关闭恢复方向 | `CallActivity.savedOrientationBeforeScreenShare` | ViewModel 保存状态，关闭时回调父容器 |
| `activeScreenShareSession` 数据源 | `CallViewModel` 直接暴露 | ViewModel 层关联但不绕开 |

### 2.4 关键接口设计

#### ViewModel

```kotlin
// ScreenShareReceiveViewModel.kt
class ScreenShareReceiveViewModel @Inject constructor(
    private val callViewModel: CallViewModel  // 注意：通过 CallViewModel 获取数据
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScreenShareReceiveUiState>(ScreenShareReceiveUiState.Hidden)
    val uiState: StateFlow<ScreenShareReceiveUiState> = _uiState.asStateFlow()

    // 关闭屏幕共享
    fun closeScreenShare()

    // 切换方向
    fun toggleOrientation()
}

sealed interface ScreenShareReceiveUiState {
    data object Hidden : ScreenShareReceiveUiState
    data class Active(
        val participant: ParticipantUiState,
        val isLandscape: Boolean,
        val savedOrientation: Int
    ) : ScreenShareReceiveUiState
}
```

#### Screen Composable

```kotlin
@Composable
fun ScreenShareReceiveScreen(
    viewModel: ScreenShareReceiveViewModel,
    eglBase: EglBase?,
    onVisibilityChanged: (visible: Boolean) -> Unit,  // 用于告知父容器显示状态
    modifier: Modifier = Modifier
)
```

### 2.5 与 CallActivity 的集成点

`CallActivity` 中需要保留的最少耦合代码：

```kotlin
// CallActivity 中最小集成
val screenShareViewModel = ViewModelProvider(this, viewModelFactory)[ScreenShareReceiveViewModel::class.java]

binding!!.screenShareFullscreenView.setContent {
    MaterialTheme {
        ScreenShareReceiveScreen(
            viewModel = screenShareViewModel,
            eglBase = rootEglBase!!,
            onVisibilityChanged = { visible ->
                // 只有这一处耦合到 View System
                binding!!.selfVideoViewWrapper.visibility = if (visible) View.GONE else View.VISIBLE
            }
        )
    }
}
```

---

## 3. 实施步骤

### Phase 1: 创建核心 ViewModel 和 UiState

**文件**: `app/src/main/java/com/nextcloud/talk/screenshare/receive/ScreenShareReceiveViewModel.kt`

- 创建独立的 `ScreenShareReceiveViewModel`（`@Inject constructor`）
- 定义 `ScreenShareReceiveUiState sealed interface`
- ViewModel 观察 `CallViewModel.activeScreenShareSession` 并映射为自己的 UiState
- 提供 `closeScreenShare()` 和 `toggleOrientation()` 方法
- 方向状态由 ViewModel 持有（不再依赖 Activity）

**依赖注入**:
- 在 `ViewModelModule.kt` 注册 `ScreenShareReceiveViewModel`
- `ScreenShareReceiveViewModel` 通过构造函数注入 `CallViewModel`（注意循环依赖问题，如需要则通过共享单例或 `AndroidViewModel` + `Application` 获取）

### Phase 2: 创建 Compose UI 组件

**文件**: `app/src/main/java/com/nextcloud/talk/screenshare/receive/components/ScreenShareVideoView.kt`
- 将从 `WebRtcScreenShareComponent.kt` 迁移过来（保留缩放/平移手势、SurfaceViewRenderer 管理）
- 保持与 `WebRtcScreenShareComponent` 相同的功能，但包路径更新

**文件**: `app/src/main/java/com/nextcloud/talk/screenshare/receive/components/ScreenShareTopBar.kt`
- 将从 `ScreenShareComponent.kt` 的 `ScreenShareControls` 迁移过来
- 包含：参与者昵称、方向切换按钮、关闭按钮
- 5秒自动隐藏动画 + 点击显示

### Phase 3: 创建顶层 Screen Composable

**文件**: `app/src/main/java/com/nextcloud/talk/screenshare/receive/ScreenShareReceiveScreen.kt`
- 组合 `ScreenShareVideoView` + `ScreenShareTopBar`
- 监听 ViewModel 的 UiState 并渲染
- 暴露 `onVisibilityChanged` 回调给父容器
- 隐藏时渲染空状态（或透明）

### Phase 4: 改造 CallActivity 集成

- 移除 `CallActivity` 中 `screenShareFullscreenView.setContent{...}` 的直接 Compose 逻辑
- 替换为引用新 `ScreenShareReceiveScreen`
- 移除 `CallActivity` 中的 `savedOrientationBeforeScreenShare` 和方向恢复逻辑
- `onCloseIconClick` 通过 ViewModel 的 `closeScreenShare()` 触发

### Phase 5: 清理旧代码

- 保留 `call/components/screenshare/` 作为向后兼容，但不主动删除
- `CallActivity` 的 `initViews()` 方法中不再需要屏幕共享恢复逻辑
- 移除 `savedOrientationBeforeScreenShare` 字段

---

## 4. 文件清单

### 新建文件

| # | 文件路径 | 说明 |
|---|---------|------|
| 1 | `screenshare/receive/ScreenShareReceiveViewModel.kt` | 独立 ViewModel |
| 2 | `screenshare/receive/ScreenShareReceiveScreen.kt` | 顶层 Composable Screen |
| 3 | `screenshare/receive/components/ScreenShareVideoView.kt` | 视频渲染组件 |
| 4 | `screenshare/receive/components/ScreenShareTopBar.kt` | 顶部控制栏 |

### 修改文件

| # | 文件路径 | 修改内容 |
|---|---------|---------|
| 1 | `dagger/modules/ViewModelModule.kt` | 注册 `ScreenShareReceiveViewModel` |
| 2 | `activities/CallActivity.kt` | 用新 Screen 替换内联 Compose |

### 可删除（清理阶段）

| # | 文件路径 | 说明 |
|---|---------|------|
| 1 | `call/components/screenshare/ScreenShareComponent.kt` | 被新组件替代（可暂留以向后兼容） |
| 2 | `CallActivity.savedOrientationBeforeScreenShare` | 逻辑移入 ViewModel |

---

## 5. 与 AGENTS.md 规范的一致性

| 规范要求 | 符合情况 | 说明 |
|---------|---------|------|
| 优先 Compose + MVVM | ✅ | 全部使用 Compose + ViewModel + StateFlow |
| 禁止新增传统 Activity | ✅ | Screen 作为 Compose 容器，通过现有 ComposeView 嵌入 |
| 禁止 ViewModel 持有 Context/View | ✅ | `ScreenShareReceiveViewModel` 通过回调通知父容器 |
| 禁止直接 `new ViewModel()` | ✅ | 通过 Dagger ViewModelFactory 创建 |
| sealed UiState | ✅ | `ScreenShareReceiveUiState` 使用 sealed interface |
| ViewModel 注册到 `ViewModelModule.kt` | ✅ | Phase 1 包含注册 |
| SPDX 头注释 | ✅ | 所有新文件包含 |
| 代码风格 (4空格/120列) | ✅ | 遵循 `.editorconfig` |
| 按 feature 分包 | ✅ | `screenshare/receive/` 独立包 |

---

## 6. 注意事项

1. **循环依赖**: `ScreenShareReceiveViewModel` 如果直接注入 `CallViewModel` 可能导致 Dagger 循环依赖。解决方案：
   - 方式 A（推荐）：`CallViewModel` 已有 `activeScreenShareSession`，新 ViewModel 通过观察 `CallViewModel` 的 flow 来获取，利用 Dagger 单例作用域
   - 方式 B：通过 `SharedFlow` / `EventBus` 解耦，新 ViewModel 监听事件
   - 方式 C：使用 `@Singleton` 作用域共享

2. **生命周期**: `ScreenShareReceiveViewModel` 的生命周期应跟随 `CallActivity`。当 `CallActivity` 销毁时，清理资源（如 SurfaceViewRenderer）。

3. **方向管理**: ViewModel 不应直接持有 Activity 引用。通过回调接口 `onOrientationChanged(Int)` 或 `ActivityResultLauncher` 方式处理方向锁定。

4. **预览支持**: 每个主要 Composable 组件提供 `@Preview`（Light/Dark/RTL 三种模式）。

5. **权限**: 屏幕共享接收不需要额外权限（只是渲染远端视频流），但 `ScreenShareReceiveViewModel` 不应涉及权限逻辑。

---

## 7. 未来扩展

- **Picture-in-Picture 模式**: 独立后的模块可以更容易地支持进入画中画模式
- **独立 Activity**: 如果需要全屏独立展示，可以将 Screen 封装为独立 Activity，通过 intent 传递 participant sessionKey
- **手势优化**: 可扩展为支持双击全屏/恢复
