# 自动更新检测 + 无障碍残留清理 + 进程优先级提升 计划

## 一、需求概述

1. **每次进入 App 自动检测更新**：打开应用时立即检查并在首页弹出更新对话框，而非仅在"关于"页面触发
2. **无障碍残留清理**：彻底清除上次删除无障碍组件后剩余的引用
3. **进程优先级提升至最高**：防止系统一键清理内存杀死悬浮窗服务，不依赖 ADB/Shizuku

---

## 二、当前状态分析

### 2.1 更新检测现状

- `MainActivity.onCreate()` 第 42-49 行：后台检查更新，但**仅写入 prefs，不弹窗**
- `AboutScreen.kt` 第 60-72 行：`LaunchedEffect(Unit)` 进入关于页面时触发更新检测并弹窗
- 问题：用户进入首页时看不到更新提示，必须手动切换到"关于"页

### 2.2 无障碍残留

Grep 扫描发现 3 处残留：

| 文件 | 行号 | 内容 |
|------|------|------|
| `res/values/strings.xml` | 7 | `<string name="keeplive_a11y_description">...` |
| `keepRules/rules.keep` | 13-14 | `-keep class ...KeepliveA11yService` |
| `shizuku/ShizukuHelper.kt` | 137 | 注释 `// 用于 KeepaliveManager 等模块` |

### 2.3 进程优先级现状

`FloatingWindowService` 已有保活措施：

| 措施 | 代码 | 效果 |
|------|------|------|
| 前台服务 | `startForeground()` | 基础保活 |
| START_STICKY | `onStartCommand` 返回 | 被杀后自动重建 |
| 1x1 OVERLAY 窗口 | `addAliveOverlay()` | 提升 OOM 优先级 |
| AlarmManager 心跳 | 5 分钟周期 | 周期性唤醒 |
| onTaskRemoved | 1 秒延迟重启 | 划掉后恢复 |

**存在的问题**：
- 通知使用 `IMPORTANCE_MIN`（静默、不显示图标），国产 ROM 将其视为"后台服务"并在一键清理时杀死
- 心跳使用 `PendingIntent.getService`（非前台），重启后可能不满足前台服务要求
- 缺少 `stopWithTask="false"` 声明

---

## 三、改动计划

### 改动 1：`MainActivity.kt` — 启动时自动更新弹窗

**改动**：将更新检测从 AboutScreen 移至 MainActivity，在首页直接弹窗。

**具体修改**：
```kotlin
// 在 setContent 之前添加状态变量
setContent {
    BatteryFloatingTheme {
        var showUpdateDialog by remember { mutableStateOf(false) }
        var updateVersion by remember { mutableStateOf("") }
        var updateApkUrl by remember { mutableStateOf("") }
        val downloadState by ApkDownloader.downloadState.collectAsState()

        // 启动时自动检查更新
        LaunchedEffect(Unit) {
            val info = UpdateChecker.check("1.59")
            if (info.hasUpdate) {
                updateVersion = info.latestVersion
                updateApkUrl = info.apkDownloadUrl
                showUpdateDialog = true
            }
        }

        AppNavigation(...)

        // 更新弹窗（全局，任意页面可见）
        if (showUpdateDialog) {
            UpdateDownloadDialog(
                updateVersion, downloadState,
                onStartDownload = { if (updateApkUrl.isNotBlank()) lifecycleScope.launch { ApkDownloader.download(this@MainActivity, updateApkUrl) } },
                onInstall = { (downloadState as? DownloadState.Completed)?.let { ApkDownloader.install(this@MainActivity, it.file) } },
                onDismiss = {
                    showUpdateDialog = false
                    if (downloadState is DownloadState.Completed) ApkDownloader.cleanup(this@MainActivity)
                }
            )
        }
    }
}
```

**同时**：删除 `MainActivity` 第 42-49 行原有的后台静默检查逻辑（已被新逻辑替代）。

---

### 改动 2：`AboutScreen.kt` — 移除冗余的自动检测

**改动**：删除 AboutScreen 中的 `LaunchedEffect(Unit)` 自动检测逻辑（第 60-72 行），只保留手动"检查更新"按钮。

**删除的代码块**：
```kotlin
// 自动检查更新（首次进入关于页面时触发）
LaunchedEffect(Unit) { ... }
```

保留 `UpdateCheckCard` 手动按钮（用户仍可手动触发）。

---

### 改动 3：`res/values/strings.xml` — 删除无障碍字符串

**改动**：删除第 7 行：
```xml
<string name="keeplive_a11y_description">用于提升应用进程存活率的无障碍服务（不处理任何事件，零功耗）</string>
```

---

### 改动 4：`keepRules/rules.keep` — 删除无障碍保留规则

**改动**：删除第 12-14 行：
```
# 无障碍保活服务不被混淆
-keep class com.example.batteryfloat.service.KeepliveA11yService { *; }
-keep class com.example.batteryfloat.service.KeepliveA11yService$* { *; }
```

---

### 改动 5：`shizuku/ShizukuHelper.kt` — 更新注释

**改动**：第 137 行注释从：
```kotlin
// 用于 KeepaliveManager 等模块执行 settings 命令
```
改为：
```kotlin
// 执行 settings 等 shell 命令
```

---

### 改动 6：`FloatingWindowService.kt` — 进程优先级提升（核心改动）

**改动 A**：通知重要性从 `IMPORTANCE_MIN` 提升至 `IMPORTANCE_LOW`

```kotlin
// 第 339 行
NotificationManager.IMPORTANCE_MIN  →  NotificationManager.IMPORTANCE_LOW
```

**原因**：`IMPORTANCE_MIN` 不显示状态栏图标，国产 ROM 一键清理时将其视为"无感知后台服务"直接杀死。`IMPORTANCE_LOW` 显示小图标，让系统识别为"用户可见前台服务"，显著降低被清理概率。

**改动 B**：`START_STICKY` → `START_REDELIVER_INTENT`

```kotlin
// 第 98 行
return START_STICKY  →  return START_REDELIVER_INTENT
```

**原因**：`START_REDELIVER_INTENT` 不仅自动重建 Service，还会重新投递最后一次 Intent，确保重建后悬浮窗和保活覆盖层正确恢复。

**改动 C**：心跳使用 `PendingIntent.getForegroundService`（API 26+）

```kotlin
// 第 178-183 行，scheduleHeartbeat() 方法
PendingIntent.getService(...)  →  PendingIntent.getForegroundService(...)
```

**原因**：`getForegroundService` 启动的 Service 会立即进入前台模式，满足 `startForeground` 的 5 秒时限要求，避免在系统高负载时因 ANR 被杀。

**改动 D**：通知添加 `FLAG_NO_CLEAR` 和 `FLAG_FOREGROUND_SERVICE`

```kotlin
// 第 363 行，buildForegroundNotification() 方法
.setOngoing(true) 后添加：
.setFlag(Notification.FLAG_NO_CLEAR, true)       // 防止通知被滑动清除
.setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)  // 强制执行前台服务标记
```

---

### 改动 7：`AndroidManifest.xml` — 添加 `stopWithTask` 属性

**改动**：在 FloatingWindowService 的声明中添加：
```xml
<service
    android:name=".service.FloatingWindowService"
    android:exported="false"
    android:foregroundServiceType="specialUse"
    android:stopWithTask="false" />
```

**原因**：阻止系统在用户清除最近任务时停止服务。结合 `onTaskRemoved` 的 AlarmManager 重启，形成双重保护。

---

## 四、改动文件清单

| 序号 | 文件 | 操作 | 改动量 |
|------|------|------|--------|
| 1 | `MainActivity.kt` | 修改 | 新增更新弹窗逻辑，删除旧的后台检查 |
| 2 | `AboutScreen.kt` | 修改 | 删除 LaunchedEffect 自动检测 |
| 3 | `res/values/strings.xml` | 修改 | 删除 1 行字符串 |
| 4 | `keepRules/rules.keep` | 修改 | 删除 3 行 |
| 5 | `shizuku/ShizukuHelper.kt` | 修改 | 修改 1 行注释 |
| 6 | `service/FloatingWindowService.kt` | 修改 | 4 处改动（通知优先级/STICKY→REDELIVER/心跳API/通知标志） |
| 7 | `AndroidManifest.xml` | 修改 | +1 属性 |

## 五、不做改动的部分

- 不修改 `build.gradle.kts`（无新增依赖）
- 不修改 `targetSdk/minSdk/compileSdk`
- 不修改签名配置
- 不修改 `HomeScreen`、`AppNavigation`（更新弹窗通过 MainActivity 全局展示）

## 六、验证步骤

1. 编译通过，无编译错误
2. 打开 App → 首页自动弹出更新对话框（如有更新）→ 无更新则正常显示首页
3. 切换到"关于"页 → 手动点击"检查更新"按钮正常工作
4. Grep 搜索 `keeplive|a11y|accessibility` → 无残留
5. 悬浮窗服务运行后，通知栏可见小图标（`IMPORTANCE_LOW` 效果）
6. 一键清理内存 → 悬浮窗不被杀死，或杀死后自动恢复
7. 从最近任务划掉应用 → 悬浮窗 1 秒后自动恢复