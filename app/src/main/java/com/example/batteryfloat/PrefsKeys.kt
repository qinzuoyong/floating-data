package com.example.batteryfloat

/**
 * SharedPreferences key 集中管理
 * 统一管理所有 SharedPreferences 的 key，避免硬编码字符串散落各处
 */
object PrefsKeys {
    /** SharedPreferences 文件名 */
    const val PREFS_NAME = "floating_prefs"

    // ===== 外观设置 =====
    /** 字体大小（sp） */
    const val FONT_SIZE = "font_size"
    /** 背景透明度（0.0-1.0） */
    const val BG_ALPHA = "bg_alpha"
    /** 背景颜色（ARGB int） */
    const val BG_COLOR = "bg_color"
    /** 圆角半径（dp） */
    const val CORNER_RADIUS = "corner_radius"
    /** 文字颜色（ARGB int） */
    const val TEXT_COLOR = "text_color"
    /** 功耗显示开关 */
    const val SHOW_POWER = "show_power"

    // ===== 悬浮窗位置 =====
    /** 横屏 X 坐标 */
    const val POS_LAND_X = "pos_land_x"
    /** 横屏 Y 坐标 */
    const val POS_LAND_Y = "pos_land_y"
    /** 竖屏 X 坐标 */
    const val POS_PORT_X = "pos_port_x"
    /** 竖屏 Y 坐标 */
    const val POS_PORT_Y = "pos_port_y"

    // ===== 锁定功能 =====
    /** 拖拽锁定功能开关 */
    const val LOCK_DRAG_ENABLED = "lock_drag_enabled"
    /** 实际锁定状态（双击切换） */
    const val LOCK_DRAG_ENGAGED = "lock_drag"

    // ===== 系统功能 =====
    /** 开机自启动开关 */
    const val BOOT_AUTO_START = "boot_auto_start"
    /** 悬浮窗是否在上次运行中 */
    const val FLOATING_WAS_RUNNING = "floating_was_running"
    /** 隐藏后台任务卡片 */
    const val HIDE_RECENTS = "hide_recents"
    /** ADB 无线调试高精度数据源开关(默认关,密钥另存 adb_prefs) */
    const val ADB_PRIV_ENABLED = "adb_priv_enabled"
    /** ADB 通道连通后自动开启所需权限(WRITE_SECURE_SETTINGS/无障碍/悬浮窗,默认关) */
    const val ADB_AUTO_GRANT = "adb_auto_grant"
    /** Shizuku 授权请求已发起过(用户拒绝后不重复弹窗) */
    const val SHIZUKU_PERM_REQUESTED = "shizuku_perm_requested"
    /** 用户在应用内主动关闭无障碍保活(此时系统侧被关视为用户意图,自愈不触发) */
    const val A11Y_USER_DISABLED = "a11y_user_disabled"

    // ===== UI 状态 =====
    /** 主题模式（0=跟随系统, 1=浅色, 2=深色） */
    const val THEME_MODE = "theme_mode"
    /** 受限设置引导已显示 */
    const val RESTRICTED_SETTINGS_GUIDED = "restricted_settings_guided"

    // ===== 家人位置共享 =====
    /** 6 位家庭码（同一家庭设备输入相同码配对） */
    const val FAMILY_CODE = "family_code"
    /** 我的备注名（注册信令展示给家人） */
    const val FAMILY_MY_NAME = "family_my_name"
    /** 我的设备唯一标识（生成一次后持久化） */
    const val FAMILY_MY_UID = "family_my_uid"
    /** 是否允许家人请求我的位置（隐私开关，默认允许） */
    const val FAMILY_ALLOW_LOC_REQ = "family_allow_loc_req"
    /** 成员列表 JSON（uid → 备注/上次位置/在线状态） */
    const val FAMILY_MEMBERS = "family_members"
}