# 版本号统一修改为 1.60

## 需修改的文件（5 处）

| 文件 | 行号 | 当前值 | 改为 |
|------|------|--------|------|
| `app/build.gradle.kts` | 56 | `versionCode = 22` | `versionCode = 23` |
| `app/build.gradle.kts` | 57 | `versionName = "1.59"` | `versionName = "1.60"` |
| `README.md` | 4 | `版本: 1.59` | `版本: 1.60` |
| `AboutScreen.kt` | 171 | `"v1.59"` | `"v1.60"` |
| `AboutScreen.kt` | 200 | `"v1.59"` | `"v1.60"` |
| `UpdateChecker.kt` | 36 | `如 "1.59"` | `如 "1.60"` |
| `UpdateChecker.kt` | 80 | `BatteryFloating/1.59` | `BatteryFloating/1.60` |

## 不修改

- `README.md:103` — `v1.59` 是版本历史中的旧条目，保留不变

## 验证

- 编译通过
- AboutScreen 显示 "v1.60"
- 构建 APK 文件名包含正确版本

## Git 提交

- 提交信息：`v1.60: 版本号统一`
- 仅本地提交，不推送