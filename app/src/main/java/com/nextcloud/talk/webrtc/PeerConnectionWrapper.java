/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Tim Krüger <t@timkrueger.me>
 * SPDX-FileCopyrightText: 2017 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.webrtc;

import android.util.Log;

import com.bluelinelabs.logansquare.LoganSquare;
import com.nextcloud.talk.models.json.signaling.DataChannelMessage;
import com.nextcloud.talk.models.json.signaling.NCIceCandidate;
import com.nextcloud.talk.models.json.signaling.NCMessagePayload;
import com.nextcloud.talk.models.json.signaling.NCSignalingMessage;
import com.nextcloud.talk.signaling.SignalingMessageReceiver;
import com.nextcloud.talk.signaling.SignalingMessageSender;

import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import androidx.annotation.Nullable;

public class PeerConnectionWrapper {

    private static final String TAG = PeerConnectionWrapper.class.getCanonicalName();

    private final SignalingMessageReceiver signalingMessageReceiver;
    private final WebRtcMessageListener webRtcMessageListener = new WebRtcMessageListener();

    private final SignalingMessageSender signalingMessageSender;

    private final DataChannelMessageNotifier dataChannelMessageNotifier = new DataChannelMessageNotifier();

    private final PeerConnectionNotifier peerConnectionNotifier = new PeerConnectionNotifier();

    private List<IceCandidate> iceCandidates = new ArrayList<>();
    private PeerConnection peerConnection;
    private String sessionId;
    private final MediaConstraints mediaConstraints;
    private final Map<String, DataChannel> dataChannels = new HashMap<>();
    // fix: 存储DataChannelObserver，挂断电话清空，避免异常 by ray on 2026/03/25
    private final Map<String, DataChannelObserver> dataChannelObservers = new HashMap<>();
    private final List<DataChannelMessage> pendingDataChannelMessages = new ArrayList<>();
    private final SdpObserver sdpObserver;

    private final boolean isMCUPublisher;
    private final String videoStreamType;

    // It is assumed that there will be at most one remote stream at each time.
    private MediaStream stream;

    /**
     * Listener for data channel messages.
     * <p>
     * Messages might have been received on any data channel, independently of its label or whether it was open by the
     * local or the remote peer.
     * <p>
     * The messages are bound to a specific peer connection, so each listener is expected to handle messages only for
     * a single peer connection.
     * <p>
     * All methods are called on the so called "signaling" thread of WebRTC, which is an internal thread created by the
     * WebRTC library and NOT the same thread where signaling messages are received.
     */
    public interface DataChannelMessageListener {
        void onAudioOn();
        void onAudioOff();
        void onVideoOn();
        void onVideoOff();
        void onNickChanged(String nick);
    }

    /**
     * Observer for changes on the peer connection.
     * <p>
     * The changes are bound to a specific peer connection, so each observer is expected to handle messages only for
     * a single peer connection.
     * <p>
     * All methods are called on the so called "signaling" thread of WebRTC, which is an internal thread created by the
     * WebRTC library and NOT the same thread where signaling messages are received.
     */
    public interface PeerConnectionObserver {
        void onStreamAdded(MediaStream mediaStream);
        void onStreamRemoved(MediaStream mediaStream);
        void onIceConnectionStateChanged(PeerConnection.IceConnectionState iceConnectionState);
    }

    public PeerConnectionWrapper(PeerConnectionFactory peerConnectionFactory,
                                 List<PeerConnection.IceServer> iceServerList,
                                 MediaConstraints mediaConstraints,
                                 String sessionId, String localSession, @Nullable MediaStream localStream,
                                 boolean isMCUPublisher, boolean hasMCU, String videoStreamType,
                                 SignalingMessageReceiver signalingMessageReceiver,
                                 SignalingMessageSender signalingMessageSender) {
        this.videoStreamType = videoStreamType;

        this.sessionId = sessionId;
        this.mediaConstraints = mediaConstraints;

        sdpObserver = new SdpObserver();
        boolean hasInitiated = sessionId.compareTo(localSession) < 0;
        this.isMCUPublisher = isMCUPublisher;

        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(iceServerList);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        // 启用持续 ICE 候选收集，提高在 NAT 环境下的连接成功率，每当网络接口变化，WebRTC 会重新收集并回调 onIceCandidate()
        configuration.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = peerConnectionFactory.createPeerConnection(configuration, new InitialPeerConnectionObserver());

        this.signalingMessageReceiver = signalingMessageReceiver;
        this.signalingMessageReceiver.addListener(webRtcMessageListener, sessionId, videoStreamType);

        this.signalingMessageSender = signalingMessageSender;

        if (peerConnection != null) {
            if (localStream != null) {
                List<String> localStreamIds = Collections.singletonList(localStream.getId());
                for(AudioTrack track : localStream.audioTracks) {
                    peerConnection.addTrack(track, localStreamIds);
                }
                for(VideoTrack track : localStream.videoTracks) {
                    peerConnection.addTrack(track, localStreamIds);
                }
            }

            if (hasMCU || hasInitiated) {
                DataChannel.Init init = new DataChannel.Init();
                init.negotiated = false;

                DataChannel statusDataChannel = peerConnection.createDataChannel("status", init);
                DataChannelObserver ob = new DataChannelObserver(statusDataChannel);
                dataChannelObservers.put("status", ob);
                statusDataChannel.registerObserver(ob);
                dataChannels.put("status", statusDataChannel);

                if (isMCUPublisher) {
                    peerConnection.createOffer(sdpObserver, mediaConstraints);
                } else if (hasMCU && "video".equals(this.videoStreamType)) {
                    // If the connection type is "screen" the client sharing the screen will send an
                    // offer; offers should be requested only for videos.
                    // "to" property is not actually needed in the "requestoffer" signaling message, but it is used to
                    // set the recipient session ID in the assembled call message.
                    NCSignalingMessage ncSignalingMessage = createBaseSignalingMessage("requestoffer");
                    signalingMessageSender.send(ncSignalingMessage);
                } else if (!hasMCU && hasInitiated && "video".equals(this.videoStreamType)) {
                    // If the connection type is "screen" the client sharing the screen will send an
                    // offer; offers should be created only for videos.
                    peerConnection.createOffer(sdpObserver, mediaConstraints);
                }
            }
        }
    }

    public void raiseHand(Boolean raise) {
        NCMessagePayload ncMessagePayload = new NCMessagePayload();
        ncMessagePayload.setState(raise);
        ncMessagePayload.setTimestamp(System.currentTimeMillis());

        NCSignalingMessage ncSignalingMessage = new NCSignalingMessage();
        ncSignalingMessage.setTo(sessionId);
        ncSignalingMessage.setType("raiseHand");
        ncSignalingMessage.setPayload(ncMessagePayload);
        ncSignalingMessage.setRoomType(videoStreamType);

        signalingMessageSender.send(ncSignalingMessage);
    }

    public void sendReaction(String emoji) {
        NCMessagePayload ncMessagePayload = new NCMessagePayload();
        ncMessagePayload.setReaction(emoji);
        ncMessagePayload.setTimestamp(System.currentTimeMillis());

        NCSignalingMessage ncSignalingMessage = new NCSignalingMessage();
        ncSignalingMessage.setTo(sessionId);
        ncSignalingMessage.setType("reaction");
        ncSignalingMessage.setPayload(ncMessagePayload);
        ncSignalingMessage.setRoomType(videoStreamType);

        signalingMessageSender.send(ncSignalingMessage);
    }

    /**
     * Adds a listener for data channel messages.
     * <p>
     * A listener is expected to be added only once. If the same listener is added again it will be notified just once.
     *
     * @param listener the DataChannelMessageListener
     */
    public void addListener(DataChannelMessageListener listener) {
        dataChannelMessageNotifier.addListener(listener);
    }

    public void removeListener(DataChannelMessageListener listener) {
        dataChannelMessageNotifier.removeListener(listener);
    }

    /**
     * Adds an observer for peer connection changes.
     * <p>
     * An observer is expected to be added only once. If the same observer is added again it will be notified just once.
     *
     * @param observer the PeerConnectionObserver
     */
    public void addObserver(PeerConnectionObserver observer) {
        peerConnectionNotifier.addObserver(observer);
    }

    public void removeObserver(PeerConnectionObserver observer) {
        peerConnectionNotifier.removeObserver(observer);
    }

    public String getVideoStreamType() {
        return videoStreamType;
    }

    public MediaStream getStream() {
        return stream;
    }

    public synchronized void removePeerConnection() {
        signalingMessageReceiver.removeListener(webRtcMessageListener);


        // 先标记所有DataChannelObserver为已处置
        for (Map.Entry<String, DataChannelObserver> entry : dataChannelObservers.entrySet()) {
            DataChannelObserver observer = entry.getValue();
            observer.markAsDisposed();
        }

        for (DataChannel dataChannel: dataChannels.values()) {
            Log.d(TAG, "Disposed DataChannel " + dataChannel.label());

            dataChannel.dispose();
        }
        dataChannels.clear();

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
            Log.d(TAG, "Disposed PeerConnection");
        } else {
            Log.d(TAG, "PeerConnection is null.");
        }
    }

    private void drainIceCandidates() {

        if (peerConnection != null) {
            for (IceCandidate iceCandidate : iceCandidates) {
                peerConnection.addIceCandidate(iceCandidate);
            }

            iceCandidates = new ArrayList<>();
        }
    }

    private void addCandidate(IceCandidate iceCandidate) {
        if (peerConnection != null && peerConnection.getRemoteDescription() != null) {
            peerConnection.addIceCandidate(iceCandidate);
        } else {
            iceCandidates.add(iceCandidate);
        }
    }

    /**
     * Sends a data channel message.
     * <p>
     * Data channel messages are always sent on the "status" data channel locally opened. However, if Janus is used,
     * messages can be sent only on publisher connections, even if subscriber connections have a "status" data channel;
     * messages sent on subscriber connections will be simply ignored. Moreover, even if the message is sent on the
     * "status" data channel subscriber connections will receive it on a data channel with a different label, as
     * Janus opens its own data channel on subscriber connections and "multiplexes" all the received data channel
     * messages on it, independently of on which data channel they were originally sent.
     * <p>
     * Data channel messages can be sent at any time; if the "status" data channel is not open yet the messages will be
     * queued and sent once it is opened. Nevertheless, if Janus is used, it is not guaranteed that the messages will
     * be received by other participants, as it is only known when the data channel of the publisher was opened, but
     * not if the data channel of the subscribers was. However, in general this should be a concern only during the
     * first seconds after a participant joins; after some time the subscriber connections should be established and
     * their data channels open.
     *
     * @param dataChannelMessage the message to send
     */
    public synchronized void send(DataChannelMessage dataChannelMessage) {
        if (dataChannelMessage == null) {
            return;
        }

        DataChannel statusDataChannel = dataChannels.get("status");
        if (statusDataChannel == null || statusDataChannel.state() != DataChannel.State.OPEN ||
            !pendingDataChannelMessages.isEmpty()) {
            Log.d(TAG, "Queuing data channel message (" + dataChannelMessage + ") " + sessionId);

            pendingDataChannelMessages.add(dataChannelMessage);

            return;
        }

        sendWithoutQueuing(statusDataChannel, dataChannelMessage);
    }

    private void sendWithoutQueuing(DataChannel statusDataChannel, DataChannelMessage dataChannelMessage) {
        try {
            Log.d(TAG, "Sending data channel message (" + dataChannelMessage + ") " + sessionId);

            ByteBuffer buffer = ByteBuffer.wrap(LoganSquare.serialize(dataChannelMessage).getBytes());
            statusDataChannel.send(new DataChannel.Buffer(buffer, false));
        } catch (Exception e) {
            Log.w(TAG, "Failed to send data channel message");
        }
    }

    public PeerConnection getPeerConnection() {
        return peerConnection;
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isMCUPublisher() {
        return isMCUPublisher;
    }

    private boolean shouldNotReceiveVideo() {
        for (MediaConstraints.KeyValuePair keyValuePair : mediaConstraints.mandatory) {
            if ("OfferToReceiveVideo".equals(keyValuePair.getKey())) {
                return !Boolean.parseBoolean(keyValuePair.getValue());
            }
        }
        return false;
    }

    private NCSignalingMessage createBaseSignalingMessage(String type) {
        NCSignalingMessage ncSignalingMessage = new NCSignalingMessage();
        ncSignalingMessage.setTo(sessionId);
        ncSignalingMessage.setRoomType(videoStreamType);
        ncSignalingMessage.setType(type);

        return ncSignalingMessage;
    }

    private class WebRtcMessageListener implements SignalingMessageReceiver.WebRtcMessageListener {

        public void onOffer(String sdp, String nick) {
            onOfferOrAnswer("offer", sdp);
        }

        public void onAnswer(String sdp, String nick) {
            onOfferOrAnswer("answer", sdp);
        }

        private void onOfferOrAnswer(String type, String sdp) {
            SessionDescription sessionDescriptionWithPreferredCodec;

            boolean isAudio = false;
            String sessionDescriptionStringWithPreferredCodec = WebRTCUtils.preferCodec(sdp, "H264", isAudio);

            sessionDescriptionWithPreferredCodec = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(type),
                sessionDescriptionStringWithPreferredCodec);

            if (getPeerConnection() != null) {
                getPeerConnection().setRemoteDescription(sdpObserver, sessionDescriptionWithPreferredCodec);
            }
        }

        public void onCandidate(String sdpMid, int sdpMLineIndex, String sdp) {
            IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
            addCandidate(iceCandidate);
        }

        public void onEndOfCandidates() {
            drainIceCandidates();
        }
    }

    private class DataChannelObserver implements DataChannel.Observer {

        private final DataChannel dataChannel;
        private final String dataChannelLabel;
        private volatile boolean isDisposed = false;

        public DataChannelObserver(DataChannel dataChannel) {
            this.dataChannel = Objects.requireNonNull(dataChannel);
            this.dataChannelLabel = dataChannel.label();
        }

        @Override
        public void onBufferedAmountChange(long l) {
            // Check if disposed before accessing
            if (isDisposed) {
                return;
            }

        }

        @Override
        public void onStateChange() {
            // 先检查是否已处置
            if (isDisposed) {
                return;
            }
            synchronized (PeerConnectionWrapper.this) {
                // The PeerConnection could have been removed in parallel even with the synchronization (as just after
                // "onStateChange" was called "removePeerConnection" could have acquired the lock).
                // 再次检查，防止竞态条件
                if (isDisposed || peerConnection == null) {
                    return;
                }

                try {
                    if (dataChannel.state() == DataChannel.State.OPEN && "status".equals(dataChannelLabel)) {
                        for (DataChannelMessage dataChannelMessage : pendingDataChannelMessages) {
                            sendWithoutQueuing(dataChannel, dataChannelMessage);
                        }
                        pendingDataChannelMessages.clear();
                    }
                } catch (IllegalStateException e) {
                    // DataChannel was disposed during state check
                    Log.w(TAG, "DataChannel was disposed during state change", e);
                    isDisposed = true;
                }
            }
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            // 先检查是否已处置
            if (isDisposed) {
                return;
            }
            synchronized (PeerConnectionWrapper.this) {
                // It is assumed that, even if its data channel was disposed, its buffers can be used while there is
                // a reference to them, so it would not be necessary to check this from a thread-safety point of view.
                // Nevertheless, if the remote peer connection was removed it would not make sense to notify the
                // listeners anyway.
                // 再次检查，防止竞态条件
                if (isDisposed || peerConnection == null) {
                    return;
                }
            }

            if (buffer.binary) {
                Log.d(TAG, "Received binary data channel message over " + dataChannelLabel + " " + sessionId);
                return;
            }

            ByteBuffer data = buffer.data;
            final byte[] bytes = new byte[data.capacity()];
            data.get(bytes);
            String strData = new String(bytes);
            Log.d(TAG, "Received data channel message (" + strData + ") over " + dataChannelLabel + " " + sessionId);

            DataChannelMessage dataChannelMessage;
            try {
                dataChannelMessage = LoganSquare.parse(strData, DataChannelMessage.class);
            } catch (IOException e) {
                Log.d(TAG, "Failed to parse data channel message");

                return;
            }

            if ("nickChanged".equals(dataChannelMessage.getType())) {
                String nick = null;
                if (dataChannelMessage.getPayload() instanceof String) {
                    nick = (String) dataChannelMessage.getPayload();
                } else if (dataChannelMessage.getPayload() instanceof Map) {
                    Map<String, String> payloadMap = (Map<String, String>) dataChannelMessage.getPayload();
                    nick = payloadMap.get("name");
                }

                if (nick != null) {
                    dataChannelMessageNotifier.notifyNickChanged(nick);
                }

                return;
            }

            if ("audioOn".equals(dataChannelMessage.getType())) {
                dataChannelMessageNotifier.notifyAudioOn();

                return;
            }

            if ("audioOff".equals(dataChannelMessage.getType())) {
                dataChannelMessageNotifier.notifyAudioOff();

                return;
            }

            if ("videoOn".equals(dataChannelMessage.getType())) {
                dataChannelMessageNotifier.notifyVideoOn();

                return;
            }

            if ("videoOff".equals(dataChannelMessage.getType())) {
                dataChannelMessageNotifier.notifyVideoOff();

                return;
            }
        }

        // 提供外部标记为已处置的方法
        public void markAsDisposed() {
            this.isDisposed = true;
        }
    }

    private class InitialPeerConnectionObserver implements PeerConnection.Observer {

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {

            Log.d("iceConnectionChangeTo: ", iceConnectionState.name() + " over " + peerConnection.hashCode() + " " + sessionId);

            peerConnectionNotifier.notifyIceConnectionStateChanged(iceConnectionState);
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {

        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            /*
             * 1.host时，不发送信令
             * 2.提高relay的优先级
             */
            // 解析ICE candidate类型
            String candidate = iceCandidate.sdp;
            String type = "";
            String[] parts = candidate.split(" ");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("typ")) {
                    type = parts[i + 1];
                    break;
                }
            }

//            // 过滤逻辑
//            if (type.equals("host")) {
//                // 丢弃host类型的candidate
//                return;
//            }

            // 调整优先级
            String modifiedCandidate = candidate;
            if (type.equals("relay")) {
                // 提升relay类型的优先级
                // 找到优先级字段并修改
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0 && parts[i - 1].equals("UDP")) {
                        // 优先级字段在UDP后面
                        try {
                            int priority = Integer.parseInt(parts[i]);
                            // 提升优先级到最高
                            parts[i] = String.valueOf(2147483647); // 最大32位整数
                            modifiedCandidate = String.join(" ", parts);
                            break;
                        } catch (NumberFormatException e) {
                            // 忽略解析错误
                        }
                    }
                }
            } else if (type.equals("srflx")) {
                // 降低srflx类型的优先级
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0 && parts[i - 1].equals("UDP")) {
                        // 优先级字段在UDP后面
                        try {
                            int priority = Integer.parseInt(parts[i]);
                            // 降低优先级
                            parts[i] = String.valueOf(priority / 2);
                            modifiedCandidate = String.join(" ", parts);
                            break;
                        } catch (NumberFormatException e) {
                            // 忽略解析错误
                        }
                    }
                }
            } else if (type.equals("host")) {
                // 降低 host 类型的优先级，但不丢弃
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0 && parts[i - 1].equals("UDP")) {
                        try {
                            int priority = Integer.parseInt(parts[i]);
                            // 将 host candidate 优先级降低到最低
                            parts[i] = String.valueOf(Math.max(1, priority / 10));
                            modifiedCandidate = String.join(" ", parts);
                            break;
                        } catch (NumberFormatException e) {
                            // 忽略解析错误
                        }
                    }
                }
            }

            NCSignalingMessage ncSignalingMessage = createBaseSignalingMessage("candidate");
            NCMessagePayload ncMessagePayload = new NCMessagePayload();
            ncMessagePayload.setType("candidate");

            NCIceCandidate ncIceCandidate = new NCIceCandidate();
            ncIceCandidate.setSdpMid(iceCandidate.sdpMid);
            ncIceCandidate.setSdpMLineIndex(iceCandidate.sdpMLineIndex);
//            ncIceCandidate.setCandidate(iceCandidate.sdp);
            ncIceCandidate.setCandidate(modifiedCandidate);
            ncMessagePayload.setIceCandidate(ncIceCandidate);

            ncSignalingMessage.setPayload(ncMessagePayload);

            signalingMessageSender.send(ncSignalingMessage);
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            stream = mediaStream;

            peerConnectionNotifier.notifyStreamAdded(mediaStream);
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            stream = null;

            peerConnectionNotifier.notifyStreamRemoved(mediaStream);
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            synchronized (PeerConnectionWrapper.this) {
                // Another data channel with the same label, no matter if the same instance or a different one, should
                // not be added, but this is handled just in case.
                // Moreover, if it were possible that an already added data channel was added again there would be a
                // potential race condition with "removePeerConnection", even with the synchronization, as it would
                // be possible that "onDataChannel" was called, then "removePeerConnection" disposed the data
                // channel, and then "onDataChannel" continued in the synchronized statements and tried to get the
                // label, which would throw an exception due to the data channel having been disposed already.
                String dataChannelLabel;
                try {
                    dataChannelLabel = dataChannel.label();
                } catch (IllegalStateException e) {
                    // The data channel was disposed already, nothing to do.
                    return;
                }

                DataChannel oldDataChannel = dataChannels.get(dataChannelLabel);
                if (oldDataChannel == dataChannel) {
                    Log.w(TAG, "Data channel with label " + dataChannel.label() + " added again");

                    return;
                }

                if (oldDataChannel != null) {
                    Log.w(TAG, "Data channel with label " + dataChannel.label() + " exists");

                    oldDataChannel.dispose();
                }

                // If the peer connection was removed in parallel dispose the data channel instead of adding it.
                if (peerConnection == null) {
                    dataChannel.dispose();

                    return;
                }

                DataChannelObserver ob = new DataChannelObserver(dataChannel);
                dataChannelObservers.put(dataChannel.label(), ob);
                dataChannel.registerObserver(new DataChannelObserver(dataChannel));
                dataChannels.put(dataChannel.label(), dataChannel);
            }
        }

        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
        }
    }

    private class SdpObserver implements org.webrtc.SdpObserver {
        private static final String TAG = "SdpObserver";

        @Override
        public void onCreateFailure(String s) {
            Log.d(TAG, "SDPObserver createFailure: " + s + " over " + peerConnection.hashCode() + " " + sessionId);

        }

        @Override
        public void onSetFailure(String s) {
            Log.d(TAG,"SDPObserver setFailure: " + s + " over " + peerConnection.hashCode() + " " + sessionId);
        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            String type = sessionDescription.type.canonicalForm();

            NCSignalingMessage ncSignalingMessage = createBaseSignalingMessage(type);
            NCMessagePayload ncMessagePayload = new NCMessagePayload();
            ncMessagePayload.setType(type);

            SessionDescription sessionDescriptionWithPreferredCodec;
            String sessionDescriptionStringWithPreferredCodec = WebRTCUtils.preferCodec
                    (sessionDescription.description,
                            "H264", false);
            sessionDescriptionWithPreferredCodec = new SessionDescription(
                    sessionDescription.type,
                    sessionDescriptionStringWithPreferredCodec);

            ncMessagePayload.setSdp(sessionDescriptionWithPreferredCodec.description);

            ncSignalingMessage.setPayload(ncMessagePayload);

            signalingMessageSender.send(ncSignalingMessage);

            if (peerConnection != null) {
                peerConnection.setLocalDescription(sdpObserver, sessionDescriptionWithPreferredCodec);
            }
        }

        @Override
        public void onSetSuccess() {
            if (peerConnection != null) {
                if (peerConnection.getLocalDescription() == null) {

                    if (shouldNotReceiveVideo()) {
                        for (RtpTransceiver t : peerConnection.getTransceivers()) {
                            if (t.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                                t.stop();
                            }
                        }
                        Log.d(TAG, "Stop all Transceivers for MEDIA_TYPE_VIDEO.");
                    }

                    /*
                        Passed 'MediaConstraints' will be ignored by WebRTC when using UNIFIED PLAN.
                        See for details: https://docs.google.com/document/d/1PPHWV6108znP1tk_rkCnyagH9FK205hHeE9k5mhUzOg/edit#heading=h.9dcmkavg608r
                     */
                    peerConnection.createAnswer(sdpObserver, new MediaConstraints());

                }

                if (peerConnection.getRemoteDescription() != null) {
                    drainIceCandidates();
                }
            }
        }
    }
}
