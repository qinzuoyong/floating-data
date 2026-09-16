package com.example.batteryfloat

import android.app.Application
import android.os.Build
import com.example.batteryfloat.adb.AdbConnectionManager
import com.example.batteryfloat.service.A11ySelfHealer
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
        // 进程启动即自检无障碍绑定实况。
        // 应用真崩溃后系统会把无障碍服务判为 crashed 并停止绑定,而它仍留在启用列表里;
        // 此时既没有 onDestroy 钩子(硬崩溃不执行)、也没有悬浮窗巡检(用户可能不开悬浮窗),
        // 自愈便永远不会被触发。进程能被拉起(用户打开应用等)是这类故障下唯一可靠的时机。
        A11ySelfHealer.maybeHeal(this, trigger = "app-start")
    }
}