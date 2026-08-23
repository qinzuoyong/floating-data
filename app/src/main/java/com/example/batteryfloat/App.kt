package com.example.batteryfloat

import android.app.Application
import android.os.Build
import com.baidu.mapapi.SDKInitializer
import com.example.batteryfloat.adb.AdbConnectionManager
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
        // 进程经任一入口(无障碍绑定/开机自启/前台服务)拉起时初始化 ADB 通道,
        // 无障碍自愈的 pm grant/shell 写回不依赖 MainActivity 曾启动(幂等,已初始化则直接返回)
        AdbConnectionManager.setup(this)

        // 百度地图 SDK：隐私合规声明 + 全局初始化（AK 来自 manifest meta-data）
        runCatching { SDKInitializer.setAgreePrivacy(this, true) }
        SDKInitializer.initialize(this)
    }
}