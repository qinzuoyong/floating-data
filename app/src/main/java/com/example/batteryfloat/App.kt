package com.example.batteryfloat

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 应用入口
 *
 * 职责(批次 2 起):进程启动时建立隐藏 API 全量豁免,
 * 供 ADB 配对的 Conscrypt#exportKeyingMaterial 反射调用使用
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }
    }
}
