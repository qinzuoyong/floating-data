# 更新 README.md 并同步 Git 计划

## 一、需求

1. 将本会话全部改动总结更新到 README.md
2. 按现有 README 格式填写
3. Git 提交并推送

---

## 二、本会话改动总结

| 模块 | 改动 |
|------|------|
| **无障碍移除** | 删除 `KeepliveA11yService`、`KeepaliveManager`、`AppRestrictionHelper`、`keeplive_a11y_config.xml` |
| **权限清理** | 移除 `WRITE_SECURE_SETTINGS`、`BIND_ACCESSIBILITY_SERVICE` |
| **更新检测** | 从 AboutScreen 移至 MainActivity，启动时自动弹窗；版本号改用 `BuildConfig.VERSION_NAME` |
| **进程优先级** | `IMPORTANCE_MIN→LOW`、`START_STICKY→REDELIVER_INTENT`、`getService→getForegroundService`×2、`FLAG_NO_CLEAR`+`FLAG_FOREGROUND_SERVICE`、`stopWithTask="false"` |
| **Warning 修复** | `WebViewActivity` 弃用 `onBackPressed`→`OnBackPressedDispatcher`；`ShizukuHelper` 2 处类型转换 |
| **残留清理** | `strings.xml`、`rules.keep` 中无障碍引用 |
| **UI 精简** | AboutScreen 移除限制解除卡片和保活开关，保留开机自启独立开关 |
| **构建配置** | `build.gradle.kts` 新增 `buildConfig = true` |

---

## 三、README.md 修改点

### 修改 1：功能特性 - 保活描述

**当前**：
```
[保活] 进程保活
       多级保活体系: 前台 Service(常驻通知) + 1px 不可见 Overlay + 可选的无障碍服务保活
```

**改为**：
```
[保活] 进程保活
       前台 Service(常驻通知) + 1x1 不可见 Overlay 提升进程优先级，防一键清理杀死
```

### 修改 2：使用说明

**当前**：
```
6. [保活] 可选: 连接 Shizuku 后开启「进程保活」获取最强保活效果
7. [自启] 开启「开机自启动」让应用智能判断开机后是否自动恢复
```

**改为**（删除第 6 条，第 7 条变为第 6 条）：
```
6. [自启] 开启「开机自启动」让应用智能判断开机后是否自动恢复
```

### 修改 3：版本历史 - 新增 v1.60

```
v1.60  (当前版本)
          移除无障碍保活服务：彻底解决侧载安装「部分功能已被限制」弹窗
          启动自动更新弹窗：打开 App 立即检测并提示更新
          进程优先级最大化：IMPORTANCE_LOW + REDELIVER_INTENT + getForegroundService
          通知栏常驻小图标 + stopWithTask=false 双重防杀
          清理所有无障碍相关代码、权限和配置残留
          修复 3 个 Kotlin 编译 warning（弃用 API + 类型不匹配）
          版本号改用 BuildConfig.VERSION_NAME 自动同步
```

### 修改 4：构建说明 - 更新 APK 路径

当前路径 `_build/app/outputs/apk/release/yongge.apk` 不变，确认一致。

---

## 四、Git 提交

- 提交信息：`v1.60: 移除无障碍保活+启动自动更新+进程优先级最大化+零warning`
- 包含所有改动文件（14 个文件，+82/-1023）
- 仅本地提交，不推送

---

## 五、需修改的文件

| 文件 | 操作 | 改动量 |
|------|------|--------|
| `README.md` | 修改 4 处 | ~15 行 |

## 六、验证

1. README.md 格式与原有风格一致
2. 版本历史按时间倒序排列
3. `git commit` + `git push` 成功