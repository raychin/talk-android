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
import android.util.Log
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
            handleStateChange(uiState)
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

    private val sendStateJobs = ConcurrentHashMap<String, Job>()
    override fun handleCallParticipantAdded(uiState: ParticipantUiState) {

        uiState.sessionKey?.let { sessionKey ->
            // 取消之前的重发任务
            sendStateJobs[sessionKey]?.cancel()

            // 启动新的重发任务（与 MCU 模式类似的定时重发策略）
            sendStateJobs[sessionKey] = scope.launch {
                // Progressive delays: send immediately, then retry several times
                // This covers:
                // - t=0ms:    ICE might already be connected (fast path)
                // - t=500ms:  ICE likely connecting
                // - t=1500ms: ICE should be connected in most cases
                // - t=3500ms: Fallback for slow networks
                // - t=7500ms: Ensure delivery even with delays
                // - t=15500s: Final safety net
                // 总时长15.5s → 47.5s
                val delays = longArrayOf(0L, 500L, 1000L, 2000L, 4000L, 8000L, 12000L, 20000L)

                for ((index, delayMs) in delays.withIndex()) {
                    if (delayMs > 0L) delay(delayMs)

                    if (!sendStateJobs.containsKey(sessionKey)) return@launch

                    val isIceConnected = uiState.isConnected

                    try {
                        sendState(sessionKey)
                        Log.d(
                            TAG,
                            "State sent to $sessionKey (#${index + 1}/${delays.size}), " +
                                "audio=${localCallParticipantModel.isAudioEnabled()}, " +
                                "video=${localCallParticipantModel.isVideoEnabled()}"
                        )

                        // // ICE 已连接时，首次发送成功即可退出
                        // if (isIceConnected) {
                        //     Log.d(TAG, "ICE connected, stop retrying for $sessionKey")
                        //     sendStateJobs.remove(sessionKey)
                        //     break
                        // }

                        // if (isIceConnected) {
                        //     // ICE 已连接，发送完整状态（DataChannel + Signaling）
                        //     sendState(sessionKey)
                        //     Log.d(TAG, "State sent to $sessionKey (#${index + 1}), ICE connected")
                        // } else {
                        //     // ICE 未连接，仅发送 Signaling 消息（WebSocket，无需 PCW）
                        //     messageSender.send(getSignalingMessageForAudioState(), sessionKey)
                        //     messageSender.send(getSignalingMessageForVideoState(), sessionKey)
                        //     Log.d(TAG, "Signaling-only sent to $sessionKey (#${index + 1}), ICE not connected")
                        // }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send state to $sessionKey (#${index + 1})", e)
                    }
                }

                sendStateJobs.remove(sessionKey)
                Log.d(TAG, "Initial state sending completed for $sessionKey")
            }
        }
    }

    override fun handleCallParticipantRemoved(sessionId: String) {
        sendStateJobs[sessionId]?.cancel()
        sendStateJobs.remove(sessionId)
    }

    override fun destroy() {
        super.destroy()
        // Cancel all collectors safely
        sendStateJobs.values.forEach { it.cancel() }
        sendStateJobs.clear()
    }

    private fun sendState(sessionKey: String?) {
        // Data channel 消息（PCW 不存在时会被队列缓存，就绪后自动发送）
        messageSender.send(getDataChannelMessageForAudioState(), sessionKey)
        messageSender.send(getDataChannelMessageForSpeakingState(), sessionKey)
        messageSender.send(getDataChannelMessageForVideoState(), sessionKey)

        Log.d(
            TAG,
            "State sent to $sessionKey, " +
                "audio=${getDataChannelMessageForAudioState()}, " +
                "speaking=${getDataChannelMessageForSpeakingState()}, " +
                "video=${getDataChannelMessageForSpeakingState()}"
        )

        // Signaling 消息（通过 WebSocket 发送，无需 PCW）
        messageSender.send(getSignalingMessageForAudioState(), sessionKey)
        messageSender.send(getSignalingMessageForVideoState(), sessionKey)
    }

    companion object {
        private val TAG = LocalStateBroadcasterNoMcu::class.java.simpleName
    }
}
