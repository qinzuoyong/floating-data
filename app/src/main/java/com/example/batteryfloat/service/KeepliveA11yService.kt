package com.example.batteryfloat.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.WindowManager
/**
 * 无障碍保活服务
 *
 * 核心原理（参考 GKD 的保活机制）：
 * 1. Android 系统将无障碍服务进程视为最高优先级之一，几乎不会主动杀死
 * 2. 添加一个 1x1 不可见的 TYPE_ACCESSIBILITY_OVERLAY 窗口，
 *    防止系统在空闲时回收无障碍服务资源
 * 3. 本服务不处理任何实际无障碍事件，CPU 消耗接近于零
 *
 * 该服务受「进程保活开关」控制，仅在用户主动开启时生效
 */
@SuppressLint("AccessibilityPolicy")
class KeepliveA11yService : AccessibilityService() {

    companion object {
        private const val TAG = "KeepliveA11yService"

        /** 服务是否正在运行（供外部查询） */
        @Volatile
        var isRunning = false

        /** 当前实例引用 */
        @Volatile
        var instance: KeepliveA11yService? = null
    }

    /** 保活用的 1x1 不可见覆盖层 */
    private var aliveView: View? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "无障碍保活服务已创建")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍保活服务已连接，添加 1x1 保活覆盖层")
        addAliveOverlay()
    }

    override fun onInterrupt() {
        // 不做任何处理 — 我们不处理无障碍事件
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 空实现 — 不处理任何无障碍事件，仅利用系统保活
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        removeAliveOverlay()
        Log.i(TAG, "无障碍保活服务已销毁")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * 添加 1x1 不可见的 TYPE_ACCESSIBILITY_OVERLAY 覆盖层
     *
     * 该窗口类型是系统为无障碍服务提供的特殊窗口类型，
     * 具有极高的系统优先级，可以显著提升进程存活率。
     * 窗口仅 1x1 像素，不可触摸，对用户完全不可见。
     */
    private fun addAliveOverlay() {
        removeAliveOverlay()  // 先清理旧的
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            width = 1          // 1px 宽度
            height = 1         // 1px 高度
            x = 0
            y = 0
            packageName = applicationContext.packageName
        }
        try {
            wm.addView(view, lp)
            aliveView = view
            Log.i(TAG, "1x1 保活覆盖层添加成功")
        } catch (e: Exception) {
            Log.e(TAG, "添加保活覆盖层失败: ${e.message}")
            aliveView = null  // 确保状态一致
        }
    }

    /** 移除保活覆盖层 */
    private fun removeAliveOverlay() {
        aliveView?.let { view ->
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
                Log.i(TAG, "保活覆盖层已移除")
            } catch (e: Exception) {
                Log.w(TAG, "移除保活覆盖层失败: ${e.message}")
            }
            aliveView = null
        }
    }
}
