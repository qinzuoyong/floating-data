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
    /** 服务器 → 客户端：注册回执（携带在线成员 peers 与全量名册 roster；创建人直接收到，加入者批准后收到） */
    const val REGISTERED = "registered"
    /** 服务器 → 客户端：成员上下线广播（全房间） */
    const val PRESENCE = "presence"
    /** 客户端 → 服务器：查询家庭码是否被占用 */
    const val ROOM_CHECK = "room-check"
    /** 服务器 → 客户端：家庭码占用结果 */
    const val ROOM_CHECK_RES = "room-check-res"
    /** 服务器 → 客户端：加入者等待创建人审核 */
    const val JOIN_PENDING = "join-pending"
    /** 服务器 → 客户端：通知创建人有新的加入申请 */
    const val JOIN_REQUEST = "join-request"
    /** 客户端 → 服务器：创建人批准加入 */
    const val JOIN_APPROVE = "join-approve"
    /** 客户端 → 服务器：创建人拒绝加入 */
    const val JOIN_REJECT = "join-reject"
    /** 服务器 → 客户端：加入申请被拒绝 */
    const val JOIN_REJECTED = "join-rejected"
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
    /** registered：全量成员名册（服务器 approved，含离线成员），用于客户端重建家人列表 */
    val roster: List<PeerInfo>? = null,
    val payload: JsonObject? = null,
    val code: String? = null,
    val message: String? = null,
    /** room-check-res：家庭码是否被占用 */
    val exists: Boolean? = null,
    /** room-check-res：创建人备注名 */
    val ownerName: String? = null
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
