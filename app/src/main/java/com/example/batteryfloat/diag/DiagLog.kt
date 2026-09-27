package com.example.batteryfloat.diag

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量诊断落盘（复用应用已有的 adb_diag.log 机制）
 *
 * 为什么需要：部分机型（如 vivo）屏蔽应用 logcat，只能靠外部存储上的诊断文件回看。
 * 家人链路的"连接抖动"（短周期断开重连）在 logcat 上不易留存，落盘后可用
 * `adb shell cat /sdcard/Android/data/<pkg>/files/adb_diag.log` 直接回看时序。
 *
 * 约定（红线）：
 * - **容量上限**：超过 [MAX_BYTES] 即清空重写，不会无界增长；
 * - **脱敏**：所有写入都经 [mask]，家庭码（6 位连续数字）与设备 uid（`d-` 开头的长十六进制串）
 *   不得明文落盘；调用方也不应主动拼入这些值（日志行只写事件类型、端点序号与关闭码）；
 * - **不冒泡**：诊断写入失败（无存储/权限异常）一律静默，绝不影响主流程。
 */
object DiagLog {

    /** 与 AdbConnectionManager 的诊断日志共用同一文件（应用内只保留一条诊断通道） */
    private const val FILE_NAME = "adb_diag.log"

    /** 容量上限（字节）：超过即清空重写 */
    private const val MAX_BYTES = 64_000L

    /** 单行上限（字符）：防异常文本撑爆文件 */
    private const val MAX_LINE_LEN = 80

    /** 设备 uid 形态：`d-` + 长十六进制 */
    private val UID_PATTERN = Regex("d-[0-9a-fA-F]{8,}")

    /** 家庭码形态：6 位连续数字（前后不再接数字） */
    private val ROOM_PATTERN = Regex("(?<!\\d)\\d{6}(?!\\d)")

    /**
     * 追加一行诊断（自动补时间戳并脱敏）
     *
     * @param context 任意 Context（取应用外部 files 目录）
     * @param line 事件内容，调用方不要拼入家庭码/uid
     */
    fun append(context: Context, line: String) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val file = java.io.File(dir, FILE_NAME)
            if (file.length() > MAX_BYTES) file.writeText("")
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText(ts + " " + mask(line) + "\n")
        } catch (_: Throwable) {
            // 诊断落盘永不冒泡
        }
    }

    /**
     * 脱敏：家庭码与设备 uid 打码，其余内容原样保留（诊断价值不因脱敏损失）
     *
     * 定点替换而非整行清除：连接事件里的关闭码、端点序号、`remote=true` 等
     * 正是排障所需，全部抹掉等于白记。
     */
    fun mask(value: String?): String {
        val raw = (value ?: "").take(MAX_LINE_LEN)
        return raw.replace(UID_PATTERN, "d-***").replace(ROOM_PATTERN, "******")
    }
}
