package com.example.batteryfloat.p2p

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.batteryfloat.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * WebRTC 直连通道管理（每成员一个 PeerConnection + "loc" DataChannel）
 *
 * 定位数据经 DataChannel 点对点传输（不经服务器）；信令（offer/answer/ice）
 * 走 [SignalClient] 中继。角色约定：uid 较小的一方为 offerer，避免 glare。
 * TURN 兜底：iceServers 同时配置 stun/turn（UDP + TCP transport）。
 */
class WebRtcP2pChannel(
    context: Context,
    private val scope: CoroutineScope,
    private val signal: SignalClient,
    private val myUid: String
) {

    private val gson = Gson()

    /** 收到成员经 DataChannel 推送的位置 */
    var onLocationUpdate: ((fromUid: String, loc: LocationPayload) -> Unit)? = null

    private val factory: PeerConnectionFactory

    /** uid → 对端连接状态 */
    private val peers = ConcurrentHashMap<String, PeerState>()

    init {
        check(isSupportedAbi()) { "WebRTC 不支持当前 ABI: " + Build.SUPPORTED_ABIS.joinToString() }
        synchronized(WebRtcP2pChannel::class.java) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                factoryInitialized = true
            }
        }
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private class PeerState(
        val pc: PeerConnection,
        var dc: DataChannel? = null
    )

    // ===== 事件入口（由 FamilyLocationService 转发信令消息触发） =====

    /** 成员上线：建立连接（uid 小者发起 offer） */
    fun onPeerOnline(peer: PeerInfo) {
        val uid = peer.uid
        if (uid == myUid) return
        if (peers.containsKey(uid)) return
        val pc = createPeerConnection(uid) ?: return
        peers[uid] = PeerState(pc)
        if (myUid < uid) {
            scheduleOffer(pc, uid)
        }
        Log.i(TAG, "peer online uid=" + uid + " role=" + if (myUid < uid) "offerer" else "answerer")
    }

    /** 成员下线：拆除连接 */
    fun onPeerOffline(uid: String) {
        disposePeer(uid)
    }

    /** 处理来自 [from] 的 WebRTC 信令（offer/answer/ice） */
    fun onRtcSignal(from: String, payload: RtcSignalPayload) {
        val state = peers[from]
        if (state == null) {
            // 先于 presence 到达的 offer：补建连接
            if (payload.kind == SignalTypes.RTC_OFFER) {
                val pc = createPeerConnection(from) ?: return
                peers[from] = PeerState(pc)
                handleOffer(from, payload, pc)
            }
            return
        }
        when (payload.kind) {
            SignalTypes.RTC_OFFER -> handleOffer(from, payload, state.pc)
            SignalTypes.RTC_ANSWER -> handleAnswer(payload, state.pc)
            SignalTypes.RTC_ICE -> handleIce(payload, state.pc)
        }
    }

    /** 向所有已连接成员广播我的位置（DataChannel 点对点） */
    fun broadcastLocation(loc: LocationPayload) {
        val json = gson.toJson(loc)
        for ((uid, state) in peers) {
            val dc = state.dc
            if (dc != null && dc.state() == DataChannel.State.OPEN) {
                val buffer = ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8))
                if (dc.send(DataChannel.Buffer(buffer, false))) {
                    Log.i(TAG, "loc sent over DC to " + uid)
                }
            }
        }
    }

    /** 拆除全部连接（服务停止时） */
    fun disposeAll() {
        for (uid in peers.keys) disposePeer(uid)
        runCatching { factory.dispose() }
    }

    // ===== 内部 =====

    private fun createPeerConnection(uid: String): PeerConnection? {
        val iceServers = buildList {
            add(PeerConnection.IceServer.builder("stun:47.94.212.176:3478").createIceServer())
            add(
                PeerConnection.IceServer.builder("turn:47.94.212.176:3478")
                    .setUsername(BuildConfig.TURN_USER)
                    .setPassword(BuildConfig.TURN_PASS)
                    .createIceServer()
            )
            add(
                PeerConnection.IceServer.builder("turn:47.94.212.176:3478?transport=tcp")
                    .setUsername(BuildConfig.TURN_USER)
                    .setPassword(BuildConfig.TURN_PASS)
                    .createIceServer()
            )
        }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = runCatching {
            factory.createPeerConnection(config, pcObserver(uid))
        }.getOrNull()
        if (pc == null) {
            Log.w(TAG, "createPeerConnection failed for " + uid)
        }
        return pc
    }

    private fun pcObserver(uid: String) = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) { /* ignore */ }

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            val payload = RtcSignalPayload(
                kind = SignalTypes.RTC_ICE,
                data = candidate.toString(),
                sdpMid = candidate.sdpMid,
                mlineIndex = candidate.sdpMLineIndex
            )
            signal.sendSignal(uid, payload)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) { /* ignore */ }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(TAG, "ice state uid=" + uid + " -> " + state)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) { /* ignore */ }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) { /* ignore */ }

        override fun onAddStream(stream: MediaStream?) { /* ignore（无媒体） */ }

        override fun onRemoveStream(stream: MediaStream?) { /* ignore */ }

        override fun onDataChannel(dataChannel: DataChannel?) {
            if (dataChannel == null) return
            val state = peers[uid] ?: return
            state.dc = dataChannel
            dataChannel.registerObserver(dcObserver(uid))
        }

        override fun onRenegotiationNeeded() { /* ignore（无媒体重协商） */ }
    }

    private fun dcObserver(uid: String) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) { /* ignore */ }

        override fun onStateChange() { /* ignore */ }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (!buffer.data.hasRemaining()) return
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val json = String(bytes, StandardCharsets.UTF_8)
            val loc = runCatching { gson.fromJson(json, LocationPayload::class.java) }.getOrNull()
            if (loc != null) {
                scope.launch { onLocationUpdate?.invoke(uid, loc) }
            }
        }
    }

    private fun scheduleOffer(pc: PeerConnection, uid: String) {
        val constraints = MediaConstraints()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(sdpObserver("set local offer"), desc)
                signal.sendSignal(
                    uid,
                    RtcSignalPayload(kind = SignalTypes.RTC_OFFER, data = desc.description)
                )
            }

            override fun onCreateFailure(error: String?) {
                Log.w(TAG, "createOffer failed: " + error)
            }

            override fun onSetSuccess() { /* ignore */ }

            override fun onSetFailure(error: String?) {
                Log.w(TAG, "setLocalDescription offer failed: " + error)
            }
        }, constraints)
    }

    private fun handleOffer(from: String, payload: RtcSignalPayload, pc: PeerConnection) {
        val data = payload.data ?: return
        pc.setRemoteDescription(
            sdpObserver("set remote offer"),
            SessionDescription(SessionDescription.Type.OFFER, data)
        )
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(sdpObserver("set local answer"), desc)
                signal.sendSignal(
                    from,
                    RtcSignalPayload(kind = SignalTypes.RTC_ANSWER, data = desc.description)
                )
            }

            override fun onCreateFailure(error: String?) {
                Log.w(TAG, "createAnswer failed: " + error)
            }

            override fun onSetSuccess() { /* ignore */ }

            override fun onSetFailure(error: String?) {
                Log.w(TAG, "setLocalDescription answer failed: " + error)
            }
        }, MediaConstraints())
    }

    private fun handleAnswer(payload: RtcSignalPayload, pc: PeerConnection) {
        val data = payload.data ?: return
        pc.setRemoteDescription(
            sdpObserver("set remote answer"),
            SessionDescription(SessionDescription.Type.ANSWER, data)
        )
    }

    private fun handleIce(payload: RtcSignalPayload, pc: PeerConnection) {
        val candidate = payload.data ?: return
        val idx = payload.mlineIndex ?: 0
        val mid = payload.sdpMid ?: "0"
        val ice = runCatching { IceCandidate(mid, idx, candidate) }.getOrNull() ?: return
        pc.addIceCandidate(ice)
    }

    private fun sdpObserver(tag: String) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) { /* ignore */ }
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, tag + " create failed: " + error)
        }
        override fun onSetSuccess() { /* ignore */ }
        override fun onSetFailure(error: String?) {
            Log.w(TAG, tag + " set failed: " + error)
        }
    }

    private fun disposePeer(uid: String) {
        val state = peers.remove(uid) ?: return
        runCatching { state.dc?.close() }
        runCatching { state.pc.close() }
        runCatching { state.pc.dispose() }
        Log.i(TAG, "peer disposed uid=" + uid)
    }

    companion object {
        const val TAG = "WebRtcP2pChannel"
        @Volatile var factoryInitialized = false

        /**
         * 当前 ABI 是否支持 WebRTC native 库。
         * webrtc-sdk 的 x86/x86_64 构建在部分虚拟化 CPU（如雷电模拟器）上会 SIGILL，
         * 此处对 x86 系列一律跳过 WebRTC，由信令中继兜底（loc-req/loc-res 仍全功能）。
         */
        fun isSupportedAbi(): Boolean {
            return Build.SUPPORTED_ABIS.none { it.startsWith("x86") }
        }
    }
}