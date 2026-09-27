package com.example.batteryfloat.ui.family

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.R
import com.example.batteryfloat.family.FamilyMember
import com.example.batteryfloat.family.FamilyStore
import com.example.batteryfloat.service.FamilyLocationService
import com.example.batteryfloat.ui.theme.DesignSystem

/** 家人 Tab 内部路由 */
private sealed interface FamilyRoute {
    data object List : FamilyRoute
    data object Add : FamilyRoute
    data object Alerts : FamilyRoute
    data class Map(val member: FamilyMember) : FamilyRoute
}

/**
 * 家人位置共享主页
 *
 * 自包含：定位/通知权限请求、前台服务启停、家庭码加入（AddFamilyScreen）、
 * 成员列表（FamilyListContent 及其分区块）、隐私开关。
 */
@Composable
fun FamilyScreen(
    prefs: SharedPreferences,
    onBeforeExternalIntent: () -> Unit = {}
) {
    val context = LocalContext.current
    val store = remember { FamilyStore.get(context) }
    val members by store.members.collectAsState()
    val connection by FamilyLocationService.connection.collectAsState()
    // 临时提示（对方不在线/信令未连接等），展示数秒后自动清除
    val notice by FamilyLocationService.notice.collectAsState()

    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(4_000L)
            FamilyLocationService.clearNotice()
        }
    }

    var route by remember { mutableStateOf<FamilyRoute>(FamilyRoute.List) }
    var serviceOn by remember { mutableStateOf(isServiceRunning()) }

    // 后台定位权限（系统要求分段授权：前台定位授予后单独请求"始终允许"）。
    // 前台服务被系统重启拉起（app 不在前台）时，无后台定位权限将无法定位应答。
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        prefs.edit().putBoolean(PrefsKeys.FAMILY_BG_LOC_ASKED, true).apply()
        // 拒绝时明确提示后果，避免用户以为已授权（后台无法应答家人位置请求）
        if (!granted) {
            Toast.makeText(
                context,
                context.getString(R.string.family_bg_loc_denied_hint),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun maybeRequestBackground() {
        val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val bgGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val asked = prefs.getBoolean(PrefsKeys.FAMILY_BG_LOC_ASKED, false)
        if (fineGranted && !bgGranted && !asked) {
            onBeforeExternalIntent()
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    // 定位 + 通知权限（首次进入请求；后台定位用于常驻响应）
    // 权限弹窗是独立系统 Activity，会触发 MainActivity.onUserLeaveHint；
    // 必须先经 onBeforeExternalIntent 标记"外部跳转"，避免 HIDE_RECENTS 把应用 finish 掉
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 授权后：未加入家庭则引导加入，已加入则自动开启服务。
        // 判定必须落在"定位权限"：仅授予通知权限时视为未授权，否则会启动失败并弹出停用通知。
        val locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) {
            val code = prefs.getString(PrefsKeys.FAMILY_CODE, "") ?: ""
            if (code.isBlank()) {
                route = FamilyRoute.Add
            } else {
                FamilyLocationService.start(context)
                serviceOn = true
                maybeRequestBackground()
            }
        }
    }
    LaunchedEffect(Unit) {
        // 前台定位 + 通知权限先行；后台定位在前台授权后单独分段引导（见 maybeRequestBackground）
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ).filter { p ->
            androidx.core.content.ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            onBeforeExternalIntent()
            permissionLauncher.launch(missing.toTypedArray())
        } else if ((prefs.getString(PrefsKeys.FAMILY_CODE, "") ?: "").isNotBlank() &&
            !isServiceRunning() && prefs.getBoolean(PrefsKeys.FAMILY_WAS_RUNNING, false)
        ) {
            // 已授权且已加入家庭：自动开启位置共享服务，保证可被家人请求到位置。
            // 必须以 FAMILY_WAS_RUNNING 为门控（用户主动停止时清除，见 ACTION_STOP）：
            // 本 LaunchedEffect 每次进入家人 Tab 都会重跑，无此门控会把用户刚关闭
            // 的服务又静默拉起（违背「停止共享」的用户意图）
            FamilyLocationService.start(context)
            serviceOn = true
            maybeRequestBackground()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val r = route) {
            is FamilyRoute.Map -> FamilyMapScreen(
                member = r.member,
                onBack = { route = FamilyRoute.List },
                onRefresh = { FamilyLocationService.requestLocation(context, r.member.uid) }
            )
            FamilyRoute.Add -> AddFamilyScreen(
                onDone = {
                    route = FamilyRoute.List
                    // 加入家庭后自动开启位置共享服务
                    FamilyLocationService.start(context)
                    serviceOn = true
                },
                // 返回只导航（不提交、不启动服务）：与系统返回键走同一入口
                onBack = { route = FamilyRoute.List },
                // 权限弹窗会触发 MainActivity.onUserLeaveHint，需标记外部跳转防 finish
                onBeforeExternalIntent = onBeforeExternalIntent
            )
            FamilyRoute.Alerts -> FamilyAlertScreen(
                context = context,
                members = members.values.toList(),
                onBack = { route = FamilyRoute.List }
            )
            FamilyRoute.List -> FamilyListContent(
                context = context,
                prefs = prefs,
                store = store,
                members = members,
                connection = connection,
                serviceOn = serviceOn,
                onToggleService = { on ->
                    if (on) {
                        FamilyLocationService.start(context)
                        serviceOn = true
                    } else {
                        FamilyLocationService.stop(context)
                        serviceOn = false
                    }
                },
                onAddFamily = { route = FamilyRoute.Add },
                onOpenMap = { route = FamilyRoute.Map(it) },
                onOpenAlerts = { route = FamilyRoute.Alerts },
                onLeaveFamily = {
                    FamilyLocationService.stop(context)
                    store.clearMembers()
                    store.setFamilyCode("")
                    serviceOn = false
                }
            )
        }

        // 临时提示浮层（对方不在线/信令未连接等，数秒后自动消失）
        notice?.let { text ->
            Card(
                shape = RoundedCornerShape(DesignSystem.CornerM),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = DesignSystem.SpacingL)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(
                        horizontal = DesignSystem.CardPadding,
                        vertical = DesignSystem.SpacingS
                    )
                )
            }
        }
    }
}

/**
 * 家人服务是否在运行
 *
 * 使用服务自身维护的进程内标志：ActivityManager.getRunningServices 已废弃，
 * 且其语义在 API 26+ 才收窄为"仅本应用服务"，直接读标志更可靠也更省。
 */
private fun isServiceRunning(): Boolean = FamilyLocationService.isRunning
