/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.data.model.clps

import android.util.Log
import androidx.core.text.toSpanned
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.nextcloud.talk.R
import com.nextcloud.talk.adapters.items.MessageResultItem
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.chat.ChatActivity
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.chat.data.model.ChatMessage.MessageType
import com.nextcloud.talk.conversationlist.ConversationsListActivity
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

/**
 * 获取类似微信的消息显示文本
 * 根据不同的消息类型返回对应的简短描述
 */
@Suppress("ReturnCount")
fun ChatMessage.getWeChatStyleDisplayText(): String? {
    val context = sharedApplication?.applicationContext ?: return null

    return when (getCalculateMessageType()) {
        MessageType.SYSTEM_MESSAGE -> {
            // 系统消息不显示内容
            null
        }
        MessageType.VOICE_MESSAGE -> {
            // 语音消息
            context.getString(R.string.clps_voice_message)
        }
        MessageType.SINGLE_NC_ATTACHMENT_MESSAGE -> {
            // 文件附件 - 获取文件名
            messageParameters?.let { params ->
                for ((_, individualHashMap) in params) {
                    if (isHashMapEntryEqualTo(individualHashMap, "type", "file")) {
                        return individualHashMap["name"]
                            ?: context.getString(R.string.clps_file_attachment)
                    }
                }
            }
            context.getString(R.string.clps_file_attachment)
        }
        MessageType.SINGLE_NC_GEOLOCATION_MESSAGE -> {
            // 位置消息
            context.getString(R.string.clps_location_message)
        }
        MessageType.POLL_MESSAGE -> {
            // 投票消息
            context.getString(R.string.clps_poll_message)
        }
        MessageType.DECK_CARD -> {
            // Deck卡片
            context.getString(R.string.clps_deck_card)
        }
        MessageType.SINGLE_LINK_GIPHY_MESSAGE,
        MessageType.SINGLE_LINK_TENOR_MESSAGE,
        MessageType.SINGLE_LINK_GIF_MESSAGE -> {
            // GIF动图
            context.getString(R.string.clps_gif_message)
        }
        MessageType.SINGLE_LINK_IMAGE_MESSAGE -> {
            // 图片消息
            context.getString(R.string.clps_image_message)
        }
        MessageType.SINGLE_LINK_VIDEO_MESSAGE -> {
            // 视频消息
            context.getString(R.string.clps_video_message)
        }
        MessageType.SINGLE_LINK_AUDIO_MESSAGE -> {
            // 音频消息
            context.getString(R.string.clps_audio_message)
        }
        MessageType.SINGLE_LINK_MESSAGE -> {
            // 链接消息
            context.getString(R.string.clps_link_message)
        }
        MessageType.REGULAR_TEXT_MESSAGE -> {
            if (isMultiMessage()) {
                val title = multiMessage().title
                if (!title.isNullOrBlank()) "[$title]" else context.getString(R.string.clps_chat_history)
            } else {
                // 普通文本消息 - 直接返回文本内容
                message?.take(50)
            }
        }
    }
}

/**
 * 判断消息内容是否可以解析为 MultiMessage
 * @return 如果可以解析为 MultiMessage 返回 true，否则返回 false
 */
fun ChatMessage.isMultiMessage(): Boolean {
    if (message.isNullOrBlank()) {
        return false
    }

    // 快速检查：JSON 对象应该以 { 开始
    val trimmedContent = message!!.trim()
    if (!trimmedContent.startsWith("{")) {
        return false
    }

    return try {
        // 尝试使用 Gson 解析为 MultiMessage
        val gson = Gson()
        val multiMessage = gson.fromJson(trimmedContent, MultiMessage::class.java)

        // 验证解析结果是否有效
        multiMessage != null &&
            (multiMessage.title != null || !multiMessage.message.isNullOrEmpty())
    } catch (e: JsonSyntaxException) {
        // 解析失败，说明不是有效的 JSON 格式
        Log.d("Ray", "Message cannot be parsed as MultiMessage: ${e.message}")
        false
    } catch (e: Exception) {
        // 其他异常也视为不可解析
        Log.d("Ray", "Error parsing message as MultiMessage: ${e.message}")
        false
    }
}

/**
 * 判断消息内容是否可以解析为 MultiMessage
 * @return 如果可以解析为 MultiMessage 返回 true，否则返回 false
 */
fun ChatMessage.multiMessage(): MultiMessage {
    if (message.isNullOrBlank()) {
        return MultiMessage()
    }

    // 快速检查：JSON 对象应该以 { 开始
    val trimmedContent = message!!.trim()
    if (!trimmedContent.startsWith("{")) {
        return MultiMessage()
    }

    return try {
        // 尝试使用 Gson 解析为 MultiMessage
        val gson = Gson()
        return gson.fromJson(trimmedContent, MultiMessage::class.java)
    } catch (e: JsonSyntaxException) {
        // 解析失败，说明不是有效的 JSON 格式
        Log.d("Ray", "Message MultiMessage: ${e.message}")
        MultiMessage()
    } catch (e: Exception) {
        // 其他异常也视为不可解析
        Log.d("Ray", "Error parsing message as MultiMessage: ${e.message}")
        MultiMessage()
    }
}

private const val MAX_MULTI_MESSAGE = 2
/**
 * 解析并显示 MultiMessage 消息
 * @return 处理后的消息文本
 */
fun ChatMessage.parseAndDisplayMultiMessage(): CharSequence {
    try {
        val gson = Gson()
        val multiMessage = gson.fromJson(message, MultiMessage::class.java)

        // 构建 MultiMessage 的显示文本
        val displayText = StringBuilder()

        // 添加标题（如果有）
        if (!multiMessage.title.isNullOrBlank()) {
            displayText.append(multiMessage.title)
            displayText.append("\n\n")
        }

        // 添加消息数量提示
        val messageCount = multiMessage.message?.size ?: 0
        if (messageCount > 0) {
            displayText.append("【包含 $messageCount 条消息】\n")

            // 显示前几条消息的预览
            val previewLimit = minOf(messageCount, MAX_MULTI_MESSAGE)
            for (i in 0 until previewLimit) {
                val msg = multiMessage.message!![i]
                val actorName = msg.actorDisplayName ?: "未知"
                // val msgText = msg.message?.take(50) ?: ""
                val msgText = msg.getWeChatStyleDisplayText().toString()
                displayText.append("- $actorName: $msgText")

                if (msg.isMultiMessage()) {
                    displayText.append("\n")
                    continue
                }

                if (msg.message?.length ?: 0 > 50) {
                    displayText.append("...")
                }
                displayText.append("\n")
            }

            // 如果消息超过 MAX_MULTI_MESSAGE 条，显示省略提示
            if (messageCount > MAX_MULTI_MESSAGE) {
                displayText.append("… 还有 ${messageCount - MAX_MULTI_MESSAGE} 条消息")
            }

            if (messageParameters == null) {
                messageParameters = HashMap()
            }
            multiMessage.message?.forEachIndexed { index, item ->
                Log.d("Ray", "-------------item $index")
                // messageParameters也许需要更新加上index
                item.messageParameters?.forEach { (key, value) ->
                    // 只保留特殊类型的参数（用户、群组等）
                    when (value["type"]) {
                        "user", "guest", "call", "user-group", "email", "circle" -> {
                            messageParameters!![key] = value
                        }
                    }
                }
            }
        }

        return displayText

    } catch (e: Exception) {
        // 如果解析失败，回退到原始消息处理
        Log.e("Ray", "Failed to parse MultiMessage", e)
        return message!!.toSpanned()
    }
}

/**
 * 解析并显示 MultiMessage title消息
 * @return 处理后的消息文本
 */
fun ChatMessage.parseAndDisplayMultiMessageTitle(): CharSequence {
    try {
        val gson = Gson()
        val multiMessage = gson.fromJson(message, MultiMessage::class.java)

        // 构建 MultiMessage 的显示文本
        val displayText = StringBuilder()

        // 仿微信显示，其他端暂时没有
        // displayText.append(getNullsafeActorDisplayName())
        // if (displayText.isNotBlank()){
        //     displayText.append(": ")
        // }
        // displayText.append(sharedApplication!!.getString(R.string.clps_chat_history))

        // 添加标题（如果有）
        if (!multiMessage.title.isNullOrBlank()) {
            displayText.append(multiMessage.title)
        } else {
            displayText.append(sharedApplication!!.getString(R.string.clps_chat_history))
        }

        return displayText

    } catch (e: Exception) {
        // 如果解析失败，回退到原始消息处理
        Log.e("Ray", "Failed to parse MultiMessage", e)
        return message!!.toSpanned()
    }
}

/**
 * 解析并显示 MultiMessage 消息
 * @return 处理后的消息文本
 */
fun MessageResultItem.parseAndDisplayMultiMessage(): CharSequence {
    try {
        val gson = Gson()
        val multiMessage = gson.fromJson(messageEntry.messageExcerpt, MultiMessage::class.java)

        // 构建 MultiMessage 的显示文本
        val displayText = StringBuilder()

        // 添加标题（如果有）
        if (!multiMessage.title.isNullOrBlank()) {
            displayText.append(multiMessage.title)
            displayText.append("\n\n")
        }

        // 添加消息数量提示
        val messageCount = multiMessage.message?.size ?: 0
        if (messageCount > 0) {
            displayText.append("【包含 $messageCount 条消息】\n")

            // 显示前几条消息的预览
            val previewLimit = minOf(messageCount, MAX_MULTI_MESSAGE)
            for (i in 0 until previewLimit) {
                val msg = multiMessage.message!![i]
                val actorName = msg.actorDisplayName ?: "未知"
                // val msgText = msg.message?.take(50) ?: ""
                val msgText = msg.getWeChatStyleDisplayText().toString()
                displayText.append("- $actorName: $msgText")

                if (msg.isMultiMessage()) {
                    displayText.append("\n")
                    continue
                }

                if (msg.message?.length ?: 0 > 50) {
                    displayText.append("...")
                }
                displayText.append("\n")
            }

            // 如果消息超过 MAX_MULTI_MESSAGE 条，显示省略提示
            if (messageCount > MAX_MULTI_MESSAGE) {
                displayText.append("… 还有 ${messageCount - MAX_MULTI_MESSAGE} 条消息")
            }

            if (messageEntry.messageParameters == null) {
                messageEntry.messageParameters = HashMap()
            }
            multiMessage.message?.forEachIndexed { index, item ->
                Log.d("Ray", "-------------item $index")
                // messageParameters也许需要更新加上index
                item.messageParameters?.forEach { (key, value) ->
                    // 只保留特殊类型的参数（用户、群组等）
                    when (value["type"]) {
                        "user", "guest", "call", "user-group", "email", "circle" -> {
                            messageEntry.messageParameters!![key] = value
                        }
                    }
                }
            }
        }

        return displayText

    } catch (e: Exception) {
        // 如果解析失败，回退到原始消息处理
        Log.e("Ray", "Failed to parse MultiMessage", e)
        return messageEntry.messageExcerpt.toSpanned()
    }
}

// /**
//  * 解析并显示 MultiMessage title消息
//  * @return 处理后的消息文本
//  */
// fun MessageResultItem.parseAndDisplayMultiMessageTitle(): CharSequence {
//     try {
//         val gson = Gson()
//         val multiMessage = gson.fromJson(messageEntry.messageExcerpt, MultiMessage::class.java)
//
//         // 构建 MultiMessage 的显示文本
//         val displayText = StringBuilder()
//
//         displayText.append(getNullsafeActorDisplayName())
//         if (displayText.isNotBlank()){
//             displayText.append(": ")
//         }
//         displayText.append(sharedApplication!!.getString(R.string.clps_chat_history))
//         // 添加标题（如果有）
//         if (!multiMessage.title.isNullOrBlank()) {
//             displayText.append(multiMessage.title)
//         }
//
//         return displayText
//
//     } catch (e: Exception) {
//         // 如果解析失败，回退到原始消息处理
//         Log.e("Ray", "Failed to parse MultiMessage", e)
//         return message!!.toSpanned()
//     }
// }


/**
 * 解析并显示 MultiMessage 消息
 * @return 处理后的消息文本
 */
fun ConversationsListActivity.parseAndDisplayMultiMessage(): CharSequence {
    try {
        val gson = Gson()
        val multiMessage = gson.fromJson(forwardMessagesJson, MultiMessage::class.java)

        // 构建 MultiMessage 的显示文本
        val displayText = StringBuilder()

        // 添加标题（如果有）
        if (!multiMessage.title.isNullOrBlank()) {
            displayText.append(multiMessage.title)
            displayText.append("\n\n")
        }

        // 添加消息数量提示
        val messageCount = multiMessage.message?.size ?: 0
        if (messageCount > 0) {
            displayText.append("【包含 $messageCount 条消息】\n")

            // 显示前几条消息的预览
            val previewLimit = minOf(messageCount, MAX_MULTI_MESSAGE)
            for (i in 0 until previewLimit) {
                val msg = multiMessage.message!![i]
                val actorName = msg.actorDisplayName ?: "未知"
                // val msgText = msg.message?.take(50) ?: ""
                val msgText = msg.getWeChatStyleDisplayText().toString()
                displayText.append("- $actorName: $msgText")

                if (msg.isMultiMessage()) {
                    displayText.append("\n")
                    continue
                }

                if (msg.message?.length ?: 0 > 50) {
                    displayText.append("...")
                }
                displayText.append("\n")
            }

            // 如果消息超过 MAX_MULTI_MESSAGE 条，显示省略提示
            if (messageCount > MAX_MULTI_MESSAGE) {
                displayText.append("… 还有 ${messageCount - MAX_MULTI_MESSAGE} 条消息")
            }

            // 替换member
            val messageParameters: HashMap<String?, HashMap<String?, String?>> = HashMap()
            multiMessage.message?.forEachIndexed { index, item ->
                Log.d("Ray", "-------------item $index")
                // messageParameters也许需要更新加上index
                item.messageParameters?.forEach { (key, value) ->
                    // 只保留特殊类型的参数（用户、群组等）
                    when (value["type"]) {
                        "user", "guest", "call", "user-group", "email", "circle" -> {
                            messageParameters!![key] = value
                        }
                    }
                }
            }

            // 替换 mention 占位符
            val finalText = replaceMentionPlaceholders(displayText.toString(), messageParameters)
            return finalText.toSpanned()
        }

        return displayText

    } catch (e: Exception) {
        // 如果解析失败，回退到原始消息处理
        Log.e("Ray", "Failed to parse MultiMessage", e)
        return forwardMessagesJson!!.toSpanned()
    }
}

/**
 * 替换 displayText 中的 {mention-key} 占位符为 @name 格式
 * @param displayText 原始显示文本
 * @param messageParameters 消息参数映射
 * @return 替换后的文本
 */
fun replaceMentionPlaceholders(displayText: String, messageParameters: HashMap<String?, HashMap<String?, String?>>?): String {
    if (messageParameters == null || messageParameters.isEmpty()) {
        return displayText
    }

    var result = displayText

    // 遍历 messageParameters，查找并替换 {key} 格式的占位符
    for ((key, value) in messageParameters) {
        if (key != null && value != null && value.containsKey("name")) {
            val placeholder = "{$key}"
            val name = value["name"]

            // 如果占位符存在于文本中，且 name 不为空
            if (result.contains(placeholder) && !name.isNullOrBlank()) {
                val mentionType = value["type"]

                // 对于 user、guest、call 类型，添加 @ 前缀
                val replacement = if (mentionType == "user" ||
                    mentionType == "guest" ||
                    mentionType == "call") {
                    "@$name"
                } else {
                    name
                }

                // 替换所有匹配的占位符
                result = result.replace(placeholder, replacement)
            }
        }
    }

    return result
}

/**
 * 判断消息内容是否可以解析为 MultiMessage
 * @return 如果可以解析为 MultiMessage 返回 true，否则返回 false
 */
fun ChatActivity.isSharedMessagesJson(): Boolean {
    if (sharedMessagesJson.isNullOrBlank()) {
        return false
    }

    // 快速检查：JSON 对象应该以 { 开始
    val trimmedContent = sharedMessagesJson!!.trim()
    if (!trimmedContent.startsWith("{")) {
        return false
    }

    return try {
        // 尝试使用 Gson 解析为 MultiMessage
        val gson = Gson()
        val multiMessage = gson.fromJson(trimmedContent, MultiMessage::class.java)

        // 验证解析结果是否有效
        multiMessage != null &&
            (multiMessage.title != null || !multiMessage.message.isNullOrEmpty())
    } catch (e: JsonSyntaxException) {
        // 解析失败，说明不是有效的 JSON 格式
        Log.d("Ray", "Message cannot be parsed as MultiMessage: ${e.message}")
        false
    } catch (e: Exception) {
        // 其他异常也视为不可解析
        Log.d("Ray", "Error parsing message as MultiMessage: ${e.message}")
        false
    }
}

/**
 * 判断消息内容是否可以解析为 MultiMessage
 * @return 如果可以解析为 MultiMessage 返回 true，否则返回 false
 */
fun ChatActivity.sharedMessagesJsonToMultiMessage(): MultiMessage {
    if (sharedMessagesJson.isNullOrBlank()) {
        return MultiMessage()
    }

    // 快速检查：JSON 对象应该以 { 开始
    val trimmedContent = sharedMessagesJson!!.trim()
    if (!trimmedContent.startsWith("{")) {
        return MultiMessage()
    }

    return try {
        // 尝试使用 Gson 解析为 MultiMessage
        val gson = Gson()
        return gson.fromJson(trimmedContent, MultiMessage::class.java)
    } catch (e: JsonSyntaxException) {
        // 解析失败，说明不是有效的 JSON 格式
        Log.d("Ray", "Message MultiMessage: ${e.message}")
        MultiMessage()
    } catch (e: Exception) {
        // 其他异常也视为不可解析
        Log.d("Ray", "Error parsing message as MultiMessage: ${e.message}")
        MultiMessage()
    }
}
