package com.example.batteryfloat.family

import android.content.Context
import android.content.SharedPreferences
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 家人到达/离开提醒的地点
 *
 * @property id 本地主键（生成后不变；判定状态以它为键）
 * @property name 地点名（提醒文案里显示，如「学校」）
 * @property lat/lng 地点圆心（GCJ-02，与家人位置同坐标系）
 * @property radiusMeters 半径（米，收敛在 [PlaceStore.RADIUS_MIN_M]~[PlaceStore.RADIUS_MAX_M]）
 * @property watchUids 监视的成员 uid；**空 = 监视全部成员**
 * @property enabled 单个地点的开关（与总开关同时生效）
 */
data class AlertPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_M,
    val watchUids: List<String> = emptyList(),
    val enabled: Boolean = true
) {
    companion object {
        /** 默认半径（米） */
        const val DEFAULT_RADIUS_M = 300
    }
}

/**
 * 家人到达/离开提醒的本地存储（SharedPreferences + StateFlow）
 *
 * 只存本机、**不上行**：服务器与家人都看不到地点名与判定状态；
 * 被监视成员看到的仍只是"有人请求了我的位置"（与手动查看位置一致）。
 *
 * 持久化三项：[PrefsKeys.FAMILY_ALERT_PLACES]（地点列表）、
 * [PrefsKeys.FAMILY_ALERT_ENABLED]（总开关）、
 * [PrefsKeys.FAMILY_ALERT_STATES]（每个「成员 × 地点」的 in/out 状态，重启后延续）。
 */
class PlaceStore private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()
    private val placesType = object : TypeToken<MutableList<AlertPlace>>() {}.type
    private val statesType =
        object : TypeToken<MutableMap<String, GeofenceEvaluator.GeofenceState>>() {}.type

    private val _places = MutableStateFlow<List<AlertPlace>>(emptyList())
    /** 地点列表（UI 观察入口） */
    val places: StateFlow<List<AlertPlace>> = _places.asStateFlow()

    /** 判定状态（成员 × 地点 → 上次 in/out 与上次提醒时刻） */
    private val _states =
        MutableStateFlow<Map<String, GeofenceEvaluator.GeofenceState>>(emptyMap())

    init {
        loadPlaces()
        loadStates()
    }

    companion object {
        /** 半径下限（米）：小于此值 GPS 精度不足以稳定判定 */
        const val RADIUS_MIN_M = 100

        /** 半径上限（米） */
        const val RADIUS_MAX_M = 5000

        /** 轮询间隔下限（分钟）：再密会把家人设备拉成连续定位（耗电） */
        const val INTERVAL_MIN_MINUTES = 5

        /** 轮询间隔默认值（分钟） */
        const val INTERVAL_DEFAULT_MINUTES = 10

        /** 轮询间隔上限（分钟） */
        const val INTERVAL_MAX_MINUTES = 60

        /** 地点名长度上限（提醒文案单行可读） */
        private const val NAME_MAX_LEN = 20

        /** 判定状态键的分隔符：uid 与 placeId 都不含它 */
        private const val KEY_SEPARATOR = "|"

        @Volatile
        private var instance: PlaceStore? = null

        /** 进程级单例：UI 与服务共享同一 StateFlow */
        fun get(context: Context): PlaceStore =
            instance ?: synchronized(this) {
                instance ?: PlaceStore(context.applicationContext).also { instance = it }
            }
    }

    // ===== 总开关与检查频率 =====

    /** 总开关（默认关：开启后会定时请求家人位置，需用户明确知情） */
    fun alertsEnabled(): Boolean = prefs.getBoolean(PrefsKeys.FAMILY_ALERT_ENABLED, false)

    fun setAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PrefsKeys.FAMILY_ALERT_ENABLED, enabled).apply()
    }

    /**
     * 轮询检查间隔（分钟）
     *
     * 读取时收敛到 [INTERVAL_MIN_MINUTES]~[INTERVAL_MAX_MINUTES]：
     * 手改配置或旧数据越界时也不至于把家人定位打成连续唤醒。
     */
    fun intervalMinutes(): Int =
        prefs.getInt(PrefsKeys.FAMILY_ALERT_INTERVAL_MIN, INTERVAL_DEFAULT_MINUTES)
            .coerceIn(INTERVAL_MIN_MINUTES, INTERVAL_MAX_MINUTES)

    fun setIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(
            PrefsKeys.FAMILY_ALERT_INTERVAL_MIN,
            minutes.coerceIn(INTERVAL_MIN_MINUTES, INTERVAL_MAX_MINUTES)
        ).apply()
    }

    /** 生效中的地点（地点自身开关；总开关由调用方判断） */
    fun activePlaces(): List<AlertPlace> = _places.value.filter { it.enabled }

    // ===== 地点增删改查 =====

    /**
     * 新增地点
     *
     * @param name 地点名（空/超长会被规范化）
     * @param lat/lng 圆心坐标
     * @param radiusMeters 半径（米，越界即收敛，不报错）
     * @param watchUids 监视成员；空 = 监视全部成员
     * @return 落库后的地点（含生成的 id）
     */
    @Synchronized
    fun add(
        name: String,
        lat: Double,
        lng: Double,
        radiusMeters: Int,
        watchUids: List<String>
    ): AlertPlace {
        val place = AlertPlace(
            id = "p-" + UUID.randomUUID().toString().replace("-", "").take(12),
            name = normalizeName(name),
            lat = lat,
            lng = lng,
            radiusMeters = clampRadius(radiusMeters),
            watchUids = watchUids.distinct()
        )
        _places.value = _places.value + place
        persistPlaces()
        return place
    }

    /** 更新地点（按 id 覆盖；名称与半径同样规范化） */
    @Synchronized
    fun update(place: AlertPlace) {
        val normalized = place.copy(
            name = normalizeName(place.name),
            radiusMeters = clampRadius(place.radiusMeters),
            watchUids = place.watchUids.distinct()
        )
        _places.value = _places.value.map { if (it.id == normalized.id) normalized else it }
        persistPlaces()
    }

    /** 删除地点；连同其判定状态一并清除（状态不随删除无限残留） */
    @Synchronized
    fun remove(id: String) {
        _places.value = _places.value.filterNot { it.id == id }
        persistPlaces()
        _states.value = _states.value.filterKeys { !it.endsWith(KEY_SEPARATOR + id) }
        persistStates()
    }

    // ===== 判定状态（成员 × 地点，落盘延续） =====

    /** 读取上次判定状态（null=首次，尚无基线） */
    fun stateFor(uid: String, placeId: String): GeofenceEvaluator.GeofenceState? =
        _states.value[uid + KEY_SEPARATOR + placeId]

    /** 写入判定状态（in/out 与上次提醒时刻） */
    @Synchronized
    fun putState(uid: String, placeId: String, state: GeofenceEvaluator.GeofenceState) {
        _states.value = _states.value + (uid + KEY_SEPARATOR + placeId to state)
        persistStates()
    }

    /**
     * 丢弃不再属于家庭的成员的判定状态（registered 全量名册同步后调用）
     *
     * 状态键是 `uid|placeId`，成员退出家庭或从名册移除后条目不会自行消失：
     * 既让状态随"曾出现成员数 × 地点数"缓慢累积，也会在该 uid 回来时用**陈旧基线**
     * 判定一次（可能吞掉或多报一条到达/离开）。名册即家庭成员全集，据它收敛最准。
     *
     * @param keepUids 仍属于家庭的成员 uid（名册口径，不含自己）
     */
    @Synchronized
    fun pruneStatesNotIn(keepUids: Set<String>) {
        val kept = _states.value.filterKeys { it.substringBefore(KEY_SEPARATOR) in keepUids }
        if (kept.size == _states.value.size) return
        _states.value = kept
        persistStates()
    }

    // ===== 内部 =====

    /** 半径收敛（越界不报错，直接落到边界） */
    private fun clampRadius(radiusMeters: Int): Int =
        radiusMeters.coerceIn(RADIUS_MIN_M, RADIUS_MAX_M)

    /** 名称规范化：去空白、限长；空白名兜底为「未命名地点」（提醒文案不留空） */
    private fun normalizeName(name: String): String {
        val trimmed = name.trim().take(NAME_MAX_LEN)
        return trimmed.ifBlank { appContext.getString(R.string.family_alert_place_untitled) }
    }

    private fun persistPlaces() {
        prefs.edit().putString(PrefsKeys.FAMILY_ALERT_PLACES, gson.toJson(_places.value, placesType))
            .apply()
    }

    private fun persistStates() {
        prefs.edit().putString(PrefsKeys.FAMILY_ALERT_STATES, gson.toJson(_states.value, statesType))
            .apply()
    }

    private fun loadPlaces() {
        val json = prefs.getString(PrefsKeys.FAMILY_ALERT_PLACES, null) ?: return
        try {
            val list: MutableList<AlertPlace>? = gson.fromJson(json, placesType)
            _places.value = list ?: emptyList()
        } catch (_: Exception) {
            // 解析失败按空列表处理并清掉脏数据，避免每次启动都抛
            prefs.edit().remove(PrefsKeys.FAMILY_ALERT_PLACES).apply()
        }
    }

    private fun loadStates() {
        val json = prefs.getString(PrefsKeys.FAMILY_ALERT_STATES, null) ?: return
        try {
            val map: MutableMap<String, GeofenceEvaluator.GeofenceState>? =
                gson.fromJson(json, statesType)
            _states.value = map ?: emptyMap()
        } catch (_: Exception) {
            prefs.edit().remove(PrefsKeys.FAMILY_ALERT_STATES).apply()
        }
    }
}
