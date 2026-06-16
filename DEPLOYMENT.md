# BatteryFloating 自动同步部署说明

## 已实现功能

### 1. Git Hooks 自动同步
- **文件**: `.git/hooks/post-commit`
- **功能**: 每次 commit 后自动执行以下操作：
  1. 同步 README 版本号
  2. 自动推送到远程仓库
  3. 检查版本变更并创建 Release

### 2. README 版本自动同步
- **文件**: `scripts/sync-readme.ps1`
- **功能**: 
  - 从 `app/build.gradle.kts` 提取 `versionName`
  - 自动更新 `README.md` 中的版本号标记
  - 自动 commit 变更

### 3. 自动推送
- **文件**: `scripts/auto-push.ps1`
- **功能**: 
  - 检查是否有未推送的 commit
  - 自动执行 `git push`
  - 处理推送失败的情况

### 4. 自动创建 Release
- **文件**: `scripts/create-release.ps1`
- **功能**: 
  - 从 build.gradle.kts 提取版本号
  - 检查版本是否已有对应的 tag 和 Release
  - 创建新的 tag 和 Release
  - 上传 APK 文件到 Release

### 5. 一键部署
- **文件**: `deploy.ps1`
- **功能**: 
  - 构建 → 同步 → 推送 → 创建 Release

## 使用方法

### 1. 首次配置

```powershell
# 设置 Gitee Token 环境变量
[Environment]::SetEnvironmentVariable("GITEE_TOKEN", "your_token_here", "User")

# 设置 PowerShell 执行策略
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

### 2. 日常使用

#### 自动模式（推荐）
每次 commit 时，post-commit 钩子会自动执行：
- 同步 README 版本号
- 推送到远程仓库
- 检查版本变更并创建 Release

#### 手动模式
```powershell
# 一键部署（构建 + 同步 + 推送）
.\deploy.ps1

# 一键部署并创建 Release
.\deploy.ps1 -CreateRelease $true

# 跳过构建步骤
.\deploy.ps1 -SkipBuild $true

# 单独执行某个脚本
.\scripts\sync-readme.ps1
.\scripts\auto-push.ps1
.\scripts\create-release.ps1 -GiteeToken "your_token"
```

### 3. 版本更新流程

1. 修改 `app/build.gradle.kts` 中的版本号：
   ```kotlin
   versionCode = 16
   versionName = "1.53"
   ```

2. 提交更改：
   ```powershell
   git add app/build.gradle.kts
   git commit -m "v1.53: 版本更新"
   ```

3. post-commit 钩子会自动：
   - 更新 README.md 中的版本号
   - 推送到远程仓库
   - 创建新的 Release

## 验证方法

### 1. 检查脚本是否正常工作
```powershell
# 测试 README 同步
.\scripts\sync-readme.ps1 -AutoCommit "false"

# 测试自动推送
.\scripts\auto-push.ps1

# 测试 Release 创建
$env:GITEE_TOKEN = "your_token"
.\scripts\create-release.ps1 -GiteeToken $env:GITEE_TOKEN
```

### 2. 检查 Git 钩子
```bash
# 确保 post-commit 钩子可执行
ls -la .git/hooks/post-commit
```

### 3. 检查 Gitee
- 访问 https://gitee.com/qinzuoyong/floating-data/releases 查看 Release
- 访问 https://gitee.com/qinzuoyong/floating-data/blob/main/README.md 查看版本号

## 常见问题

### Q1: post-commit 钩子不执行
**原因**: 钩子文件没有可执行权限或文件名错误
**解决**: 
```bash
chmod +x .git/hooks/post-commit
```

### Q2: PowerShell 脚本执行失败
**原因**: 执行策略限制
**解决**: 
```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

### Q3: Gitee API 认证失败
**原因**: Token 无效或权限不足
**解决**: 
1. 检查 Token 是否正确
2. 检查 Token 权限是否包含 `projects` 和 `contents`
3. 重新生成 Token

### Q4: 版本号提取失败
**原因**: build.gradle.kts 格式变化
**解决**: 检查正则表达式是否匹配当前格式

### Q5: 推送失败
**原因**: 网络连接问题或认证失败
**解决**: 
1. 检查网络连接
2. 检查凭证配置
3. 手动推送: `git push origin main`

## 文件结构

```
BatteryFloating/
├── .git/hooks/
│   └── post-commit              # Git post-commit 钩子
├── scripts/
│   ├── sync-readme.ps1          # README 版本同步脚本
│   ├── auto-push.ps1            # 自动推送脚本
│   └── create-release.ps1       # 自动创建 Release 脚本
├── deploy.ps1                   # 一键部署脚本
├── README.md                    # 项目说明文档
└── DEPLOYMENT.md                # 本文档
```

## 技术栈

- **Git Hooks**: 自动触发脚本执行
- **PowerShell**: Windows 原生脚本语言
- **Gitee API**: 创建 Release 和上传文件
- **Gradle**: Android 项目构建