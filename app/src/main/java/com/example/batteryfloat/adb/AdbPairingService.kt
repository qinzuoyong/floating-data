/*
 * 移植自 Shizuku (https://github.com/RikkaApps/Shizuku) manager/src/main/java/moe/shizuku/manager/adb/AdbPairingService.kt
 * Copyright (C) 2021 RikkaApps
 * Licensed under the Apache License, Version 2.0
 *
 * 适配点:GlobalScope → 自有 scope;密钥复用 AdbConnectionManager;
 *        startForeground 使用 specialUse 类型(targetSdk 34 要求);通知构建集中到 Notifs
 *
 * 配对主路径(与 Shizuku 一致):前台服务 + 通知栏 RemoteInput 直回配对码,
 * 与系统「使用配对码配对设备」弹窗同屏可见,免切换应用/免分屏。
 * 时序:先持续 NSD 发现 pairing 端口(无超时,端口变化自动刷新通知),码到即配。
 */
package com.example.batteryfloat.adb

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.example.batteryfloat.notif.Notifs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.ConnectException

class AdbPairingService : Service() {

    companion object {

        private const val TAG = "AdbPairingService"

        private const val REPLY_REQUEST_ID = 1
        private const val STOP_REQUEST_ID = 2

        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_REPLY = "reply"
        private const val REMOTE_INPUT_KEY = "pairing_code"
        private const val EXTRA_PORT = "pairing_port"

        fun start(context: Context) {
            val intent = Intent(context, AdbPairingService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        private fun stopIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_STOP)

        private fun replyIntent(context: Context, port: Int): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_REPLY)
                .putExtra(EXTRA_PORT, port)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var adbMdns: AdbMdns? = null
    private var started = false

    private val observer = { port: Int ->
        Log.i(TAG, "配对服务端口: $port")
        if (port > 0) {
            // 端口写入通知 reply intent:即使服务在用户输码前被回收,端口也不丢(Shizuku 同款设计)
            getSystemService(NotificationManager::class.java)
                .notify(Notifs.ID_PAIRING, createInputNotification(port))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            ACTION_START -> onStart()
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_KEY)?.toString() ?: ""
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (port != -1) onInput(code, port) else onStart()
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            else -> return START_NOT_STICKY
        }
        if (notification != null) {
            try {
                startForeground(
                    Notifs.ID_PAIRING, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e: Throwable) {
                Log.e(TAG, "startForeground 失败", e)
                getSystemService(NotificationManager::class.java)
                    .notify(Notifs.ID_PAIRING, notification)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
        scope.cancel()
    }

    private fun onStart(): Notification {
        startSearch()
        return searchingNotification
    }

    private fun onInput(code: String, port: Int): Notification {
        val key = AdbConnectionManager.peekKey()
        if (key == null) {
            Log.e(TAG, "AdbKey 不可用")
            handleResult(false, AdbKeyException(IllegalStateException("AdbKey 不可用")))
            return workingNotification
        }
        Log.i(TAG, "开始配对(端口 $port)")
        scope.launch {
            // use 确保成功/失败/中途异常路径都释放 socket/流与 native PairingContext
            AdbPairingClient("127.0.0.1", port, code, key).use { pairing ->
                pairing.runCatching {
                    start()
                }.onFailure {
                    Log.w(TAG, "配对失败", it)
                    handleResult(false, it)
                }.onSuccess {
                    Log.i(TAG, "配对协议完成: $it")
                    handleResult(it, null)
                }
            }
        }
        return workingNotification
    }

    private fun handleResult(success: Boolean, exception: Throwable?) {
        stopForeground(STOP_FOREGROUND_REMOVE)

        val title: String
        val text: String?

        if (success) {
            Log.i(TAG, "配对成功")
            title = "配对成功"
            text = "正在连接高精度数据源…"
            stopSearch()
            AdbConnectionManager.onPaired(applicationContext)
        } else {
            title = "配对失败"
            text = when (exception) {
                is ConnectException -> "无法连接配对端口:配对弹窗是否已关闭?重新打开后重试"
                is AdbInvalidPairingCodeException -> "配对码错误或已过期(系统每次弹窗会刷新),请重试"
                is AdbKeyException -> "密钥存储错误,请重试或重启应用"
                else -> {
                    exception?.let { Log.w(TAG, "配对异常堆栈:\n" + Log.getStackTraceString(it)) }
                    "未知错误,详见日志"
                }
            }
        }

        // 成功通知 4 秒后自动消失;失败通知保留供查看原因(点击关闭)
        getSystemService(NotificationManager::class.java)
            .notify(Notifs.ID_PAIRING, Notifs.pairingResult(this, title, text, success))
        stopSelf()
    }

    private val stopNotificationAction: Notification.Action by lazy {
        val pendingIntent = PendingIntent.getService(
            this, STOP_REQUEST_ID, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE
        )
        Notification.Action.Builder(null, "停止搜索", pendingIntent).build()
    }

    private val replyNotificationAction: Notification.Action by lazy {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY).run {
            setLabel("配对码")
            build()
        }
        val pendingIntent = PendingIntent.getForegroundService(
            this, REPLY_REQUEST_ID, replyIntent(this, -1),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        Notification.Action.Builder(null, "输入配对码", pendingIntent)
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun replyNotificationAction(port: Int): Notification.Action {
        // 先确保基础 action 已创建,再以新端口重建 PendingIntent(action 内持有的 PI 需随端口更新)
        val action = replyNotificationAction
        PendingIntent.getForegroundService(
            this, REPLY_REQUEST_ID, replyIntent(this, port),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return action
    }

    private val searchingNotification: Notification by lazy {
        Notifs.pairingSearching(this, stopNotificationAction)
    }

    private fun createInputNotification(port: Int): Notification {
        return Notifs.pairingInput(this, replyNotificationAction(port))
    }

    private val workingNotification: Notification by lazy {
        Notifs.pairingWorking(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
