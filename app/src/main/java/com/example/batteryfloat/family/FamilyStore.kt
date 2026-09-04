package com.example.batteryfloat.family

import android.content.Context
import android.content.SharedPreferences
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.p2p.LocationPayload
import com.example.batteryfloat.p2p.PeerInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 家人共享数据模型：一个成员（含本地备注与上次位置）
 *
 * @property uid 设备唯一标识（远端）
 * @property name 对方注册时上报的备注名
 * @property note 本地自定义备注（显示优先级高于 name）
 * @property online 是否在线（presence 驱动）
 * @property lastLat/lastLng/lastTs/lastAccuracy 上次收到的位置（"上次位置时间"需求）
 */
data class FamilyMember(
    val uid: String,
    val name: String = "",
    val note: String = "",
    val online: Boolean = false,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastTs: Long? = null,
    val lastAccuracy: Float? = null
) {
    /** 显示名：本地备注优先，否则远端注册名，兜底 uid 尾 4 位 */
    val displayName: String
        get() = if (note.isNotBlank()) note else if (name.isNotBlank()) name else "家人 " + uid.takeLast(4)
}

/**
 * 家人共享本地存储（SharedPreferences + StateFlow 双通道）
 *
 * 职责：我的 uid/备注名/家庭码/隐私开关 + 成员列表（上次位置）。
 * 任何写入同步更新 [members] StateFlow，Compose UI 直接 collect。
 */
class FamilyStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()
    private val membersType = object : TypeToken<MutableList<FamilyMember>>() {}.type

    private val _members = MutableStateFlow<Map<String, FamilyMember>>(emptyMap())
    /** 成员 uid → 成员（含上次位置/在线状态），UI 观察入口 */
    val members: StateFlow<Map<String, FamilyMember>> = _members.asStateFlow()

    /** 加入审核状态（加入者视角：NONE=正常/未申请，PENDING=等待创建人审核，REJECTED=被拒绝） */
    enum class JoinState { NONE, PENDING, REJECTED }

    private val _pendingJoins = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 待审核的加入申请（uid → 备注名），创建人视角，UI 观察入口 */
    val pendingJoins: StateFlow<Map<String, String>> = _pendingJoins.asStateFlow()

    private val _joinState = MutableStateFlow(JoinState.NONE)
    /** 我的加入审核状态（加入者视角），UI 观察入口 */
    val joinState: StateFlow<JoinState> = _joinState.asStateFlow()

    init {
        loadMembers()
    }

    companion object {
        @Volatile
        private var instance: FamilyStore? = null

        /**
         * 进程级单例：UI 与服务必须共享同一 StateFlow，
         * 否则服务收到的位置更新不会反映到 Compose 界面。
         */
        fun get(context: Context): FamilyStore =
            instance ?: synchronized(this) {
                instance ?: FamilyStore(context.applicationContext).also { instance = it }
            }
    }

    // ===== 我的身份 =====

    /** 我的设备 uid（首次生成并持久化） */
    fun myUid(): String {
        val cached = prefs.getString(PrefsKeys.FAMILY_MY_UID, null)
        if (cached != null) return cached
        val uid = "d-" + UUID.randomUUID().toString().replace("-", "").take(20)
        prefs.edit().putString(PrefsKeys.FAMILY_MY_UID, uid).apply()
        return uid
    }

    /** 我的备注名 */
    fun myName(): String = prefs.getString(PrefsKeys.FAMILY_MY_NAME, "") ?: ""

    fun setMyName(name: String) {
        prefs.edit().putString(PrefsKeys.FAMILY_MY_NAME, name.trim()).apply()
    }

    /** 6 位家庭码（空 = 未加入家庭） */
    fun familyCode(): String = prefs.getString(PrefsKeys.FAMILY_CODE, "") ?: ""

    fun setFamilyCode(code: String) {
        prefs.edit().putString(PrefsKeys.FAMILY_CODE, code.trim()).apply()
    }

    /** 隐私开关：是否响应家人的位置请求（默认允许） */
    fun allowLocReq(): Boolean = prefs.getBoolean(PrefsKeys.FAMILY_ALLOW_LOC_REQ, true)

    fun setAllowLocReq(allow: Boolean) {
        prefs.edit().putBoolean(PrefsKeys.FAMILY_ALLOW_LOC_REQ, allow).apply()
    }

    // ===== 成员管理 =====
    // 写方法均 @Synchronized:信令回调线程与 UI 线程并发读改写 _members/_pendingJoins,防更新丢失

    /** 记录/更新一个成员（注册回执或 presence 时调用，不动位置字段） */
    @Synchronized
    fun upsertMember(uid: String, name: String, online: Boolean) {
        val current = _members.value[uid]
        val member = (current ?: FamilyMember(uid = uid)).copy(
            name = name,
            online = online
        )
        _members.value = _members.value + (uid to member)
        persistMembers()
    }

    /**
     * 按服务器名册全量重建成员列表（家庭实际成员，含离线成员）
     *
     * 保留名册内成员的本地备注与上次位置；名册外成员（服务器已清除的测试残留）移除。
     *
     * @param roster registered 回执携带的全量名册
     */
    @Synchronized
    fun syncRoster(roster: List<PeerInfo>) {
        val newMap = LinkedHashMap<String, FamilyMember>()
        for (entry in roster) {
            val old = _members.value[entry.uid]
            newMap[entry.uid] = (old ?: FamilyMember(uid = entry.uid)).copy(
                name = entry.name,
                online = entry.online
            )
        }
        _members.value = newMap
        persistMembers()
    }

    /** presence 下线 */
    @Synchronized
    fun markOffline(uid: String) {
        val current = _members.value[uid] ?: return
        _members.value = _members.value + (uid to current.copy(online = false))
        persistMembers()
    }

    /** 收到位置应答：更新上次位置与时间戳 */
    @Synchronized
    fun updateLocation(uid: String, loc: LocationPayload) {
        val current = _members.value[uid] ?: FamilyMember(uid = uid)
        val member = current.copy(
            lastLat = loc.lat,
            lastLng = loc.lng,
            lastTs = loc.ts,
            lastAccuracy = loc.accuracy
        )
        _members.value = _members.value + (uid to member)
        persistMembers()
    }

    /** 移除成员（家人列表删除） */
    @Synchronized
    fun removeMember(uid: String) {
        _members.value = _members.value - uid
        persistMembers()
    }

    /** 清空全部成员（退出家庭） */
    @Synchronized
    fun clearMembers() {
        _members.value = emptyMap()
        persistMembers()
    }

    /** 本地备注名 */
    @Synchronized
    fun setMemberNote(uid: String, note: String) {
        val current = _members.value[uid] ?: FamilyMember(uid = uid)
        _members.value = _members.value + (uid to current.copy(note = note.trim()))
        persistMembers()
    }

    // ===== 加入审核 =====

    /** 记录一条加入申请（创建人视角，信令 join-request 到达时调用） */
    @Synchronized
    fun addPendingJoin(uid: String, name: String) {
        // 服务器要求审核说明本地残留的旧成员记录已过期：先移除，避免列表 key 冲突
        if (_members.value.containsKey(uid)) {
            _members.value = _members.value - uid
            persistMembers()
        }
        _pendingJoins.value = _pendingJoins.value + (uid to name)
    }

    /** 移除加入申请（创建人批准/拒绝后调用） */
    @Synchronized
    fun removePendingJoin(uid: String) {
        _pendingJoins.value = _pendingJoins.value - uid
    }

    /** 更新我的加入审核状态（加入者视角：join-pending / join-rejected / registered） */
    fun setJoinState(state: JoinState) {
        _joinState.value = state
    }

    // ===== 内部 =====

    private fun persistMembers() {
        val json = gson.toJson(_members.value.values.toMutableList(), membersType)
        prefs.edit().putString(PrefsKeys.FAMILY_MEMBERS, json).apply()
    }

    private fun loadMembers() {
        val json = prefs.getString(PrefsKeys.FAMILY_MEMBERS, null) ?: return
        try {
            val list: MutableList<FamilyMember> = gson.fromJson(json, membersType) ?: return
            _members.value = list.associateBy { it.uid }
        } catch (_: Exception) {
            prefs.edit().remove(PrefsKeys.FAMILY_MEMBERS).apply()
        }
    }
}