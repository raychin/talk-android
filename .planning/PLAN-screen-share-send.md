# 屏幕分享（发送端）功能 Compose 独立实现计划

> 目标：实现通话中**分享本机屏幕**（发送端）功能，参考 PC 端和 iOS 端的实现，采用独立的 Compose 模块设计，尽量减少与 `CallActivity` 的耦合，为后续全面 Compose 化做好准备。

---

## 1. 三端信令对比（PC / iOS / Android）

### 1.1 总体结论：信令协议完全一致

经过对 PC 端（spreed Web App）、iOS 端（NextcloudTalk）和 Android 端完整的信令代码进行逐项对比，**三端使用的信令协议完全一致**，均遵循 Nextcloud Talk 统一信令规范。差异仅体现在平台屏幕捕获 API 上。

### 1.2 详细对比表

| 信令层面 | PC 端 | iOS 端 | Android 端 |
|---------|-------|--------|-----------|
| **屏幕流类型** | `roomType: "screen"` | `kRoomTypeScreen = @"screen"` | `type: "screen"` |
| **创建 screen PC（发送端）** | `createPeer({type:'screen', sharemyscreen:true, receiveMedia:{offerToReceiveAudio:0, offerToReceiveVideo:0}})` | `NCPeerConnection` + `roomType = kRoomTypeScreen` + `isOwnScreensharePeer = YES` | 已有接收端，发送端需新增 publisher 角色 |
| **创建 screen PC（接收端）** | SimpleWebRTC 自动处理 | `NCPeerConnection` subscriber | `createPeerConnectionWrapperForSessionIdAndType(publisher=false, type="screen")` ✅ 已有 |
| **unshareScreen 发送（非 MCU）** | `peer.send('unshareScreen')` | `NCUnshareScreenMessage` → 逐个 session 发送 | 需新增 |
| **unshareScreen 发送（MCU）** | `signaling.sendRoomMessage({roomType:'screen', type:'unshareScreen'})` | `[externalSignalingController sendRoomMessageOfType:@"unshareScreen" andRoomType:kRoomTypeScreen]` | 需新增 |
| **unshareScreen 接收** | `peer.js L556` → `parent.emit('unshareScreen')` → `peer.end()` | `kNCSignalingMessageTypeUnshareScreen` → 关闭 PC → `didReceiveUnshareScreenFromPeer` | ✅ `SignalingMessageReceiver` 已解析 → `onUnshareScreen()` |
| **SDP 约束（发送端）** | `receiveMedia: {offerToReceiveAudio: 0, offerToReceiveVideo: 0}` | publisher PC 默认不添加 recvonly transceiver | ⚠️ 需设置 `offerToReceiveAudio=0, offerToReceiveVideo=0` |
| **屏幕捕获 API** | `getDisplayMedia()` / Electron `desktopCapturer` | ReplayKit → Broadcast Extension → Socket | `MediaProjectionManager.createScreenCaptureIntent()` |

### 1.3 关键参考实现（iOS 端 NCCallController.m）

```objc
// iOS 端 startScreenshare — 最清晰的发送端实现参考
- (void)startScreenshare {
    RTCVideoSource *videoSource = [peerConnectionFactory videoSource];
    RTCVideoCapturer *videoCapturer = [[RTCVideoCapturer alloc] initWithDelegate:videoSource];
    [_screensharingController startCaptureWithVideoSource:videoSource withVideoCapturer:videoCapturer];
    _localScreenTrack = [peerConnectionFactory videoTrackWithSource:videoSource trackId:kNCScreenTrackId];
    
    if (hasMCU) {
        [self createScreenPublisherPeerConnection];       // MCU: 创建 publisher PC
    } else {
        for (session in sessionsInCall) {
            [self sendScreensharingOfferToSessionId:session]; // 非MCU: 逐个创建 peer + sendOffer
        }
    }
    _screensharingActive = YES;
}

// iOS 端 stopScreenshare
- (void)stopScreenshare {
    [_screensharingController stopCapture];
    if (hasMCU) {
        [self->_screenPublisherPeerConnection close];
        [externalSignalingController sendRoomMessageOfType:@"unshareScreen" andRoomType:kRoomTypeScreen];
    } else {
        // 关闭所有 ownScreenPeer + 发送 unshareScreen 信令
        for (NCPeerConnection *peer in connectionsDict) {
            if (peer.isOwnScreensharePeer) {
                [self cleanPeerConnectionForSessionId:...];
            } else {
                NCSignalingMessage *msg = [[NCUnshareScreenMessage alloc] initWithFrom:to:sid:roomType:payload:@{}];
                [_signalingController sendSignalingMessage:msg];
            }
        }
    }
    _screensharingActive = NO;
}
```

---

## 2. 背景分析：接收端如何工作（关键参考）

### 2.1 接收端数据流

```
远端 Peer → PeerConnection(type="screen")
  → PeerConnectionWrapper
     → ParticipantHandler.screenPeerConnectionObserver
        → handleScreenStreamChange(mediaStream)
           → ParticipantUiState.screenMediaStream
           → ParticipantUiState.isScreenStreamEnabled = true
  → CallViewModel.activeScreenShareSession (点击图标后激活)
  → ScreenShareComponent (Compose 全屏展示)
     → WebRTCScreenShareComponent (SurfaceViewRenderer 渲染)
```

### 2.2 发送端需要做的反向流程（三端对比）

```
本地屏幕捕获 → VideoSource → VideoTrack → MediaStream
  → PeerConnection(type="screen", publisher 角色)
     → 通过信令发送 offer/answer/candidate
     → 停止时发送 unshareScreen
  → 需要:
     1. MediaProjection API 获取屏幕内容 (Android 特有)
     2. 创建 screen VideoSource + VideoTrack
     3. 创建独立的 screen PeerConnection（publisher 角色）
     4. 发送 unshareScreen 信令（格式与 PC/iOS 完全一致）
     5. UI 控制按钮
```

---

## 3. 方案设计

### 3.1 架构设计（简化后）

```
┌──────────────────────────────────────────────────────────────────┐
│  ScreenShareSendViewModel (独立 ViewModel)                        │
│  ├─ 持有 UiState (Idle / Sharing)                                │
│  ├─ 暴露 startScreenShare() / stopScreenShare()                  │
│  ├─ 通过回调通知 CallActivity 执行实际操作                         │
│  └─ onCleared() 时确保停止分享                                    │
├──────────────────────────────────────────────────────────────────┤
│  CallActivity (实际执行者，参考 iOS NCCallController)              │
│  ├─ MediaProjection 权限请求 (registerForActivityResult)          │
│  ├─ 创建 ScreenCapturer → VideoSource → VideoTrack               │
│  ├─ 创建本地 MediaStream + screen Publisher PeerConnection       │
│  │   (复用现有 createPeerConnectionWrapperForSessionIdAndType)    │
│  ├─ 发送 unshareScreen 信令 (MCU/非MCU)                          │
│  └─ hangup/terminateAudioVideo 时清理                            │
├──────────────────────────────────────────────────────────────────┤
│  Compose UI 组件                                                 │
│  ├─ ScreenShareSendButton (通话音量栏中的分享按钮)                │
│  └─ ScreenShareSendStatusOverlay (分享中的状态指示)               │
└──────────────────────────────────────────────────────────────────┘
```

### 3.2 与 CallActivity 的最小接口

ViewModel 通过一个简单的回调 lambda 通知 CallActivity：

```kotlin
// ViewModel 定义 (不再定义复杂接口)
class ScreenShareSendViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow<ScreenShareSendUiState>(ScreenShareSendUiState.Idle)
    val uiState: StateFlow<ScreenShareSendUiState> = _uiState.asStateFlow()

    // 回调 —— 由 CallActivity 注入
    var onStartShareRequest: ((MediaProjection) -> Unit)? = null
    var onStopShareRequest: (() -> Unit)? = null

    fun startScreenShare(mediaProjection: MediaProjection) {
        _uiState.value = ScreenShareSendUiState.Sharing
        onStartShareRequest?.invoke(mediaProjection)
    }

    fun stopScreenShare() {
        _uiState.value = ScreenShareSendUiState.Idle
        onStopShareRequest?.invoke()
    }

    override fun onCleared() {
        if (_uiState.value is ScreenShareSendUiState.Sharing) {
            stopScreenShare()
        }
    }
}

sealed interface ScreenShareSendUiState {
    data object Idle : ScreenShareSendUiState
    data object Sharing : ScreenShareSendUiState
}
```

### 3.3 UiState

```kotlin
sealed interface ScreenShareSendUiState {
    data object Idle : ScreenShareSendUiState
    data object Sharing : ScreenShareSendUiState
}
```

**说明**：原计划的 `RequestingPermission` 和 `Error` 状态由 `CallActivity` 直接管理（通过 ActivityResult 结果和 Snackbar），不必引入 ViewModel。简化后 ViewModel 只关注 Idle/Sharing 两个核心状态。

### 3.4 CallActivity 核心逻辑（参考 iOS NCCallController）

```kotlin
// CallActivity 中新增

// 1. 权限请求
private val screenSharePermissionLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
        val mediaProjection = getMediaProjectionManager().getMediaProjection(
            result.resultCode, result.data!!
        )
        startScreenSharing(mediaProjection)
    }
}

// 2. 启动屏幕分享（核心逻辑，参考 iOS startScreenshare）
private fun startScreenSharing(mediaProjection: MediaProjection) {
    val pcFactory = peerConnectionFactory!!
    val eglContext = rootEglBase!!.eglBaseContext

    // 创建 VideoSource + VideoCapturer
    val videoSource = pcFactory.createVideoSource(/* isScreencast = */ true)
    screenCapturer = ScreenCapturer(mediaProjection, videoSource, eglContext)
    screenCapturer!!.startCapture(SCREEN_CAPTURE_WIDTH, SCREEN_CAPTURE_HEIGHT, SCREEN_CAPTURE_FPS)

    // 创建 VideoTrack
    val screenVideoTrack = pcFactory.createVideoTrack("NCs0", videoSource)

    // 创建本地 MediaStream
    val localStream = pcFactory.createLocalMediaStream("NCSS")
    localStream.addTrack(screenVideoTrack)

    // 创建 screen Publisher PeerConnection（注意 SDP 约束：只发送不接收）
    if (hasMCU) {
        createScreenPublisherPeerConnection(localStream)
    } else {
        for (sessionId in callParticipantSessionIds) {
            val peer = createPeerConnectionWrapperForSessionIdAndType(
                publisher = true,
                sessionId = sessionId,
                type = "screen"
            )
        }
    }

    screenShareSendViewModel.startScreenShare(mediaProjection)
}

// 3. 停止屏幕分享（参考 iOS stopScreenshare）
private fun stopScreenSharing() {
    screenCapturer?.stopCapture()
    screenCapturer?.dispose()
    screenCapturer = null

    // 发送 unshareScreen 信令
    if (hasMCU) {
        // MCU: 通过 signaling 发送房间消息
        signalingMessageSender?.send(NCSignalingMessage().apply {
            type = "unshareScreen"
            roomType = "screen"
        })
    } else {
        // 非 MCU: 逐个通知建立了 screen peer 的参与者
        for ((sessionId, _) in peerConnectionWrapperList) {
            val msg = NCSignalingMessage().apply {
                to = sessionId
                type = "unshareScreen"
                roomType = "screen"
            }
            signalingMessageSender?.send(msg)
        }
    }

    // 销毁所有 screen publisher peer
    for (sessionId in callParticipantSessionIds) {
        endPeerConnection(sessionId, "screen")
    }

    screenShareSendViewModel.stopScreenShare()
}
```

### 3.5 ScreenCapturer 封装

```kotlin
/**
 * 封装 Android MediaProjection API → WebRTC VideoSource
 * 参考 iOS: NCScreensharingController (ScreenCapturer + ScreenCaptureController)
 *
 * 核心思路：
 *   MediaProjection.createVirtualDisplay()
 *   → SurfaceTexture 接收帧
 *   → 回调到 WebRTC VideoSource.capturer(...)
 */
class ScreenCapturer(
    private val mediaProjection: MediaProjection,
    private val videoSource: VideoSource,
    private val eglContext: EglBase.Context
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var isCapturing = false

    fun startCapture(width: Int = 1280, height: Int = 720, fps: Int = 15) {
        if (isCapturing) return
        isCapturing = true

        captureThread = HandlerThread("ScreenCaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        val surfaceTexture = SurfaceTexture(/* textureId */).apply {
            setDefaultBufferSize(width, height)
        }

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenShare",
            width, height, Resources.getSystem().displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            Surface(surfaceTexture), null, null
        )

        // 启动 SurfaceTexture 帧循环 → 推送到 VideoSource
        surfaceTexture.setOnFrameAvailableListener({
            captureHandler?.post {
                surfaceTexture.updateTexImage()
                val transformMatrix = FloatArray(16)
                surfaceTexture.getTransformMatrix(transformMatrix)

                val videoFrame = VideoFrame(
                    /* ... SurfaceTexture 帧转 VideoFrame.Buffer */,
                    0, System.nanoTime()
                )
                videoSource.adaptOutputFormat(width, height, fps)
                // videoSource 接收到 frame 后自动转发给 peer connection
            }
        }, captureHandler)
    }

    fun stopCapture() {
        isCapturing = false
        virtualDisplay?.release()
        virtualDisplay = null
        captureThread?.quitSafely()
        captureThread = null
    }

    fun dispose() {
        stopCapture()
        mediaProjection.stop()
    }
}
```

---

## 4. SDP 约束说明（三端一致）

屏幕分享是 **单向流**，发送端只发送视频不接收音视频：

| 配置项 | 值 | 说明 |
|-------|-----|------|
| `OfferToReceiveAudio` | `false` | 不接收远端音频 |
| `OfferToReceiveVideo` | `false` | 不接收远端视频 |

Android 现有代码 `createPeerConnectionWrapperForSessionIdAndType()` 中，`type="screen"` 的接收端分支设置了 `OfferToReceiveVideo=true`，发送端分支（publisher=true）需要使用不同的约束或去掉接收设置。

**参考 iOS 实现**：iOS 的 publisher PeerConnection 不添加任何 recvonly transceiver，自然就是只发送。

---

## 5. unshareScreen 信令发送（三端一致）

| 模式 | PC 端 | iOS 端 | Android 端（需实现） |
|------|-------|--------|-------------------|
| **非 MCU** | `peer.send('unshareScreen')` | `NCUnshareScreenMessage(to:peer.peerId, roomType:kRoomTypeScreen, payload:{})` | `NCSignalingMessage(to=sessionId, type="unshareScreen", roomType="screen")` |
| **MCU** | `signaling.sendRoomMessage({roomType:"screen", type:"unshareScreen"})` | `[externalSignalingController sendRoomMessageOfType:@"unshareScreen" andRoomType:kRoomTypeScreen]` | `NCSignalingMessage(type="unshareScreen", roomType="screen")` 通过 `SignalingMessageSender.send()` 或 `MessageSender.sendToAll()` |

---

## 6. 实施步骤

### Phase 1: 创建 ScreenCapturer 封装

**文件**: `app/src/main/java/com/nextcloud/talk/screenshare/send/ScreenCapturer.kt`
- 封装 `MediaProjection` + `VirtualDisplay` → WebRTC `VideoSource`
- 参考 iOS: `NCScreensharingController` + `ScreenCapturer` + `ScreenCaptureController`
- 提供 `startCapture()` / `stopCapture()` / `dispose()`
- 分辨率默认 1280x720 @ 15fps

### Phase 2: 创建 UiState 和 ViewModel

**文件**: `app/src/main/java/com/nextcloud/talk/screenshare/send/ScreenShareSendUiState.kt`
- `sealed interface ScreenShareSendUiState`（Idle / Sharing）

**文件**: `app/src/main/java/com/nextcloud/talk/screenshare/send/ScreenShareSendViewModel.kt`
- 持有 UiState（Idle/Sharing）
- 提供 `onStartShareRequest` / `onStopShareRequest` 回调（由 CallActivity 注入）
- `startScreenShare(mediaProjection)` / `stopScreenShare()` 方法
- 注册到 `ViewModelModule.kt`

### Phase 3: 创建 Compose UI 组件

**文件**: `app/src/main/java/com/nextcloud/talk/screenshare/send/components/ScreenShareSendButton.kt`
- 通话控制栏中的分享按钮
- 观察 ViewModel.uiState 切换图标（分享/停止）
- 点击事件通过 ViewModel 转发

**文件**: `app/src/main/java/com/nextcloud/talk/screenshare/send/components/ScreenShareSendStatusOverlay.kt`
- 分享中的状态指示覆盖层
- 显示"正在分享屏幕"提示，提供停止按钮

### Phase 4: 集成到 MoreCallActionsDialog

**文件**: `app/src/main/java/com/nextcloud/talk/ui/dialog/MoreCallActionsDialog.kt`
- 添加"分享屏幕"菜单项
- 点击触发 CallActivity 的 `requestScreenSharePermission()`

**文件**: `app/src/main/res/layout/dialog_more_call_actions.xml`
- 添加屏幕分享条目

### Phase 5: 集成到 CallActivity

**文件**: `app/src/main/java/com/nextcloud/talk/activities/CallActivity.kt`

需要添加的逻辑（参考 iOS `NCCallController`）：

1. **MediaProjection 权限请求**：`registerForActivityResult(StartActivityForResult)` + `createScreenCaptureIntent()`
2. **注入 ViewModel 回调**：`screenShareSendViewModel.onStartShareRequest = { /* startScreenSharing */ }`
3. **`startScreenSharing(mediaProjection)` 方法**：
   - 创建 ScreenCapturer + VideoSource + VideoTrack
   - MCU: 创建 screen publisher PC
   - 非MCU: 逐个创建 screen peer + sendOffer
4. **`stopScreenSharing()` 方法**：
   - 停止捕获
   - 发送 unshareScreen 信令（MCU/非MCU）
   - 销毁所有 screen PC
5. **hangup() 中**：调用 `stopScreenSharing()`
6. **`terminateAudioVideo()` 中**：清理 ScreenCapturer 资源

---

## 7. 文件清单

### 新建文件

| # | 文件路径 | 说明 |
|---|---------|------|
| 1 | `screenshare/send/ScreenCapturer.kt` | MediaProjection → WebRTC VideoSource 封装 |
| 2 | `screenshare/send/ScreenShareSendUiState.kt` | 状态 sealed interface (Idle/Sharing) |
| 3 | `screenshare/send/ScreenShareSendViewModel.kt` | 独立 ViewModel |
| 4 | `screenshare/send/components/ScreenShareSendButton.kt` | 分享按钮 Compose |
| 5 | `screenshare/send/components/ScreenShareSendStatusOverlay.kt` | 分享状态覆盖层 |

### 修改文件

| # | 文件路径 | 修改内容 |
|---|---------|---------|
| 1 | `dagger/modules/ViewModelModule.kt` | 注册 `ScreenShareSendViewModel` |
| 2 | `activities/CallActivity.kt` | 权限请求、ScreenCapturer 管理、PC 创建、unshareScreen 发送、hangup 清理 |
| 3 | `ui/dialog/MoreCallActionsDialog.kt` | 添加屏幕分享按钮 |
| 4 | `res/layout/dialog_more_call_actions.xml` | 添加屏幕分享 UI 条目 |
| 5 | `AndroidManifest.xml` | 可能需要添加 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限 |

---

## 8. 与 AGENTS.md 规范的一致性

| 规范要求 | 符合情况 | 说明 |
|---------|---------|------|
| 优先 Compose + MVVM | ✅ | ScreenShareSendViewModel + Compose 组件 |
| 禁止新增传统 Activity | ✅ | 作为功能模块集成到 CallActivity |
| 禁止 ViewModel 持有 Context/View | ✅ | ViewModel 不持有任何 Context，通过回调通知 Activity |
| 禁止直接 `new ViewModel()` | ✅ | 通过 Dagger ViewModelFactory 创建 |
| sealed UiState | ✅ | ScreenShareSendUiState 使用 sealed interface |
| ViewModel 注册 | ✅ | 在 ViewModelModule.kt 注册 |
| SPDX 头注释 | ✅ | 所有新文件包含 |
| 按 feature 分包 | ✅ | `screenshare/send/` 独立包 |

---

## 9. 注意事项

1. **MediaProjection API 限制**：
   - Android 10+ 需要 `MediaProjectionManager.createScreenCaptureIntent()`
   - Android 14+ 需要 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限
   - 必须在前台 Service 中进行屏幕捕获

2. **PeerConnectionFactory**：
   - 发送端可复用接收端已有的 `screenSharePeerConnectionFactory`（software codec）
   - 屏幕流只需要 VideoTrack，不需要 AudioTrack

3. **SDP 约束**：
   - 发送端必须设置 `OfferToReceiveAudio=false, OfferToReceiveVideo=false`
   - 参考 PC 端 `receiveMedia: {offerToReceiveAudio: 0, offerToReceiveVideo: 0}`

4. **unshareScreen 信令**：
   - 格式与 PC/iOS 完全一致：`{type: "unshareScreen", roomType: "screen"}`
   - MCU 模式发到房间，非 MCU 模式逐个 session 发送
   - 信令通过现有的 `SignalingMessageSender` 或 `MessageSender` 发送

5. **hangup 清理**：
   - 必须在 `hangup()` 和 `onDestroy()` 中确保停止屏幕分享
   - 参考 iOS: `stopScreenshare` 在 `finishCallLocally` 和 `cleanCurrentPeerConnections` 中调用

6. **MoreCallActionsDialog 整合**：
   - 暂时保留 View System BottomSheet 方式
   - 屏幕分享按钮作为其中一项
   - 后续可整体迁移为 Compose BottomSheet

---

## 10. 测试计划

| 测试类型 | 测试内容 |
|---------|---------|
| 单元测试 | ViewModel 状态转换（Idle → Sharing → Idle） |
| 单元测试 | ScreenCapturer 初始化/销毁 |
| 集成测试 | MediaProjection 权限请求流程 |
| 集成测试 | screen PeerConnection 创建/销毁 |
| 集成测试 | unshareScreen 信令发送（MCU/非MCU） |
| 手动测试 | 启动/停止屏幕分享 |
| 手动测试 | 屏幕分享中切换应用 |
| 手动测试 | Android → PC/iOS 接收屏幕分享 |
| 手动测试 | PC/iOS → Android 接收屏幕分享（验证兼容性） |
| 手动测试 | 挂断通话时自动停止分享 |
| 手动测试 | 屏幕分享 + 摄像头/麦克风同时开启 |
