# 文件临时消息 —— 完整开发计划

> 支持本地文件作为临时消息发送，文件上传成功后替换为在线消息

---

## 一、概述

### 1.1 现状

| 功能 | 文字消息 | 文件上传 |
|------|---------|---------|
| 发送前创建临时消息 | ✅ `addTemporaryMessage()` | ❌ 没有 |
| 显示"发送中"状态 | ✅ 时钟图标 | ❌ 完全不可见 |
| 上传失败标记 | ✅ `FAILED` 状态 + 红色图标 | ❌ |
| 失败后重试 | ✅ 长按→重新发送 | ❌ |
| 完成替换 | ✅ `referenceId` 匹配轮询替换 | ❌ |

### 1.2 目标

1. 选择文件后立即显示临时文件消息（文件名 + 文件图标 + 发送状态）
2. 上传成功后自动替换为正常的在线文件消息
3. 上传失败时显示失败状态，用户可通过长按重新发送
4. 残留临时消息自动清理（超时 24h）

### 1.3 核心流程

```
用户选择文件
  │
  ├─ ChatActivity.uploadFile() → ChatViewModel.uploadFileWithTempMessage()
  │
  ├─ Repository.addTemporaryFileMessage()
  │   ├─ referenceId 生成
  │   ├─ 创建临时 ChatMessageEntity（isTemporary=true, sendStatus=PENDING）
  │   │   └─ messageParameters 含本地文件信息（name, path, mimetype, size）
  │   └─ _messageFlow.emit() → 界面立即显示"发送中"
  │
  ├─ UploadAndShareFilesWorker.upload()（metaData 携带 referenceId + roomToken）
  │
  ├─ [成功] ViewModel 监听 Worker 完成
  │   └─ Repository.sendConfirmFileMessage(referenceId, caption, fileId)
  │       └─ 服务器消息携带 referenceId → 轮询自动匹配替换
  │
  └─ [失败] ViewModel 监听 Worker 失败
      └─ Repository.markFileMessageFailed(referenceId)
          └─ sendStatus = FAILED → 界面红色图标 + "Failed"

  用户长按失败消息
    └─ TempMessageActionsDialog → "重新发送"
        └─ resendFileMessage() → 重新上传
```

---

## 二、方案选择

| 选项 | 替换机制 | 可靠性 | 改动量 |
|------|---------|--------|--------|
| **A：纯轮询替换** | 不上传 Worker 结果，仅靠长轮询自动匹配 | ❌ 服务器不确定返回 `referenceId` | 小 |
| **B：主动替换（推荐）** | Worker 完成后客户端主动调用发送 API | ✅ `referenceId` 100% 匹配 | 中 |

**选择 B**。原因：文件上传 API 返回文件 ID 但不保证创建聊天消息的 `referenceId`。客户端主动发送确认消息可确保 `referenceId` 一致，轮询自动匹配替换。

---

## 三、文件修改清单

### 3.1 新增/修改文件

| # | 文件 | 操作 | 说明 |
|---|------|------|------|
| 1 | `OfflineFirstChatRepository.kt` | ✅ 新增方法 | `addTemporaryFileMessage()`、`sendConfirmFileMessage()`、`markFileMessageFailed()`、`cleanupStaleTempMessages()` |
| 2 | `ChatMessageRepository.kt` | ✅ 新增接口 | 上述 4 个方法在接口中的定义 |
| 3 | `ChatViewModel.kt` | ✅ 新增/修改 | `uploadFileWithTempMessage()`、`resendFileMessage()` |
| 4 | `ChatActivity.kt` | ✅ 修改 | `uploadFile()` 改为走新流程；`onCreate` 中调用残留清理 |
| 5 | `PreviewMessageViewHolder.kt` | ✅ 修改 | 兼容 `isTemporary=true` 的本地文件显示（文件名、文件图标、发送状态） |
| 6 | `UploadAndShareFilesWorker.kt` | ✅ 修改 | 改为 `CoroutineWorker`；`metaData` 中携带 `referenceId` + `roomToken`；成功后输出 `fileId` |

### 3.2 无需修改但需了解的文件

| 文件 | 说明 |
|------|------|
| `TempMessageActionsDialog.kt` | 长按临时消息的弹窗，自动显示"重新发送"（已有逻辑，无需修改） |
| `OutcomingTextMessageViewHolder.kt` | 状态显示（时钟/错误/勾）——仅文字消息使用，文件消息走 `PreviewMessageViewHolder` |
| `handleNewAndTempMessages()` | 轮询替换逻辑，通过 `referenceId` 匹配，无需修改 |
| `sendUnsentChatMessages()` | 页面打开时自动重试未发送消息，文件临时消息标记为 `FAILED` 后走此路径 |

---

## 四、详细设计

### 4.1 OfflineFirstChatRepository.kt

#### 4.1.1 `addTemporaryFileMessage()`

```kotlin
override suspend fun addTemporaryFileMessage(
    fileUri: String,
    caption: String,
    replyTo: Int,
    displayName: String,
    internalConversationId: String
): Flow<Result<ChatMessage?>> = flow {
    val referenceId = SendMessageUtils().generateReferenceId()
    val currentTimeMillis = System.currentTimeMillis()
    val internalId = "${internalConversationId}@_temp_$currentTimeMillis"
    
    val fileName = Uri.parse(fileUri).lastPathSegment ?: "unknown"
    val mimeType = getMimeType(fileUri)
    val fileSize = getFileSize(fileUri) // 辅助方法，返回 Long
    
    // 构建设置参数（模仿在线文件消息格式）
    val fileParams = HashMap<String?, String?>()
    fileParams["type"] = "file"
    fileParams["name"] = fileName
    fileParams["path"] = fileUri
    fileParams["mimetype"] = mimeType
    fileParams["size"] = fileSize.toString()
    val messageParameters = HashMap<String?, HashMap<String?, String?>>()
    messageParameters["file"] = fileParams

    val entity = ChatMessageEntity(
        internalId = internalId,
        internalConversationId = internalConversationId,
        jsonMessageId = 0,
        referenceId = referenceId,
        isTemporary = true,
        sendStatus = SendStatus.PENDING,
        messageType = MessageType.SINGLE_NC_ATTACHMENT_MESSAGE.name,
        message = caption.ifEmpty { fileName },
        messageParameters = messageParameters,
        actorDisplayName = displayName,
        timestamp = currentTimeMillis,
        // ... 其他字段使用默认值
    )
    
    chatDao.upsertChatMessage(entity)
    val model = entity.asModel()
    emit(Result.success(model))
    _messageFlow.emit(Triple(true, false, listOf(model)))
}
```

**关键点**：
- `referenceId` 确保后续轮询匹配替换
- `messageParameters` 中包含本地文件信息（path 为 Content URI）
- `messageType = SINGLE_NC_ATTACHMENT_MESSAGE` 使 ChatKit 路由到 `PreviewMessageViewHolder`
- `jsonMessageId = 0` 标识它为临时消息

#### 4.1.2 `sendConfirmFileMessage()`

上传成功后，通过 API 发送聊天消息（携带服务器文件 ID 和临时消息的 `referenceId`）：

```kotlin
override suspend fun sendConfirmFileMessage(
    referenceId: String,
    caption: String,
    fileId: Long,
    roomToken: String
): Flow<Result<ChatMessage?>> {
    // 构建带文件附件的消息参数
    val messageParameters = hashMapOf(
        "file" to hashMapOf(
            "type" to "file",
            "id" to fileId.toString()
        )
    )
    val messageText = caption.ifEmpty { "{file}" }
    
    // 调用常规发送消息 API，但携带 referenceId
    return sendChatMessage(
        credentials = credentials,
        url = ApiUtils.getUrlForChat(chatApiVersion, baseUrl, roomToken),
        message = messageText,
        displayName = displayName,
        replyTo = 0,
        sendWithoutNotification = false,
        referenceId = referenceId,
        threadTitle = null,
        messageParameters = messageParameters
    )
}
```

**注意**：需要将 `sendChatMessage` 的可选参数扩展，使其支持传递 `messageParameters`。当前 `sendChatMessage` 接受 `message: String`，不直接支持 `messageParameters`。需要查看 API 调用是否支持。

或者：使用 `api.sendChatMessage()` 直接调用（绕开 `sendChatMessage` 的业务逻辑）：

```kotlin
val response = api.sendChatMessage(
    credentials = credentials,
    url = ApiUtils.getUrlForChat(...),
    message = messageText,
    referenceId = referenceId,
    messageParameters = Gson().toJson(messageParameters),
    // ... 其他参数
)
```

#### 4.1.3 `markFileMessageFailed()`

```kotlin
override suspend fun markFileMessageFailed(referenceId: String) {
    val tempMessage = chatDao.getTempMessageForConversation(
        internalConversationId, referenceId, threadId
    ).firstOrNull()
    tempMessage?.let {
        it.sendStatus = SendStatus.FAILED
        chatDao.updateChatMessage(it)
        _updateMessageFlow.emit(it.asModel())
    }
}
```

#### 4.1.4 `cleanupStaleTempMessages()`

```kotlin
override suspend fun cleanupStaleTempMessages() {
    val staleMessages = chatDao.getTempMessagesForConversation(internalConversationId)
        .first()
        .filter { System.currentTimeMillis() - it.timestamp > STALE_TEMP_TIMEOUT }
    if (staleMessages.isNotEmpty()) {
        staleMessages.forEach { stale ->
            _removeMessageFlow.emit(stale.asModel())
        }
        chatDao.deleteChatMessages(staleMessages.map { it.internalId })
    }
}

companion object {
    private const val STALE_TEMP_TIMEOUT = 24 * 60 * 60 * 1000L // 24 小时
}
```

### 4.2 ChatViewModel.kt

#### 4.2.1 `uploadFileWithTempMessage()`

```kotlin
fun uploadFileWithTempMessage(
    fileUri: String,
    isVoiceMessage: Boolean,
    caption: String,
    roomToken: String,
    replyToMessageId: Int,
    displayName: String
) {
    val internalConversationId = chatRoomToken
    
    viewModelScope.launch {
        // Step 1: 创建临时消息
        chatRepository.addTemporaryFileMessage(
            fileUri = fileUri,
            caption = caption,
            replyTo = replyToMessageId,
            displayName = displayName,
            internalConversationId = internalConversationId
        ).collect { result ->
            if (result.isSuccess) {
                val tempMessage = result.getOrNull()!!
                val referenceId = tempMessage.referenceId ?: return@collect
                
                // Step 2: 启动上传 Worker（携带 referenceId 和 roomToken）
                val metaDataMap = mutableMapOf<String, Any>()
                metaDataMap["referenceId"] = referenceId
                metaDataMap["roomToken"] = roomToken
                val metaData = Gson().toJson(metaDataMap)
                
                val workId = UploadAndShareFilesWorker.uploadWithId(
                    fileUri, roomToken, displayName, metaData
                )
                
                // Step 3: 监听 Worker 完成
                observeUploadWorkResult(workId, referenceId, caption, roomToken)
            }
        }
    }
}

private fun observeUploadWorkResult(
    workId: UUID,
    referenceId: String,
    caption: String,
    roomToken: String
) {
    viewModelScope.launch {
        WorkManager.getInstance(application)
            .getWorkInfoByIdFlow(workId)
            .collect { workInfo ->
                if (workInfo.state.isFinished) {
                    if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                        val fileId = workInfo.outputData.getLong("fileId", -1L)
                        if (fileId > 0) {
                            // 上传成功 → 发送确认消息
                            chatRepository.sendConfirmFileMessage(
                                referenceId, caption, fileId, roomToken
                            ).collect { /* 结果处理 */ }
                        }
                    } else {
                        // 上传失败 → 标记临时消息为 FAILED
                        chatRepository.markFileMessageFailed(referenceId)
                    }
                }
            }
    }
}
```

#### 4.2.2 `resendFileMessage()`（复用 `resendMessage` 的框架）

```kotlin
fun resendFileMessage(
    credentials: String,
    urlForChat: String,
    message: ChatMessage
) {
    viewModelScope.launch {
        // 从临时消息的 messageParameters 中提取文件路径
        val fileParams = message.messageParameters?.get("file")
        val fileUri = fileParams?.get("path") ?: return@launch
        
        // 重置状态为 PENDING
        chatRepository.resendChatMessage(
            credentials, urlForChat,
            message.message.orEmpty(),
            message.actorDisplayName.orEmpty(),
            message.parentMessageId?.toIntOrZero() ?: 0,
            false,
            message.referenceId.orEmpty()
        ).collect { result ->
            if (result.isSuccess) {
                // 重新上传文件（重新走临时文件上传流程）
                uploadFileWithTempMessage(
                    fileUri = fileUri,
                    isVoiceMessage = false,
                    caption = message.message.orEmpty(),
                    roomToken = chatRoomToken,
                    replyToMessageId = message.parentMessageId?.toIntOrZero() ?: 0,
                    displayName = message.actorDisplayName.orEmpty()
                )
            }
        }
    }
}
```

### 4.3 ChatActivity.kt

#### 4.3.1 `uploadFile()` 修改

```kotlin
fun uploadFile(fileUri: String, isVoiceMessage: Boolean, caption: String,
               roomToken: String, replyToMessageId: Int, displayName: String) {
    // 原：chatViewModel.uploadFile(...)  // 直接走 Worker
    // 改：
    chatViewModel.uploadFileWithTempMessage(fileUri, isVoiceMessage, caption, 
                                            roomToken, replyToMessageId, displayName)
    cancelReply()
}
```

#### 4.3.2 启动时清理残留临时消息

在 `ChatActivity` 的合适位置（如 `initObservers` 或 `onCreate` 中）调用：

```kotlin
// 清理超时的残留临时消息（24 小时前的文件临时消息）
lifecycleScope.launch {
    chatViewModel.cleanupStaleTempMessages()
}
```

并在 `ChatViewModel` 中：

```kotlin
fun cleanupStaleTempMessages() {
    viewModelScope.launch {
        chatRepository.cleanupStaleTempMessages()
    }
}
```

### 4.4 PreviewMessageViewHolder.kt

需要修改 `onBind()` 方法，当 `message.isTemporary` 时从本地 `messageParameters` 读取文件信息：

```kotlin
override fun onBind(message: ChatMessage) {
    super.onBind(message)
    sharedApplication!!.componentApplication.inject(this)
    
    if (message.isTemporary) {
        bindTemporaryFileMessage(message)
    } else {
        bindOnlineFileMessage(message)
    }
}

private fun bindTemporaryFileMessage(message: ChatMessage) {
    val fileParams = message.messageParameters?.get("file") ?: return
    val fileName = fileParams["name"] ?: ""
    val mimeType = fileParams["mimetype"] ?: "application/octet-stream"
    val fileSize = fileParams["size"]?.toLongOrNull() ?: 0L
    
    // 显示文件名
    binding.messageText.text = fileName
    binding.messageText.visibility = View.VISIBLE
    
    // 显示文件大小
    binding.messageSize.text = formatFileSize(fileSize)
    binding.messageSize.visibility = View.VISIBLE
    
    // 显示文件类型图标（根据扩展名）
    showFileTypeIcon(mimeType)
    
    // 显示发送状态（PENDING→时钟, FAILED→红色错误, 发送中→进度指示器）
    showFileSendingStatus(message)
}

private fun showFileSendingStatus(message: ChatMessage) {
    when (message.sendStatus) {
        SendStatus.PENDING -> {
            updateStatus(R.drawable.baseline_schedule_24, "Sending")
        }
        SendStatus.FAILED -> {
            updateStatus(R.drawable.baseline_error_outline_24, "Failed")
        }
        else -> {
            // 正常情况下不应进入（在线消息走另一分支）
            updateStatus(R.drawable.ic_check, "Sent")
        }
    }
}
```

**注意**：`PreviewMessageViewHolder` 当前可能没有 `updateStatus()` 方法，需要参考 `OutcomingTextMessageViewHolder` 中的实现添加。或者复用父类的 status/sending 布局。

### 4.5 UploadAndShareFilesWorker.kt

#### 4.5.1 新增 `uploadWithId()` 方法

```kotlin
fun uploadWithId(
    fileUri: String, 
    roomToken: String, 
    conversationName: String, 
    metaData: String?
): UUID {
    val data = Data.Builder()
        .putString(DEVICE_SOURCE_FILE, fileUri)
        .putString(ROOM_TOKEN, roomToken)
        .putString(CONVERSATION_NAME, conversationName)
        .putString(META_DATA, metaData)
        .build()
    val uploadWorker = OneTimeWorkRequest.Builder(UploadAndShareFilesWorker::class.java)
        .setInputData(data)
        .build()
    WorkManager.getInstance().enqueueUniqueWork(
        fileUri, ExistingWorkPolicy.KEEP, uploadWorker
    )
    return uploadWorker.id  // 返回 workId 供 ViewModel 监听
}
```

#### 4.5.2 Worker 输出 `fileId`

参考现有 `doWork()` 方法。在文件上传成功后，在 `Result.success()` 的 `outputData` 中输出服务器返回的文件 ID：

```kotlin
// doWork() 中上传成功后
val outputData = Data.Builder()
    .putLong("fileId", uploadedFileId)
    .build()
return Result.success(outputData)
```

**注意**：当前 `UploadAndShareFilesWorker` 是 `Worker`（非 `CoroutineWorker`），改为 `CoroutineWorker` 更有利于异步回调。如果改为 `CoroutineWorker`，需要同时将基类从 `Worker` 改为 `CoroutineWorker`。

---

## 五、实施顺序

```
Phase 1 — 临时消息创建（1~2 小时）
├── OfflineFirstChatRepository.addTemporaryFileMessage()
├── ChatMessageRepository 接口新增
├── ChatViewModel.uploadFileWithTempMessage() 上半部分（创建临时消息）
└── ChatActivity.uploadFile() 改为走新流程

Phase 2 — 临时消息渲染（1~2 小时）
├── PreviewMessageViewHolder 兼容本地文件信息
├── 添加发送状态图标
└── 验证临时消息在列表中的显示

Phase 3 — 上传完成替换（1~2 小时）
├── UploadAndShareFilesWorker 输出 fileId
├── ViewModel 监听 Worker 完成
├── Repository.sendConfirmFileMessage()
├── Repository.markFileMessageFailed()
└── resendFileMessage() 重发逻辑

Phase 4 — 残留清理 & 收尾（0.5 小时）
├── cleanupStaleTempMessages()
├── 编译验证
└── 完整流程测试
```

**总预估**：4~6 小时

---

## 六、验证清单

| # | 验证项 | 预期结果 |
|---|--------|---------|
| 1 | 选择文件发送 | 立即显示临时文件消息（文件名 + 文件图标 + 时钟"发送中"） |
| 2 | 上传成功 + 轮询替换 | 临时消息消失，在线消息出现（正常文件内容 + 双勾） |
| 3 | 上传失败 | 临时消息显示红色错误图标 + "Failed" |
| 4 | 长按失败消息 | 弹出 `TempMessageActionsDialog`，显示"重新发送" |
| 5 | 重新发送成功 | 重新上传 → 替换为在线消息 |
| 6 | 再次失败 | 继续显示失败状态 |
| 7 | App 重启 + 失败消息 | 残留消息不超过 24h → 自动重发；超过 → 自动删除 |
| 8 | 编译 | `./gradlew :app:compileClpsDebugKotlin` 通过 |

---

## 七、风险与注意事项

1. **ChatKit ViewHolder 路由**：需要确认临时文件消息能正确路由到 `PreviewMessageViewHolder`。`ChatMessage.getImageUrl()` 在临时消息下可能返回空，ChatKit 可能降级到 `TextMessageViewHolder`。建议在 `hasContentFor()` 中针对 `isTemporary` 做额外判断，或临时设置一个假的 `getImageUrl()`。

2. **Worker 改为 `CoroutineWorker`**：当前是 `Worker`（同步 `doWork()`），改为 `CoroutineWorker` 需要同时修改项目其他依赖，或在 `Worker` 中直接 `runBlocking` 调用。建议当前保留 `Worker`，仅通过 `outputData` 输出结果，`WorkManager.getWorkInfoByIdFlow` 在任何 Worker 类型下都能工作。

3. **`sendConfirmFileMessage` 的 API 调用**：需要确认 Talk API 是否支持在发送消息时传入 `messageParameters`（JSON 格式的文件附件）。查看 `api.sendChatMessage()` 的参数列表。如果不支持，可能需要使用其他 API 端点或方式。

4. **权限**：读取本地文件 URI 需要 `READ_EXTERNAL_STORAGE` 或 `READ_MEDIA_*` 权限。临时消息显示时通过 URI 读取文件信息可能需要适当权限处理。

5. **文件 URI 的持久性**：临时消息存储在 Room 中后，文件 URI（可能是 Content URI）可能在系统重启后失效。建议同时缓存文件名、MIME 类型等信息到 `messageParameters`。
