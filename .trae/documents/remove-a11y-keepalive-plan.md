# 移除无障碍 + 限制解除 + 修复自动更新 完整计划

## 核心结论：移除无障碍保活服务后，「受限设置」弹窗不会再出现

Android 的「受限设置」弹窗**仅针对无障碍服务 (AccessibilityService) 和通知监听服务 (NotificationListenerService)**。移除无障碍组件后，系统中不再有可限制的敏感组件，弹窗自然不会弹出。

---

## 需删除的文件（4 个）

### 1. `service/KeepliveA11yService.kt`
无障碍保活服务，使用 TYPE_ACCESSIBILITY_OVERLAY 1x1 窗口保活。移除后进程存活率下降，但 FloatingWindowService 自带的 1x1 TYPE_APPLICATION_OVERLAY 保活不受影响。

### 2. `res/xml/keeplive_a11y_config.xml`
KeepliveA11yService 的 XML 配置元数据。

### 3. `util/KeepaliveManager.kt`
保活管理器，100% 围绕 KeepliveA11yService 运作（启用/禁用/Shizuku 静默开关/ADB 配置）。删除后 `build.gradle.kts` 中无需额外改动。

### 4. `util/AppRestrictionHelper.kt`
ACCESS_RESTRICTED_SETTINGS 解除工具类。移除无障碍后无需解除受限设置，整个类无调用方。

---

## 需修改的文件（3 个）

### 5. `AndroidManifest.xml`

**改动 1** — 删除 KeepliveA11yService 的 service 声明（第 52-64 行）：
```xml
<!-- 整个 <service android:name=".service.KeepliveA11yService"... 块删除 -->
```

**改动 2** — 删除 BIND_ACCESSIBILITY_SERVICE 权限（第 17-19 行）：
```xml
<!-- <uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE"... 删除 -->
```

**改动 3** — 删除 WRITE_SECURE_SETTINGS 权限（第 14-16 行）：
> 该权限仅用于 KeepaliveManager 通过 Shizuku 写入无障碍服务列表和 AppOps 配置。ShizukuHelper 本身通过反射执行 shell 命令，不依赖此权限声明。删除后保持清单干净。

---

### 6. `ui/AboutScreen.kt`

**改动 1** — 删除导入：
- 删除第 39 行 `import ...KeepliveA11yService`
- 删除第 44 行 `import ...AppRestrictionHelper`
- 删除第 45 行 `import ...KeepaliveManager`

**改动 2** — 删除第 67-68 行状态变量：
- `var keepaliveEnabled`
- `var keepaliveRunning`

**改动 3** — 删除第 71-86 行生命周期观察者（保活状态同步逻辑）。

**改动 4** — 删除第 106 行 `UserGuideCard()` 调用（移除限制解除卡片）。

**改动 5** — 删除第 108-124 行 `KeepaliveSection(...)` 调用及回调。

**改动 6** — 删除第 163-234 行 `UserGuideCard()` 整个函数。

**改动 7** — 删除第 257-295 行 `KeepaliveSection()` 整个函数。

**改动 8** — 新增自动更新检测（解决"不能自动检测更新"问题）：
```kotlin
// AboutScreen 内容中第 105 行（Text("关于") 之后），添加：
// 自动检查更新（首次渲染时触发一次）
LaunchedEffect(Unit) {
    if (!isChecking) {
        isChecking = true
        val info = withContext(Dispatchers.IO) { UpdateChecker.check("1.59") }
        isChecking = false
        if (info.hasUpdate) {
            updateVersion = info.latestVersion
            updateApkUrl = info.apkDownloadUrl
            showUpdateDialog = true
        }
    }
}
```

最后保留的组件顺序（从上到下）：
1. 标题 "关于"
2. PermissionGuideCard（悬浮窗权限 + 电池优化 — 保留）
3. BootSection（开机自启动开关只保留开机自启，删除进程保活）
4. UpdateCheckCard（检查更新按钮保留 + 自动检测按需弹窗）
5. AboutInfoCard（GitHub/Gitee 链接）

---

### 7. `MainActivity.kt`

**改动 1** — 删除第 21 行导入 `import ...KeepaliveManager`

**改动 2** — 修改 `onResume()`（第 81-87 行）：
```kotlin
override fun onResume() {
    super.onResume()
    isLaunchingExternal = false
}
// 删除 KeepaliveManager.isOpeningExternal = false
// 删除 KeepaliveManager.isKeepaliveRunning()
```

**改动 3** — 修改 `onUserLeaveHint()`（第 103-115 行）：
```kotlin
if (isLaunchingExternal || KeepaliveManager.isOpeningExternal) {
```
改为：
```kotlin
if (isLaunchingExternal) {
```

**改动 4** — 修改自动更新逻辑（第 42-50 行）：
当前逻辑：后台检查更新仅保存到 prefs，不显示给用户。
改为后台检查后直接弹窗：
```kotlin
lifecycleScope.launch {
    val info = UpdateChecker.check("1.59")
    if (info.hasUpdate) {
        prefs.edit()
            .putString("latest_version", info.latestVersion)
            .putString("apk_url", info.apkDownloadUrl)
            .putBoolean("has_update_available", true)
            .apply()
    }
}
```
然后在 AboutScreen 中读取 `has_update_available` 并自动弹出更新对话框。
**（与 AboutScreen 改动 8 结合，二选一即可，推荐 AboutScreen 方案）**

**推荐**：MainActivity 中只做版本预检（写入 prefs），AboutScreen 首次自动检查更新时从 prefs 读取。或者干脆将自动检测逻辑全部移至 AboutScreen 的 `LaunchedEffect` 中，MainActivity 中删除第 42-50 行逻辑。**二选一，推荐 AboutScreen 方案**。

---

## 不修改的文件

| 文件 | 理由 |
|------|------|
| `service/FloatingWindowService.kt` | 核心悬浮窗服务，自带 1x1 TYPE_APPLICATION_OVERLAY 保活，无无障碍依赖 |
| `view/FloatingWindowView.kt` | 悬浮窗视图，无无障碍引用 |
| `monitor/BatteryMonitor.kt` | 电池温度监控，通过 ShizukuHelper 读取 sysfs，无无障碍引用 |
| `receiver/BootReceiver.kt` | 仅依赖 FloatingWindowService，无障碍保活移除不影响开机自启 |
| `shizuku/ShizukuHelper.kt` | 反射 Shizuku 执行命令，KeepaliveManager 删除后仍被 BatteryMonitor 使用 |
| `update/UpdateChecker.kt` | 版本检测工具类，不需要改动 |
| `update/ApkDownloader.kt` | APK 下载器，不需要改动 |
| `app/build.gradle.kts` | 无需要移除的依赖，keepalive 相关代码无外部库依赖 |

---

## 改动范围总结

| 操作 | 文件 | 说明 |
|------|------|------|
| 🗑 删除 | `service/KeepliveA11yService.kt` | 无障碍保活服务 |
| 🗑 删除 | `res/xml/keeplive_a11y_config.xml` | 无障碍配置 |
| 🗑 删除 | `util/KeepaliveManager.kt` | 保活管理器 |
| 🗑 删除 | `util/AppRestrictionHelper.kt` | 限制解除工具 |
| ✏️ 修改 | `AndroidManifest.xml` | 移除 service 声明 + 2 个权限 |
| ✏️ 修改 | `ui/AboutScreen.kt` | 移除限制卡片、保活UI，新增自动更新检测 |
| ✏️ 修改 | `MainActivity.kt` | 移除 KeepaliveManager 引用 |

## 验证步骤

1. 编译通过，无编译错误
2. 侧载安装 APK，确认无障碍设置列表中没有「神奇悬浮窗」
3. 确认系统不再弹出「部分功能已被限制」弹窗
4. 悬浮窗正常显示和运行
5. 开机自启功能正常
6. 关于页面打开后自动检测更新，有更新则弹出更新对话框
7. Shizuku 温度读取功能正常