package com.example.batteryfloat.view

import kotlin.math.abs
import android.content.Context
import android.content.SharedPreferences
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.batteryfloat.R

/**
 * 电池温度悬浮窗视图（双行版）
 * - 竖向 LinearLayout：温度 + 功耗
 * - 圆角半透明背景，支持外观自定义
 * - 全屏拖拽，双击可锁定/解锁位置
 * - 内存缓存锁定状态和视图尺寸，减少每帧 SharedPreferences 读取和 measure 开销
 */
class FloatingWindowView(context: Context) : LinearLayout(context) {

    private var layoutParams: WindowManager.LayoutParams? = null
    private val prefs: SharedPreferences =
        context.getSharedPreferences("floating_prefs", Context.MODE_PRIVATE)

    /** 温度文本 */
    val tempText: TextView = TextView(context)
    /** 功耗文本 */
    val powerText: TextView = TextView(context)

    companion object {
        private const val TAG = "FloatingWindowView"
        private const val DOUBLE_TAP_MS = 400L
        /** 锁定功能开关 key（由 MainScreen 开关控制） */
        private const val PREF_LOCK_ENABLED = "lock_drag_enabled"
        /** 实际锁定状态 key（由双击切换） */
        private const val PREF_LOCK_ENGAGED = "lock_drag"
        /** 拖拽触发阈值（像素），超过此值视为拖拽 */
        private const val DRAG_THRESHOLD = 10
        /** SharedPreferences key - 横屏 X */
        private const val PREF_POS_LAND_X = "pos_land_x"
        /** SharedPreferences key - 横屏 Y */
        private const val PREF_POS_LAND_Y = "pos_land_y"
        /** SharedPreferences key - 竖屏 X */
        private const val PREF_POS_PORT_X = "pos_port_x"
        /** SharedPreferences key - 竖屏 Y */
        private const val PREF_POS_PORT_Y = "pos_port_y"
    }

    // 拖拽相关
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    /** 缓存 density，避免每帧 getDisplayMetrics 开销 */
    private val density: Float = context.resources.displayMetrics.density

    // 双击锁定相关
    private var lastTapTime = 0L
    private var isDragging = false

    // ===== 内存缓存（避免每帧读 SharedPreferences / measure） =====
    /** 内存缓存的实际锁定状态 */
    private var lockEngaged = false
    /** 内存缓存的锁定功能开关 */
    private var lockEnabled = false
    /** 缓存尺寸标记（外观变更时置 true，clamp 时重新 measure） */
    private var sizeCacheDirty = true
    /** 缓存视图宽度 */
    private var cachedWidth = 0
    /** 缓存视图高度 */
    private var cachedHeight = 0

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER

        // 温度行
        tempText.text = context.getString(R.string.temperature_loading)
        tempText.gravity = Gravity.CENTER
        addView(tempText)

        // 功耗行
        powerText.text = "--W"
        powerText.gravity = Gravity.CENTER
        addView(powerText)

        applyAppearance()
    }

    fun setLayoutParams(params: WindowManager.LayoutParams) {
        this.layoutParams = params
    }

    fun reloadAppearance() {
        applyAppearance()
    }

    private fun applyAppearance() {
        val fontSize = prefs.getFloat("font_size", 8f)
        val alpha = (prefs.getFloat("bg_alpha", 0.5f) * 255).toInt().coerceIn(0, 255)
        val bgColor = prefs.getInt("bg_color", 0xFF666666.toInt())
        val finalBg = Color.argb(alpha, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
        val cornerRadius = prefs.getFloat("corner_radius", 30f)
        val textColor = prefs.getInt("text_color", Color.WHITE)
        val showPower = prefs.getBoolean("show_power", true)
        // 同步内存缓存，后续触摸事件直接读取缓存变量
        lockEngaged = prefs.getBoolean(PREF_LOCK_ENGAGED, false)
        lockEnabled = prefs.getBoolean(PREF_LOCK_ENABLED, false)

        val drawable = GradientDrawable().apply {
            setColor(finalBg)
            setCornerRadius(dpToPx(cornerRadius.toInt()).toFloat())
            // 锁定状态添加半透明天蓝色边框，美感简洁
            if (lockEngaged) {
                setStroke(dpToPx(2), Color.argb(180, 100, 181, 246))  // 柔光蓝 2dp
            } else {
                setStroke(0, Color.TRANSPARENT)
            }
        }
        background = drawable

        tempText.textSize = fontSize
        tempText.setTextColor(textColor)

        powerText.textSize = fontSize * 0.85f
        powerText.setTextColor(textColor)

        powerText.visibility = if (showPower) View.VISIBLE else View.GONE

        val padding = dpToPx(8)
        setPadding(padding, padding / 2, padding, padding / 2)

        // 外观变更后尺寸缓存失效，下次 clamp 需重新 measure
        sizeCacheDirty = true
    }

    fun updateTemperature(celsius: Float) {
        // 锁定状态通过边框指示，温度文本保持不变，与功耗文本对齐
        tempText.text = String.format("%.1f\u00b0C", celsius)
    }

    fun updatePower(watts: Float) {
        powerText.text = if (watts >= 0) String.format("%.1fW", watts) else "--W"
    }

    /**
     * 将悬浮窗钳位到当前屏幕有效范围内
     * - 使用缓存尺寸（外观未变更时避免重复 measure）
     * - 如果是方向切换（非拖拽），先恢复该方向的已保存位置，再钳位
     * @param restorePosition 是否恢复为当前方向已保存的位置（方向切换时用）
     */
    fun clampToScreenBounds(restorePosition: Boolean = false) {
        val params = layoutParams ?: return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val windowMetrics = wm.currentWindowMetrics
        val bounds = windowMetrics.bounds
        val screenW = bounds.width()
        val screenH = bounds.height()

        // 只在尺寸缓存脏（外观变更/首次）时重新 measure
        if (sizeCacheDirty || cachedWidth <= 0 || cachedHeight <= 0) {
            measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            cachedWidth = measuredWidth
            cachedHeight = measuredHeight
            sizeCacheDirty = false
        }
        val viewW = cachedWidth
        val viewH = cachedHeight

        val isLandscape = screenW > screenH
        // 如果是方向切换，恢复该方向已保存的位置
        if (restorePosition) {
            val savedPos = getSavedPosition(isLandscape)
            if (savedPos.first != Int.MIN_VALUE && savedPos.second != Int.MIN_VALUE) {
                params.x = savedPos.first
                params.y = savedPos.second
            }
        }
        val displayCutout = windowMetrics.windowInsets.displayCutout
        val topInset = displayCutout?.safeInsetTop ?: 0
        var minX = 0
        if (isLandscape) {
            val leftInset = displayCutout?.safeInsetLeft ?: 0
            minX = -leftInset.coerceAtLeast(1)
        }
        val maxX = screenW - viewW
        val maxY = screenH - viewH
        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(-topInset, maxY)
        try {
            wm.updateViewLayout(this, params)
        } catch (e: Exception) {
            Log.w(TAG, "钳位悬浮窗位置失败: ${e.message}")
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val params = layoutParams ?: return super.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.rawX - initialTouchX)
                val dy = abs(event.rawY - initialTouchY)
                if (dx > DRAG_THRESHOLD || dy > DRAG_THRESHOLD) isDragging = true

                // 实际锁定状态下禁止拖拽（使用内存缓存，避免每帧读 SharedPreferences）
                if (lockEngaged) return true

                params.x = initialX + (event.rawX - initialTouchX).toInt()
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                clampToScreenBounds()
                return true
            }
            MotionEvent.ACTION_UP -> {
                // 拖拽结束后保存当前位置
                if (isDragging) {
                    saveCurrentPosition()
                }
                // 仅在非拖拽时检测双击
                if (!isDragging) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < DOUBLE_TAP_MS) {
                        // 仅当锁定功能开关开启时，双击才生效（使用内存缓存）
                        if (!lockEnabled) {
                            Log.d(TAG, "双击忽略：锁定功能开关已关闭")
                            lastTapTime = 0
                            return true
                        }
                        // 双击：切换实际锁定状态
                        lockEngaged = !lockEngaged
                        prefs.edit().putBoolean(PREF_LOCK_ENGAGED, lockEngaged).apply()
                        Log.i(TAG, "双击切换锁定: $lockEngaged")
                        // 触发震动反馈（提示用户锁定状态已变更）
                        @Suppress("MissingPermission")
                        val vibrator = context.getSystemService(Vibrator::class.java)
                        if (vibrator != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            vibrator.vibrate(
                                VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                            )
                        }
                        // 刷新外观（更新边框，不修改温度文本）
                        reloadAppearance()
                    }
                    lastTapTime = now
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // 处理取消事件，重置拖拽状态
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * density).toInt()
    }

    /**
     * 保存当前悬浮窗位置到 SharedPreferences（按指定方向）
     *
     * @param isLandscape 指定保存到哪个方向的 key。
     *        null 时自动检测当前方向（用于拖拽结束时的调用，此时方向未变）。
     *        非 null 时使用传入值（用于方向切换时的调用，此时 currentWindowMetrics
     *        已变为新方向，必须由调用方传入旧方向）。
     */
    fun saveCurrentPosition(isLandscape: Boolean? = null) {
        val params = layoutParams ?: return
        val landscape = isLandscape ?: run {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() > bounds.height()
        }
        if (landscape) {
            prefs.edit().putInt(PREF_POS_LAND_X, params.x).putInt(PREF_POS_LAND_Y, params.y).apply()
        } else {
            prefs.edit().putInt(PREF_POS_PORT_X, params.x).putInt(PREF_POS_PORT_Y, params.y).apply()
        }
    }

    /**
     * 获取指定方向已保存的位置
     * @return Pair(x, y)，无保存值则返回 Int.MIN_VALUE
     */
    private fun getSavedPosition(isLandscape: Boolean): Pair<Int, Int> {
        if (isLandscape) {
            val x = prefs.getInt(PREF_POS_LAND_X, Int.MIN_VALUE)
            val y = prefs.getInt(PREF_POS_LAND_Y, Int.MIN_VALUE)
            return Pair(x, y)
        } else {
            val x = prefs.getInt(PREF_POS_PORT_X, Int.MIN_VALUE)
            val y = prefs.getInt(PREF_POS_PORT_Y, Int.MIN_VALUE)
            return Pair(x, y)
        }
    }
}