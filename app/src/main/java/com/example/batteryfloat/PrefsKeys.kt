package com.example.batteryfloat

/**
 * SharedPreferences 常量定义
 * 集中管理所有 key，避免硬编码字符串散布各处
 */
object PrefsKeys {
    /** SharedPreferences 文件名 */
    const val PREFS_NAME = "floating_prefs"

    // ===== 服务状态 =====
    /** 悬浮窗是否正在运行（供开机自启判断） */
    const val FLOATING_WAS_RUNNING = "floating_was_running"

    // ===== 开机自启 =====
    /** 开机自启动开关 */
    const val BOOT_AUTO_START = "boot_auto_start"

    // ===== 权限引导 =====
    /** Android 14+ 受限设置是否已引导过 */
    const val RESTRICTED_SETTINGS_GUIDED = "restricted_settings_guided"

    // ===== UI 控制 =====
    /** 隐藏后台（最近任务列表） */
    const val HIDE_RECENTS = "hide_recents"

    // ===== 外观设置 =====
    /** 主题模式（0=跟随系统, 1=浅色, 2=深色） */
    const val THEME_MODE = "theme_mode"

    /** 字体大小 */
    const val FONT_SIZE = "font_size"

    /** 圆角曲率 */
    const val CORNER_RADIUS = "corner_radius"

    /** 背景透明度 */
    const val BG_ALPHA = "bg_alpha"

    /** 背景颜色 */
    const val BG_COLOR = "bg_color"

    /** 文字颜色 */
    const val TEXT_COLOR = "text_color"

    // ===== 功能开关 =====
    /** 锁定悬浮窗位置（双击切换） */
    const val LOCK_DRAG_ENGAGED = "lock_drag"

    /** 锁定功能是否启用（外部开关） */
    const val LOCK_DRAG_ENABLED = "lock_drag_enabled"

    /** 功耗显示开关 */
    const val SHOW_POWER = "show_power"

    // ===== 悬浮窗位置（横屏） =====
    const val POS_LAND_X = "pos_land_x"
    const val POS_LAND_Y = "pos_land_y"

    // ===== 悬浮窗位置（竖屏） =====
    const val POS_PORT_X = "pos_port_x"
    const val POS_PORT_Y = "pos_port_y"
}
