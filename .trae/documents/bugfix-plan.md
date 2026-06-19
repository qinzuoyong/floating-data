# 问题修复计划

## 代码审计结果

| # | 严重度 | 文件 | 问题 |
|---|--------|------|------|
| 1 | 中 | `ShizukuHelper.kt` | 资源泄漏：异常路径未关闭 stream/process |
| 2 | 低 | `BatteryMonitor.kt` | `isRunning` 缺少 `@Volatile` |
| 3 | 低 | `ApkDownloader.kt` | check-then-act 非原子 |
| 4 | 低 | `FloatingWindowService.kt` | `heartbeatPendingIntent!!` 强制断言 |
| 5 | 低 | `ShizukuHelper.kt` | 多处 `!!` 反射 Method 断言 |
| 6 | 低 | `MainActivity.kt` | `finishAndRemoveTask` API 34 弃用 |
| 7 | 低 | `AppearanceScreen.kt` | 滑块拖拽频繁写 SharedPreferences |

## 修复计划

### 修复 1：`ShizukuHelper.kt` — 资源泄漏（中）

**原因**：`readTemperature()` 和 `executeCommand()` 中异常路径不关闭 stream/process。

**修复**：将 `outputStream.write()`、`reader.readLine()` 等操作包裹在 try-with-resources 风格的 try-finally 中，确保 `outputStream.close()`、`inputStream.close()`、`process.destroy()` 在 finally 块执行。

### 修复 2：`BatteryMonitor.kt` — `@Volatile`（低）

**修复**：`private var isRunning = false` → `@Volatile private var isRunning = false`

### 修复 3：`FloatingWindowService.kt` — `!!` 断言（低）

**修复**：`heartbeatPendingIntent!!` → `heartbeatPendingIntent ?: return` 安全调用

### 不修复的项目

| # | 理由 |
|---|------|
| 3 | check-then-act 在 UI 线程单调度器下不触发，修复成本高于收益 |
| 5 | 反射 Method 缓存由 `ensureReflectionCache()` 保证初始化，业务逻辑正确 |
| 6 | `finishAndRemoveTask` 在 API 34 可用但弃用，`overrideActivityTransition` 替代方案需 API 34+ 且语义不同，当前实现符合预期 |
| 7 | `apply()` 异步+合并，实际性能影响可忽略，引入 debounce 增加复杂度 |

## 修复后流程

```
修复 3 个问题 → 构建 APK → 启动 Android Studio → 用户实机测试 → 反馈问题 → 循环修复
```

## 修改文件

| 文件 | 改动 |
|------|------|
| `ShizukuHelper.kt` | try-finally 包裹资源清理 |
| `BatteryMonitor.kt` | +`@Volatile` |
| `FloatingWindowService.kt` | `!!` → `?: return` |