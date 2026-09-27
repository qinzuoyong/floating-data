package com.example.batteryfloat.family

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 家人到达/离开提醒的判定核心（**纯函数**，无 Android 依赖，可独立复算）
 *
 * 判定链（顺序即优先级）：
 * 1. 样本可用性：精度差于半径、超出新鲜度窗口的样本直接丢弃（不可据此判定）；
 * 2. 双阈值 + 滞回：进入用半径、离开用半径 × [EXIT_HYSTERESIS_FACTOR]，
 *    介于两者之间时保持上次状态——否则站在边界上会来回"到达/离开"刷通知；
 * 3. 首次样本只建立基线（不通知）：否则应用刚启动就会因"当前在半径外"报一次离开;
 * 4. 状态未变不通知;
 * 5. 同一（成员 × 地点）在 [NOTIFY_COOLDOWN_MS] 内最多一条通知（去抖兜底）：
 *    窗口内命中的转换**不提醒也不推进状态**，窗口过后由下一份样本按当前状态补报。
 *
 * 调用方负责把 [GeofenceState] 落盘（重启后延续判定），本对象不持有任何状态。
 */
object GeofenceEvaluator {

    /** 离开阈值系数：离开半径 = 地点半径 × 该系数（滞回区宽度 = 半径 × 0.25） */
    const val EXIT_HYSTERESIS_FACTOR = 1.25

    /** 样本新鲜度窗口：取值时刻早于（now - 该值）的样本视为过期并丢弃 */
    const val SAMPLE_FRESHNESS_MS = 15 * 60_000L

    /** 同一（成员 × 地点）的通知去重窗口：窗口内最多提醒一次 */
    const val NOTIFY_COOLDOWN_MS = 10 * 60_000L

    /** 地球平均半径（米），Haversine 用 */
    private const val EARTH_RADIUS_M = 6_371_008.8

    /**
     * 单个（成员 × 地点）的判定状态（由调用方落盘，重启后继续沿用）
     *
     * @property inside 上次判定的结果（true=在半径内）
     * @property lastNotifyAt 上次发出提醒的时刻（0=从未提醒）
     */
    data class GeofenceState(val inside: Boolean, val lastNotifyAt: Long = 0L)

    /** 单次判定结果 */
    sealed interface Decision {
        /** 样本不可用（精度差于半径 / 超出新鲜度窗口）：不改变任何状态 */
        data object Discarded : Decision

        /** 无转换：首次建立基线、状态未变、或命中转换但在去重窗口内（状态仍需落盘） */
        data class Quiet(val state: GeofenceState) : Decision

        /** 命中到达/离开转换且应当提醒 */
        data class Alert(val state: GeofenceState, val entered: Boolean) : Decision
    }

    /**
     * 两点大圆距离（Haversine，米）
     *
     * @param lat1 起点纬度、[lng1] 起点经度（度）
     * @param lat2 终点纬度、[lng2] 终点经度（度）
     */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * 判定一个位置样本是否构成到达/离开转换
     *
     * @param previous 上次状态；null=首次样本（仅建立基线，不通知）
     * @param radiusMeters 地点半径（米）
     * @param distanceMeters 样本点到地点的距离（米，由 [distanceMeters] 算出）
     * @param accuracyMeters 样本精度（米）；差于半径即不可用于判定
     * @param sampleTs 样本取值时刻；超出新鲜度窗口即丢弃
     * @param nowMs 当前时刻
     */
    fun evaluate(
        previous: GeofenceState?,
        radiusMeters: Int,
        distanceMeters: Double,
        accuracyMeters: Float,
        sampleTs: Long,
        nowMs: Long
    ): Decision {
        // 1. 样本可用性
        if (accuracyMeters > radiusMeters) return Decision.Discarded
        if (sampleTs <= 0L || nowMs - sampleTs > SAMPLE_FRESHNESS_MS) return Decision.Discarded

        // 2. 双阈值 + 滞回：滞回区内保持上次状态（首次样本落在滞回区视为在外）
        val exitRadius = radiusMeters * EXIT_HYSTERESIS_FACTOR
        val inside = when {
            distanceMeters <= radiusMeters -> true
            distanceMeters > exitRadius -> false
            else -> previous?.inside ?: false
        }

        // 3. 首次样本：只建立基线
        if (previous == null) return Decision.Quiet(GeofenceState(inside, 0L))

        // 4. 状态未变
        if (previous.inside == inside) return Decision.Quiet(GeofenceState(inside, previous.lastNotifyAt))

        // 5. 命中转换但落在去重窗口内：不提醒，且**状态保持原样**（不推进）。
        //    若这里把状态推进成 inside，这次真实转换就被永久吞掉——窗口过后同一位置再也
        //    不构成"状态翻转"，用户永远等不到那条「到达」（真机实测：离开后 10 分钟内返回，
        //    只在锁屏收到「离开」，「到达」再不出现）。保持原状态则窗口过后由下一份样本补报。
        if (nowMs - previous.lastNotifyAt < NOTIFY_COOLDOWN_MS) {
            return Decision.Quiet(previous)
        }
        return Decision.Alert(GeofenceState(inside, nowMs), entered = inside)
    }
}
