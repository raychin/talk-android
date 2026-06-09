/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2023 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.signaling

import android.util.Log
import com.nextcloud.talk.signaling.SignalingMessageReceiver.ConversationMessageListener

internal class ConversationMessageNotifier {
    private val conversationMessageListeners: MutableSet<ConversationMessageListener> = LinkedHashSet()

    companion object {
        private const val TAG = "RayMessage"
    }

    @Synchronized
    fun addListener(listener: ConversationMessageListener?) {
        requireNotNull(listener) { "conversationMessageListener can not be null" }
        conversationMessageListeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: ConversationMessageListener) {
        conversationMessageListeners.remove(listener)
    }

    @Synchronized
    fun notifyStartTyping(userId: String?, sessionId: String?) {
        for (listener in ArrayList(conversationMessageListeners)) {
            listener.onStartTyping(userId, sessionId)
        }
    }

    fun notifyStopTyping(userId: String?, sessionId: String?) {
        for (listener in ArrayList(conversationMessageListeners)) {
            listener.onStopTyping(userId, sessionId)
        }
    }

    /**
     * 方案3：通知监听器有新消息信号到达（通过 WebSocket）
     * 触发 ChatActivity 执行一次性增量同步
     */
    fun notifyNewMessageSignal(type: String) {
        Log.d(TAG, "notifyNewMessageSignal: signaling type=$type, notifying ${conversationMessageListeners.size} listeners")
        for (listener in ArrayList(conversationMessageListeners)) {
            listener.onNewMessageSignal(type)
        }
    }
}
