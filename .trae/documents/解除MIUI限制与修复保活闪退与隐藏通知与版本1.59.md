# 实施计划：解除MIUI限制 + 修复保活闪退 + 隐藏通知 + 版本升级1.59 + 更新README

## 概述

本计划解决5个问题：
1. **解除MIUI应用限制**：改进 `AppRestrictionHelper`，增加限制检测和多种反射尝试
2. **修复保活闪退 + 开关状态不一致**：根因是 `enableViaSystemSettings()` 阻塞4秒期间 `onUserLeaveHint()` 杀死Activity；开关用 `keepaliveEnabled` 而非实际运行状态
3. **隐藏"正在其他应用上层"通知**：通知通道改为 `IMPORTANCE_MIN`，删除旧通道重建
4. **版本号改为1.59**：7处版本引用全部修改
5. **更新README.md + git提交（不推送）**：新增v1.59版本信息

## 当前状态分析

### 问题1：MIUI应用限制

- `AppRestrictionHelper.kt` 已有三级策略：反射→Shizuku→手动引导
- 反射尝试 `AppOpsManager.setMode(int,int,String,int)`，但该方法标注 `@hide`，在Android 14+可能被非SDK接口限制阻止
- 当前未检测限制是否实际存在，盲目尝试解除
- **改进方向**：增加 `unsafeCheckOpNoThrow` 检测限制状态；尝试 `setUidMode` 作为替代反射方法；改进手动引导的MIUI路径

### 问题2：保活闪退 + 开关不一致

**闪退根因**（代码路径 `KeepaliveManager.enableViaSystemSettings()` → `AboutScreen.onKeepaliveToggle`）：
1. 用户点击保活开关 → `keepaliveEnabled = true` → `scope.launch { toggleKeepalive() }`
2. `enableViaSystemSettings()` 在 `Dispatchers.Main` 中 `startActivity(ACCESSIBILITY_SETTINGS)`
3. App 进入后台 → `MainActivity.onUserLeaveHint()` 触发
4. `isLaunchingExternal` 为 `false`（KeepaliveManager 直接启动Intent，未通知MainActivity）
5. `hide_recents` 默认 `true` → `finishAndRemoveTask()` 销毁Activity
6. 协程被取消（`rememberCoroutineScope` 绑定Composition）
7. 用户从无障碍设置返回 → Activity已销毁 → 闪退至桌面

**开关不一致根因**：
- 开关 `checked = keepaliveEnabled`（用户意图），状态指示灯用 `keepaliveRunning`（实际运行）
- Activity被销毁后重建，`keepaliveEnabled` 从prefs读取（仍为false，因为协程被取消未保存）
- 但 `keepaliveRunning` 可能为true（用户在设置页手动开启了服务）
- 结果：指示灯显示"保活中"但开关显示OFF

### 问题3："正在其他应用上层"通知

- `FloatingWindowService` 使用 `TYPE_APPLICATION_OVERLAY` 显示悬浮窗
- 通知通道 `IMPORTANCE_LOW`，通知文本"悬浮窗运行中"
- Android 13+ 对使用 `TYPE_APPLICATION_OVERLAY` 的App会显示系统级"正在其他应用上层"通知
- 该通知是系统通知，App无法直接移除
- **可行方案**：将通知通道改为 `IMPORTANCE_MIN`（不在状态栏显示，仅通知栏折叠）；删除旧通道重建（通道importance创建后不可更改）

### 问题4：版本号1.59

7处引用：
1. `app/build.gradle.kts:56` — `versionCode = 21` → `22`
2. `app/build.gradle.kts:57` — `versionName = "1.58"` → `"1.59"`
3. `MainActivity.kt:44` — `UpdateChecker.check("1.58")` → `"1.59"`
4. `AboutScreen.kt:109` — `UpdateChecker.check("1.58")` → `"1.59"`
5. `AboutScreen.kt:290` — `Text("v1.58"` → `Text("v1.59"`
6. `AboutScreen.kt:319` — `Text("v1.58"` → `Text("v1.59"`
7. `UpdateChecker.kt:36` — 注释 `"1.58"` → `"1.59"`
8. `UpdateChecker.kt:80` — `User-Agent", "BatteryFloating/1.58"` → `"1.59"`

### 问题5：README.md

- 当前版本行：`v1.58  (当前版本)` 在第95行
- 需在其上方新增 `v1.59` 版本信息
- README.md 第4行版本号 `1.58` → `1.59`

## 修改方案

### 修改1：`AppRestrictionHelper.kt` — 增加限制检测 + 多反射方法

**文件**：`app/src/main/java/com/example/batteryfloat/util/AppRestrictionHelper.kt`

**改动**：
1. 新增 `isRestricted(context): Boolean` 方法 — 通过反射 `AppOpsManager.unsafeCheckOpNoThrow` 检测 `OP_ACCESS_RESTRICTED_SETTINGS` 是否为 `MODE_ERRORED`
2. 在 `liftRestriction()` 开头先检测，若未受限直接返回 `Success("无需解除")`
3. 反射策略增加 `setUidMode(int,int,int)` 作为 `setMode` 的替代方法
4. 改进手动引导：提供MIUI精确路径 `设置→应用设置→应用管理→神奇悬浮窗→右上角"..."→允许受限制的设置`

### 修改2：`KeepaliveManager.kt` — 修复闪退 + 非阻塞设计

**文件**：`app/src/main/java/com/example/batteryfloat/util/KeepaliveManager.kt`

**改动**：
1. 新增 `@Volatile var isOpeningExternal = false` 静态字段
2. `enableViaSystemSettings()` 和 `disableViaSystemSettings()` 中：
   - 设置 `isOpeningExternal = true` 后再 `startActivity`
   - **移除4秒阻塞等待循环** — 直接返回 `false`（表示"需用户手动操作，尚未确认"）
   - 不再阻塞协程，避免Activity被杀导致协程取消
3. `toggleKeepalive()` 返回值语义调整：
   - `true` = 已确认成功（Shizuku静默启用成功）
   - `false` = 需用户手动操作或失败（已打开设置页）

### 修改3：`MainActivity.kt` — 防止打开设置时杀死Activity

**文件**：`app/src/main/java/com/example/batteryfloat/MainActivity.kt`

**改动**：
1. `onUserLeaveHint()` 中增加检查 `KeepaliveManager.isOpeningExternal`
2. 若 `isOpeningExternal == true`，跳过 `finishAndRemoveTask()`（与 `isLaunchingExternal` 同等处理）
3. `onResume()` 中重置 `KeepaliveManager.isOpeningExternal = false`

### 修改4：`AboutScreen.kt` — 开关状态与实际运行一致 + 版本号

**文件**：`app/src/main/java/com/example/batteryfloat/ui/AboutScreen.kt`

**改动A：修复开关状态一致性**
1. `KeepaliveSection` 的 Switch `checked` 改为绑定 `keepaliveRunning`（实际运行状态）而非 `keepaliveEnabled`
2. `onKeepaliveToggle` 回调改为：
   - 不再立即设置 `keepaliveEnabled = enabled`
   - 调用 `toggleKeepalive()` 后，根据返回值和 `KeepliveA11yService.isRunning` 更新 `keepaliveRunning`
   - `keepaliveEnabled` 仅用于记录prefs，不影响开关显示
3. 添加生命周期观察者：`ON_RESUME` 时刷新 `keepaliveRunning = KeepliveA11yService.isRunning`（用户从设置页返回后自动刷新）

**改动B：版本号1.59**
- 第109行：`UpdateChecker.check("1.58")` → `"1.59"`
- 第290行：`Text("v1.58"` → `Text("v1.59"`
- 第319行：`Text("v1.58"` → `Text("v1.59"`

### 修改5：`FloatingWindowService.kt` — 隐藏通知

**文件**：`app/src/main/java/com/example/batteryfloat/service/FloatingWindowService.kt`

**改动**：
1. `createNotificationChannel()`：
   - 先删除旧通道 `manager.deleteNotificationChannel(CHANNEL_ID)`
   - 创建新通道 `IMPORTANCE_MIN`（替代 `IMPORTANCE_LOW`）
   - 设置 `setSound(null, null)`、`enableVibration(false)`、`setShowBadge(false)`
2. `buildForegroundNotification()`：
   - 添加 `.setSilent(true)`（Android 12+静默通知）
   - 添加 `.setVisibility(NotificationCompat.VISIBILITY_SECRET)`（锁屏隐藏）
   - 优先级改为 `PRIORITY_MIN`
3. 通道ID改为 `"battery_temp_channel_v2"`（确保新通道生效，旧通道残留不影响）

### 修改6：版本号 — `build.gradle.kts` + `MainActivity.kt` + `UpdateChecker.kt`

**文件**：`app/build.gradle.kts`
- `versionCode = 21` → `22`
- `versionName = "1.58"` → `"1.59"`

**文件**：`app/src/main/java/com/example/batteryfloat/MainActivity.kt`
- 第44行：`UpdateChecker.check("1.58")` → `"1.59"`

**文件**：`app/src/main/java/com/example/batteryfloat/update/UpdateChecker.kt`
- 第36行注释：`"1.58"` → `"1.59"`
- 第80行：`"BatteryFloating/1.58"` → `"BatteryFloating/1.59"`

### 修改7：`README.md` — 新增v1.59版本信息

**文件**：`README.md`

**改动**：
1. 第4行：`版本: 1.58` → `版本: 1.59`
2. 第95行上方新增：
```
  v1.59  (当前版本)
           解除MIUI应用限制(多策略:反射+Shizuku+引导); 修复保活闪退(非阻塞设计+防Activity销毁)
           保活开关状态与实际运行一致; 隐藏"正在其他应用上层"通知(IMPORTANCE_MIN)
           横竖屏切换位置偏差修复; 代码质量优化(内存泄漏/资源泄漏/弃用API)

  v1.58
           版本号升级、外观默认值调整、隐藏后台默认开启、首次启动权限引导、WebView内置浏览、双源更新检测
```
3. 原 `v1.58  (当前版本)` 改为 `v1.58`（去掉"当前版本"标记）

## 假设与决策

1. **反射 `setMode` 可能仍被阻止**：Android 14+非SDK接口限制可能阻止反射调用。方案设计为自动降级，不保证反射一定成功
2. **`IMPORTANCE_MIN` 不能完全去除系统通知**：Android 13+的"正在其他应用上层"是系统级通知，`IMPORTANCE_MIN`只能最小化App自身通知。若系统通知仍存在，需用户手动在通知设置中关闭
3. **保活开关改为绑定 `keepaliveRunning`**：用户点击开关后，若Shizuku不可用则打开设置页，开关不会立即变为ON（因为服务尚未运行），用户从设置页返回后`ON_RESUME`刷新状态。这确保开关始终与实际状态一致
4. **`isOpeningExternal` 使用静态字段**：简单可靠，`KeepaliveManager`是object（单例），`MainActivity`直接读取
5. **通道ID改为 `_v2`**：Android通知通道importance创建后不可更改，必须用新ID重建
6. **git提交不推送**：用户明确要求不推送远程仓库

## 验证步骤

1. **编译验证**：`.\gradlew.bat assembleRelease` 通过（只构建正式版）
2. **MIUI限制解除验证**：
   - 侧载安装后出现限制提示
   - 关于页点击"一键解除限制"
   - 若反射成功：限制消失；若失败：跳转设置页引导
3. **保活闪退验证**：
   - 点击保活开关 → 打开无障碍设置 → 按返回键
   - 确认不闪退，正常返回应用
   - 确认开关状态与实际保活状态一致
4. **通知验证**：
   - 启动悬浮窗后检查通知栏
   - 确认App通知不在状态栏显示（仅通知栏折叠）
5. **版本号验证**：
   - APK版本信息显示1.59
   - 更新检测使用1.59作为当前版本
6. **README验证**：v1.59信息正确显示
7. **Git验证**：`git log` 确认提交存在，`git status` 确认未推送
