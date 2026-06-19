# 全量代码审查与修复计划

## 修改点1：受限设置弹窗问题

### 根因分析

Android 的「受限设置 (Restricted Settings)」机制由 AOSP 框架在系统级触发，判断条件为：

```
应用安装来源是非信任渠道（侧载）+ 应用声明了敏感权限
```

**敏感权限白名单（AOSP 源码 `RestrictedPreferenceHelper.java`）**：
- `BIND_ACCESSIBILITY_SERVICE` ─ 无障碍服务
- `BIND_NOTIFICATION_LISTENER_SERVICE` ─ 通知监听服务

**当前项目 manifest 中这两项权限均已被移除**，且 `build.gradle.kts` 无任何无障碍依赖。

**弹窗仍出现的原因**：前一个版本已经注册了无障碍服务到系统设置中。用户安装新 APK 时，Android 包管理器会保留旧的服务注册信息，即使新 APK 的 manifest 已移除该服务，系统仍基于**安装历史**显示限制。

**解决方案**：需要在安装新版本前**完全卸载旧版本**（清除系统缓存的服务注册），然后重新安装。代码层面无需改动。

此外，部分国产 ROM（MIUI/HyperOS、ColorOS、OriginOS）的受限设置范围更广，可能额外检查 `SYSTEM_ALERT_WINDOW` 和 `REQUEST_INSTALL_PACKAGES`。但这两个权限是本应用核心功能所必需，无法移除。

---

## 修改点2：开机自启动功能

**结论：开机自启动功能完全正常，不依赖无障碍服务，不应删除。**

`BootReceiver` 的启动链路：
```
系统发送 BOOT_COMPLETED → BootReceiver.onReceive()
  → 读取 prefs["boot_auto_start"]（默认 true）
  → 读取 prefs["floating_was_running"]（上次悬浮窗是否运行中）
  → 调用 FloatingWindowService.start(context)
```

此链路不涉及任何无障碍组件，移除无障碍服务对其无影响。`AboutScreen.kt` 中的 `BootSection` 开关正常工作。

---

## 修改点3：代码 Bug 修复与质量提升

### Bug 1：`FloatingWindowService.kt` — `onTaskRemoved` 使用错误的 PendingIntent API

**位置**：第 155 行
```kotlin
PendingIntent.getService(...)  // ❌ 非前台，重启后可能不满足 startForeground 时限
```
**修复**：
```kotlin
PendingIntent.getForegroundService(...)  // ✅ 与前次修改的心跳保持一致
```

### Bug 2：`AboutScreen.kt` — 未使用的导入

**位置**：第 32-33 行
```kotlin
import kotlinx.coroutines.Dispatchers   // ❌ 未使用（原 LaunchedEffect 中 withContext(Dispatchers.IO) 已删除）
import kotlinx.coroutines.withContext   // ❌ 未使用
```
**修复**：删除这两行导入。

### Bug 3：`MainActivity.kt` + `AboutScreen.kt` — 硬编码版本号

**位置**：`MainActivity.kt:51` 和 `AboutScreen.kt:81`
```kotlin
UpdateChecker.check("1.59")  // ❌ 硬编码，版本升级时需手动修改
```
**修复**：
```kotlin
UpdateChecker.check(BuildConfig.VERSION_NAME)  // ✅ 自动同步 build.gradle.kts 中的 versionName
```

### 代码质量 4：`AboutScreen.kt` — 误导性注释

**位置**：第 38 行
```kotlin
// 保活状态（已移除无障碍保活，仅保留开机自启）
```
**修复**：更新为：
```kotlin
// 开机自启状态
```

### 代码质量 5：`FloatingWindowService.kt` — `isRunning` 使用 `var by lazy` 不恰当

**位置**：第 42 行
```kotlin
var isRunning by lazy { false }  // ❌ var + by lazy 语义不清晰
```
**修复**：改为普通变量：
```kotlin
companion object {
    var isRunning = false
        private set
}
```
或保持为 top-level 变量：
```kotlin
@Volatile
private var isRunning = false
```
推荐使用 `@Volatile` + companion object 方案，确保多线程可见性。

---

## 修改点4：Warning 修复

### Warning 1：`AboutScreen.kt` 未使用的导入
同 Bug 2，删除 `Dispatchers` 和 `withContext` 导入。

### Warning 2：`FloatingWindowView.kt` — `@Suppress("MissingPermission")` 可优化
**位置**：第 252 行
```kotlin
@Suppress("MissingPermission")
val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
```
`VIBRATE` 是普通权限（非 dangerous），Android 12+ 不会对此产生 lint 警告。`@Suppress` 可以移除，但如果保留也无害。**保守处理：保留不删。**

---

## 需修改的文件清单

| 序号 | 文件 | 操作 | 改动 |
|------|------|------|------|
| 1 | `FloatingWindowService.kt` | Bug 修复 | `getService` → `getForegroundService`（第 155 行）；`isRunning` 改为 companion object |
| 2 | `AboutScreen.kt` | 清理 | 删除 `Dispatchers`/`withContext` 导入；更新注释；版本号改用 `BuildConfig.VERSION_NAME` |
| 3 | `MainActivity.kt` | Bug 修复 | 版本号改用 `BuildConfig.VERSION_NAME` |

## 不修改的文件

| 文件 | 理由 |
|------|------|
| `BootReceiver.kt` | 功能正常，无依赖无障碍 |
| `AndroidManifest.xml` | 无障碍引用已清除，无需改动 |
| `HomeScreen.kt` | 无 bug，无 warning |
| `AppNavigation.kt` | 无 bug，无 warning |
| `BatteryMonitor.kt` | 无 bug，无 warning |
| `ShizukuHelper.kt` | 无 bug，无 warning |
| `UpdateChecker.kt` | 无 bug |
| `UpdateDownloadDialog.kt` | 无 bug |

## 验证步骤

1. 编译通过，**零 warning**
2. 卸载旧版本 APK → 安装新版本 → 确认无障碍设置列表无「神奇悬浮窗」
3. 确认「部分功能已被限制」弹窗不再出现
4. 开机自启开关正常工作
5. 打开 App → 自动检测更新（使用 `BuildConfig.VERSION_NAME`）
6. 从最近任务划掉 → 悬浮窗 1 秒后恢复（`getForegroundService` 生效）