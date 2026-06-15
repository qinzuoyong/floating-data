package com.example.batteryfloat.view

import android.content.Context
import android.content.SharedPreferences
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
    }

    // 拖拽相关
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // 双击锁定相关
    private var lastTapTime = 0L
    private var isDragging = false

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
        val fontSize = prefs.getFloat("font_size", 14f)
        val alpha = (prefs.getFloat("bg_alpha", 0.8f) * 255).toInt().coerceIn(0, 255)
        val bgColor = prefs.getInt("bg_color", Color.argb(255, 0, 0, 0))
        val finalBg = Color.argb(alpha, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
        val cornerRadius = prefs.getFloat("corner_radius", 30f)
        val textColor = prefs.getInt("text_color", Color.WHITE)
        val showPower = prefs.getBoolean("show_power", false)

        val drawable = GradientDrawable().apply {
            setColor(finalBg)
            setCornerRadius(dpToPx(cornerRadius.toInt()).toFloat())
        }
        background = drawable

        tempText.textSize = fontSize
        tempText.setTextColor(textColor)

        powerText.textSize = fontSize * 0.85f
        powerText.setTextColor(textColor)

        powerText.visibility = if (showPower) View.VISIBLE else View.GONE

        val padding = dpToPx(8)
        setPadding(padding, padding / 2, padding, padding / 2)
    }

    fun updateTemperature(celsius: Float) {
        val isLocked = prefs.getBoolean("lock_drag", false)
        tempText.text = if (isLocked) "🔒 ${String.format("%.1f", celsius)}°C"
                        else String.format("%.1f°C", celsius)
    }

    fun updatePower(watts: Float) {
        powerText.text = if (watts >= 0) String.format("%.1fW", watts) else "--W"
    }

    /**
     * 将悬浮窗钳位到当前屏幕有效范围内
     */
    fun clampToScreenBounds() {
        val params = layoutParams ?: return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay?.getRealMetrics(displayMetrics)
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels
        measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val viewW = measuredWidth
        val viewH = measuredHeight
        val isLandscape = screenW > screenH
        @Suppress("DEPRECATION")
        val displayCutout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            wm.defaultDisplay?.cutout
        } else null
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
                val dx = Math.abs(event.rawX - initialTouchX)
                val dy = Math.abs(event.rawY - initialTouchY)
                if (dx > 10 || dy > 10) isDragging = true

                // 锁定状态下禁止拖拽
                if (prefs.getBoolean("lock_drag", false)) return true

                params.x = initialX + (event.rawX - initialTouchX).toInt()
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                clampToScreenBounds()
                return true
            }
            MotionEvent.ACTION_UP -> {
                // 仅在非拖拽时检测双击
                if (!isDragging) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < DOUBLE_TAP_MS) {
                        // 双击：切换锁定状态
                        val newLocked = !prefs.getBoolean("lock_drag", false)
                        prefs.edit().putBoolean("lock_drag", newLocked).apply()
                        Log.i(TAG, "双击切换锁定: $newLocked")
                        // 如果温度已显示，立即更新锁图标
                        tempText.text = tempText.text?.let {
                            val temp = it.toString().replace("🔒 ", "").replace("°C", "")
                            if (newLocked) "🔒 ${temp}°C" else "${temp}°C"
                        }
                    }
                    lastTapTime = now
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
