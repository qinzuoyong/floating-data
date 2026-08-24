package com.example.batteryfloat.p2p

import com.google.gson.JsonObject

/**
 * 家人位置共享信令协议（纯信令中继，无 WebRTC）
 *
 * 与服务器 /opt/family-signal/server.js 对齐，全部消息为 JSON 对象。
 * 房间 = 6 位家庭码；uid = 设备唯一标识（生成一次持久化）；name = 备注名。
 */
object SignalTypes {
    /** 客户端 → 服务器：注册/上线 */
    const val REGISTER = "register"
    /** 服务器 → 客户端：注册回执（携带同房在线成员） */
    const val REGISTERED = "registered"
    /** 服务器 → 客户端：成员上下线广播（全房间） */
    const val PRESENCE = "presence"
    /** 请求某成员位置（中继） */
    const val LOC_REQ = "loc-req"
    /** 位置应答（中继给请求方） */
    const val LOC_RES = "loc-res"
    /** 心跳 */
    const val PING = "ping"
    const val PONG = "pong"
    /** 服务器错误回执 */
    const val ERROR = "error"
}

/**
 * 信令消息统一信封（Gson 反序列化目标；字段名与服务器协议一致，禁止改名）
 * payload 为自由 JSON，按 type 解释（见 [LocationPayload]）。
 */
data class SignalMessage(
    val type: String,
    val from: String? = null,
    val to: String? = null,
    val name: String? = null,
    val room: String? = null,
    val uid: String? = null,
    val online: Boolean? = null,
    val peers: List<PeerInfo>? = null,
    val payload: JsonObject? = null,
    val code: String? = null,
    val message: String? = null
)

/** 房间内成员摘要（注册回执与 presence 使用） */
data class PeerInfo(
    val uid: String,
    val name: String,
    val online: Boolean
)

/** loc-res 位置载荷（payload 字段，与地图/存储共用） */
data class LocationPayload(
    val lat: Double,
    val lng: Double,
    val ts: Long,
    val accuracy: Float = 0f
)
