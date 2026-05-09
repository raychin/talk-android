/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Daniel Calviño Sánchez <danxuliu@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.call

/**
 * Helper class to send the local participant state to the other participants in the call when an MCU is not used.
 *
 *
 * Sending the state when it changes is handled by the base class; this subclass only handles sending the initial
 * state when a remote participant is added.
 *
 *
 * The state is sent when a connection with another participant is first established (which implicitly broadcasts the
 * initial state when the local participant joins the call, as a connection is established with all the remote
 * participants). Note that, as long as that participant stays in the call, the initial state is not sent again, even
 * after a temporary disconnection; data channels use a reliable transport by default, so even if the state changes
 * while the connection is temporarily interrupted the normal state update messages should be received by the other
 * participant once the connection is restored.
 *
 *
 * Nevertheless, in case of a failed connection and an ICE restart it is unclear whether the data channel messages
 * would be received or not (as the data channel transport may be the one that failed and needs to be restarted).
 * However, the state (except the speaking state) is also sent through signaling messages, which need to be
 * explicitly fetched from the internal signaling server, so even in case of a failed connection they will be
 * eventually received once the remote participant connects again.
 */
import com.nextcloud.talk.activities.ParticipantUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection.IceConnectionState
import java.util.concurrent.ConcurrentHashMap

class LocalStateBroadcasterNoMcu(
    private val localCallParticipantModel: LocalCallParticipantModel,
    private val messageSender: MessageSenderNoMcu,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
) : LocalStateBroadcaster(localCallParticipantModel, messageSender) {

    // Map sessionId -> observer wrapper (Flow collector job)
    private val iceConnectionStateObservers = ConcurrentHashMap<String, IceConnectionStateObserver>()

    private inner class IceConnectionStateObserver(val uiState: ParticipantUiState) {
        private var job: Job? = null

        init {
            // 任选其一
            handleStateChange(uiState)
            // 注意：本方案不需要notifyPeerConnectionReady
            // startStateSendingWithRetry()
        }

        /**
         * 使用渐进延迟重试机制发送本地初始状态到远端参与者。
         *
         * 重试时间点：0ms, 300ms, 600ms, 1000ms, 2000, 4000ms
         * 总最大等待：~7900ms
         *
         * 正常情况下 PCW 在 addCallParticipant 后很快就会通过
         * getOrCreatePeerConnectionWrapperForSessionIdAndType 创建完成，
         * 通常在第 1-2 次尝试即可成功发送。
         */
        private fun startStateSendingWithRetry() {
            val sessionKey = uiState.sessionKey ?: return

            job = scope.launch {
                // 渐进式重试延迟（毫秒）：首次立即尝试，后续逐步加大间隔
                val retryDelays = longArrayOf(0L, 300L, 600L, 1000L, 2000L, 4000L)

                for ((index, delayMs) in retryDelays.withIndex()) {
                    // 非首次需要等待
                    if (delayMs > 0L) {
                        delay(delayMs)
                    }

                    // 检查参与者是否已离开（避免无效重试）
                    if (!iceConnectionStateObservers.containsKey(sessionKey)) {
                        // Log.d(TAG, "Participant $sessionKey removed, stopping retry")
                        return@launch
                    }

                    try {
                        sendState(sessionKey)
                        // Log.d(TAG, "Initial state sent successfully to $sessionKey (attempt ${index + 1})")
                        // 发送成功，停止重试并清理自身
                        remove()
                        return@launch
                    } catch (e: Exception) {
                        // Log.w(
                        //     TAG,
                        //     "Failed to send initial state to $sessionKey " +
                        //         "(attempt ${index + 1}/${retryDelays.size}): ${e.message}"
                        // )
                    }
                }

                // Log.w(
                //     TAG,
                //     "Exhausted all retries ($${retryDelays.size} attempts) sending initial state to $sessionKey"
                // )
            }
        }

        private fun handleStateChange(uiState: ParticipantUiState) {
            // Determine ICE connection state
            val iceState = if (uiState.isConnected) IceConnectionState.CONNECTED else IceConnectionState.NEW

            if (iceState == IceConnectionState.CONNECTED) {
                remove()
                sendState(uiState.sessionKey)
            }
        }

        fun remove() {
            job?.cancel()
            iceConnectionStateObservers.remove(uiState.sessionKey)
        }
    }

    override fun handleCallParticipantAdded(uiState: ParticipantUiState) {
        uiState.sessionKey?.let {
            iceConnectionStateObservers[it]?.remove()

            iceConnectionStateObservers[it] =
                IceConnectionStateObserver(uiState)
        }
    }

    override fun handleCallParticipantRemoved(sessionId: String) {
        iceConnectionStateObservers[sessionId]?.remove()
    }

    override fun destroy() {
        super.destroy()
        // Cancel all collectors safely
        val observersCopy = iceConnectionStateObservers.values.toList()
        for (observer in observersCopy) {
            observer.remove()
        }
    }

    private fun sendState(sessionKey: String?) {
        messageSender.send(getDataChannelMessageForAudioState(), sessionKey)
        messageSender.send(getDataChannelMessageForSpeakingState(), sessionKey)
        messageSender.send(getDataChannelMessageForVideoState(), sessionKey)

        messageSender.send(getSignalingMessageForAudioState(), sessionKey)
        messageSender.send(getSignalingMessageForVideoState(), sessionKey)
    }

    // LocalStateBroadcasterNoMcu.kt 新增
    fun notifyPeerConnectionReady(sessionId: String?) {
        sessionId ?: return
        // 延迟一小段时间确保 PCW 已加入列表后再发送
        scope.launch {
            delay(500)  // 等待 PCW 注册完成
            sendState(sessionId)  // 重试发送当前状态
        }
    }
}
