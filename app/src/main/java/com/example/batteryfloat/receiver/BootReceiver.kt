package com.example.batteryfloat.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.batteryfloat.service.FloatingWindowService

/**
 * 开机自启广播接收器
 * 监听 BOOT_COMPLETED，自动启动悬浮窗服务
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "设备开机，启动悬浮窗服务")
            try {
                FloatingWindowService.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "开机启动服务失败", e)
            }
        }
    }
}
