/*
 * 移植自 Shizuku (https://github.com/RikkaApps/Shizuku) manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt
 * Copyright (C) 2021 RikkaApps
 * Licensed under the Apache License, Version 2.0
 *
 * 适配点:
 * - androidx.lifecycle.Observer → Kotlin 函数类型,免 livedata 依赖
 * - 健壮性加固(参考 Stellar/roro2239 对同源代码的加固):
 *   发现启动失败有限重试、停止失败状态复位防死锁、发现窗口内周期重启防 ROM 漏报、
 *   resolve 并发保护、本机接口枚举移出 NSD 回调线程
 */
package com.example.batteryfloat.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.Executors

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: (Int) -> Unit
) {

    @Volatile
    private var registered = false

    @Volatile
    private var running = false

    /** 刷新触发的 stop→start 重启进行中(等 onDiscoveryStopped 后再重新发起) */
    @Volatile
    private var pendingRestart = false

    /** resolveService 进行中(NSD 不允许对同一发现并发 resolve) */
    @Volatile
    private var resolving = false

    @Volatile
    private var serviceFound = false

    @Volatile
    private var startRetryCount = 0

    @Volatile
    private var refreshCount = 0
    private var serviceName: String? = null
    private var listener: DiscoveryListener? = null
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor = Executors.newSingleThreadExecutor()

    /** 周期重启发现:部分 ROM 的 NSD 只在发现启动时枚举一次,重启可让漏报的服务重新出现 */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!running || serviceFound) return
            if (refreshCount >= MAX_REFRESH_COUNT) return
            if (registered && !pendingRestart && !resolving) {
                refreshCount++
                Log.v(TAG, "重启服务发现(第 $refreshCount 次)")
                pendingRestart = true
                try {
                    listener?.let { nsdManager.stopServiceDiscovery(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "停止发现失败(刷新): ${e.message}")
                    pendingRestart = false
                }
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        startRetryCount = 0
        refreshCount = 0
        serviceFound = false
        resolving = false
        if (executor.isShutdown) executor = Executors.newSingleThreadExecutor()
        startDiscoveryInternal()
        mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(refreshRunnable)
        if (registered) {
            try {
                listener?.let { nsdManager.stopServiceDiscovery(it) }
            } catch (e: Exception) {
                Log.w(TAG, "停止发现失败: ${e.message}")
            }
        }
        listener = null
        registered = false
        pendingRestart = false
        executor.shutdown()
    }

    private fun startDiscoveryInternal() {
        if (!running || registered) return
        try {
            listener = DiscoveryListener(this)
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "启动发现失败: ${e.message}")
            scheduleStartRetry()
        }
    }

    /** 发现启动失败有限重试(此前一次失败即放弃,整轮发现窗口白等) */
    private fun scheduleStartRetry() {
        if (!running) return
        if (startRetryCount >= MAX_START_RETRY) {
            Log.w(TAG, "发现启动重试达上限($MAX_START_RETRY 次)")
            return
        }
        startRetryCount++
        mainHandler.postDelayed({
            if (running && !registered) startDiscoveryInternal()
        }, START_RETRY_DELAY_MS)
    }

    private fun onDiscoveryStart() {
        registered = true
        startRetryCount = 0
    }

    private fun onDiscoveryStop() {
        registered = false
        if (pendingRestart && running) {
            pendingRestart = false
            mainHandler.postDelayed({
                if (running && !registered) startDiscoveryInternal()
            }, RESTART_DELAY_MS)
        }
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        if (resolving) return
        resolving = true
        nsdManager.resolveService(info, ResolveListener(this))
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) {
            serviceFound = false
            observer(-1)
        }
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        // 本机接口枚举与端口探测是阻塞 IO,移出 NSD 回调(主线程)执行
        executor.execute {
            val isLocal = try {
                NetworkInterface.getNetworkInterfaces()
                    .asSequence()
                    .any { networkInterface ->
                        networkInterface.inetAddresses
                            .asSequence()
                            .any { resolvedService.host.hostAddress == it.hostAddress }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "本机接口枚举失败: ${e.message}")
                false
            }
            if (!isLocal || !running) return@execute
            val portFree = isPortAvailable(resolvedService.port)
            if (portFree && running) {
                mainHandler.post {
                    if (running && !serviceFound) {
                        serviceFound = true
                        serviceName = resolvedService.serviceName
                        mainHandler.removeCallbacks(refreshRunnable)
                        observer(resolvedService.port)
                    }
                }
            }
        }
    }

    private fun isPortAvailable(port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")

            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")

            // 未注册成功即失败,复位后走有限重试
            adbMdns.registered = false
            adbMdns.scheduleStartRetry()
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")

            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")

            // 停止失败若不复位 registered,下次 start() 会跳过 discoverServices 造成死锁
            adbMdns.registered = false
            adbMdns.pendingRestart = false
            if (adbMdns.running) {
                adbMdns.mainHandler.postDelayed({
                    if (adbMdns.running && !adbMdns.registered) adbMdns.startDiscoveryInternal()
                }, RESTART_DELAY_MS)
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {
            Log.v(TAG, "onResolveFailed: ${nsdServiceInfo.serviceName}, $i")

            adbMdns.resolving = false
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceResolved: ${nsdServiceInfo.serviceName}")

            adbMdns.resolving = false
            adbMdns.onServiceResolved(nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"

        /** 发现窗口内周期重启:每 2s 一次、上限 6 次(覆盖 12s 发现窗口),找到即停 */
        const val REFRESH_INTERVAL_MS = 2_000L
        const val MAX_REFRESH_COUNT = 6
        const val START_RETRY_DELAY_MS = 500L
        const val MAX_START_RETRY = 5
        const val RESTART_DELAY_MS = 100L
    }
}
