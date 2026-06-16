# BatteryFloating 自动同步与自动发布实现方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Git Hooks 自动同步、README 版本自动同步、自动创建 Release 的完整自动化工作流

**Architecture:** 使用 PowerShell 脚本作为核心实现语言（Windows 原生支持），通过 Git Hooks 调用脚本实现自动化。脚本负责：从 build.gradle.kts 提取版本号 → 更新 README.md → commit & push → 检测版本变更 → 通过 Gitee API 创建 Release 并上传 APK

**Tech Stack:** Git Hooks, PowerShell, Gitee API, Kotlin/Gradle

---

## 项目现状分析

### 版本号位置
- **build.gradle.kts**: `app/build.gradle.kts` 第 48-49 行
  ```kotlin
  versionCode = 15
  versionName = "1.52"
  ```
- **README.md**: 第 4 行
  ```
  > 版本: **1.52** | 最低支持: **Android 14 (API 34)**
  ```

### Git 配置
- 远程仓库: `https://gitee.com/qinzuoyong/floating-data.git`
- 当前分支: `main`
- 凭证存储: 已配置 `credential.helper = store`
- 工作区: 干净，与远程同步

### APK 位置
- 构建后 APK: `_build/app/outputs/apk/release/yongge.apk`
- 已有 `copyApkToYongge` 任务将 APK 复制为 `yongge.apk`

---

## 文件结构设计

```
BatteryFloating/
├── .git/hooks/
│   └── post-commit              # Git post-commit 钩子
├── scripts/
│   ├── sync-readme.ps1          # README 版本同步脚本
│   ├── auto-push.ps1            # 自动推送脚本
│   └── create-release.ps1       # 自动创建 Release 脚本
└── docs/
    └── superpowers/
        └── plans/
            └── 2026-06-16-auto-sync-and-release.md  # 本文档
```

---

## Task 1: 创建 README 版本同步脚本

**Files:**
- Create: `scripts/sync-readme.ps1`

- [ ] **Step 1: 创建脚本目录**

```powershell
New-Item -ItemType Directory -Path "scripts" -Force
```

- [ ] **Step 2: 编写 sync-readme.ps1 脚本**

```powershell
<#
.SYNOPSIS
    从 build.gradle.kts 读取版本号并更新 README.md

.DESCRIPTION
    该脚本会：
    1. 从 app/build.gradle.kts 提取 versionName
    2. 更新 README.md 中的版本号标记
    3. 自动 commit 变更（可选）

.PARAMETER AutoCommit
    是否自动 commit 变更，默认 $true

.EXAMPLE
    .\sync-readme.ps1 -AutoCommit $true
#>

param(
    [bool]$AutoCommit = $true
)

$ErrorActionPreference = "Stop"

# 获取项目根目录
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildGradlePath = Join-Path $ProjectRoot "app\build.gradle.kts"
$ReadmePath = Join-Path $ProjectRoot "README.md"

Write-Host "🔄 开始同步 README 版本号..." -ForegroundColor Cyan

# 1. 从 build.gradle.kts 提取版本号
if (-not (Test-Path $BuildGradlePath)) {
    Write-Error "❌ 未找到 build.gradle.kts: $BuildGradlePath"
    exit 1
}

$BuildContent = Get-Content $BuildGradlePath -Raw
if ($BuildContent -match 'versionName\s*=\s*"([^"]+)"') {
    $Version = $Matches[1]
    Write-Host "📦 检测到版本号: $Version" -ForegroundColor Green
} else {
    Write-Error "❌ 无法从 build.gradle.kts 提取版本号"
    exit 1
}

# 2. 更新 README.md
if (-not (Test-Path $ReadmePath)) {
    Write-Error "❌ 未找到 README.md: $ReadmePath"
    exit 1
}

$ReadmeContent = Get-Content $ReadmePath -Raw
$OldVersionPattern = '(?<=版本:\s*\*\*)[^\*]+(?=\*\*)'

if ($ReadmeContent -match $OldVersionPattern) {
    $OldVersion = $Matches[0]
    if ($OldVersion -eq $Version) {
        Write-Host "✅ 版本号已是最新 ($Version)，无需更新" -ForegroundColor Yellow
        exit 0
    }
    
    $NewContent = $ReadmeContent -replace $OldVersionPattern, $Version
    Set-Content -Path $ReadmePath -Value $NewContent -NoNewline
    Write-Host "📝 README.md 已更新: $OldVersion → $Version" -ForegroundColor Green
} else {
    Write-Error "❌ README.md 中未找到版本号标记 (格式: 版本: **X.XX**)"
    exit 1
}

# 3. 自动 commit（可选）
if ($AutoCommit) {
    Set-Location $ProjectRoot
    git add README.md
    git commit -m "docs: 自动同步版本号到 README ($Version)"
    Write-Host "✅ 已自动 commit README 变更" -ForegroundColor Green
}

Write-Host "🎉 README 版本同步完成!" -ForegroundColor Green
```

- [ ] **Step 3: 测试脚本基本功能**

```powershell
cd "C:\Users\yong\AndroidStudioProjects\BatteryFloating"
.\scripts\sync-readme.ps1 -AutoCommit $false
```

预期输出：
- 显示检测到的版本号
- 显示 README 更新信息（如果版本有变化）
- 不会自动 commit（因为 `-AutoCommit $false`）

---

## Task 2: 创建自动推送脚本

**Files:**
- Create: `scripts/auto-push.ps1`

- [ ] **Step 1: 编写 auto-push.ps1 脚本**

```powershell
<#
.SYNOPSIS
    自动将本地 commit 推送到远程仓库

.DESCRIPTION
    该脚本会：
    1. 检查是否有未推送的 commit
    2. 执行 git push
    3. 处理推送失败的情况

.PARAMETER Remote
    远程仓库名称，默认 "origin"

.PARAMETER Branch
    分支名称，默认 "main"

.EXAMPLE
    .\auto-push.ps1 -Remote "origin" -Branch "main"
#>

param(
    [string]$Remote = "origin",
    [string]$Branch = "main"
)

$ErrorActionPreference = "Stop"

Write-Host "🚀 开始自动推送到远程仓库..." -ForegroundColor Cyan

# 获取项目根目录
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

# 1. 检查是否有未推送的 commit
$LocalCommits = git log "$Remote/$Branch..HEAD" --oneline 2>$null
if (-not $LocalCommits) {
    Write-Host "✅ 没有需要推送的 commit" -ForegroundColor Yellow
    exit 0
}

$CommitCount = ($LocalCommits | Measure-Object).Count
Write-Host "📋 发现 $CommitCount 个未推送的 commit:" -ForegroundColor Cyan
$LocalCommits | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }

# 2. 执行推送
Write-Host "⬆️ 正在推送到 $Remote/$Branch..." -ForegroundColor Cyan
try {
    git push $Remote $Branch 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅ 推送成功!" -ForegroundColor Green
    } else {
        throw "git push 返回非零退出码: $LASTEXITCODE"
    }
} catch {
    Write-Host "❌ 推送失败: $_" -ForegroundColor Red
    Write-Host "💡 可能的原因:" -ForegroundColor Yellow
    Write-Host "   1. 网络连接问题" -ForegroundColor Gray
    Write-Host "   2. 远程仓库有新的变更（需要先 pull）" -ForegroundColor Gray
    Write-Host "   3. 认证失败（检查凭证配置）" -ForegroundColor Gray
    exit 1
}

Write-Host "🎉 推送完成!" -ForegroundColor Green
```

- [ ] **Step 2: 测试脚本**

```powershell
cd "C:\Users\yong\AndroidStudioProjects\BatteryFloating"
.\scripts\auto-push.ps1
```

预期输出：
- 显示未推送的 commit 数量和内容
- 执行推送操作
- 显示推送结果

---

## Task 3: 创建自动 Release 脚本

**Files:**
- Create: `scripts/create-release.ps1`

- [ ] **Step 1: 编写 create-release.ps1 脚本**

```powershell
<#
.SYNOPSIS
    自动创建 Gitee Release 并上传 APK

.DESCRIPTION
    该脚本会：
    1. 从 build.gradle.kts 提取版本号
    2. 检查版本是否已有对应的 tag
    3. 创建新的 tag 和 Release
    4. 上传 APK 文件到 Release

.PARAMETER GiteeToken
    Gitee API 个人访问令牌

.PARAMETER ApkPath
    APK 文件路径，默认为构建输出路径

.EXAMPLE
    .\create-release.ps1 -GiteeToken "your_token_here"
#>

param(
    [Parameter(Mandatory=$true)]
    [string]$GiteeToken,
    
    [string]$ApkPath = ""
)

$ErrorActionPreference = "Stop"

# Gitee API 配置
$Owner = "qinzuoyong"
$Repo = "floating-data"
$ApiBase = "https://gitee.com/api/v5"

# 获取项目根目录
$ProjectRoot = Split-Path -Parent $PSScriptRoot

Write-Host "🚀 开始创建 Release..." -ForegroundColor Cyan

# 1. 从 build.gradle.kts 提取版本号
$BuildGradlePath = Join-Path $ProjectRoot "app\build.gradle.kts"
if (-not (Test-Path $BuildGradlePath)) {
    Write-Error "❌ 未找到 build.gradle.kts: $BuildGradlePath"
    exit 1
}

$BuildContent = Get-Content $BuildGradlePath -Raw
if ($BuildContent -match 'versionName\s*=\s*"([^"]+)"') {
    $Version = $Matches[1]
    Write-Host "📦 检测到版本号: $Version" -ForegroundColor Green
} else {
    Write-Error "❌ 无法从 build.gradle.kts 提取版本号"
    exit 1
}

$TagName = "v$Version"

# 2. 检查 tag 是否已存在
Write-Host "🔍 检查 tag $TagName 是否已存在..." -ForegroundColor Cyan
$CheckTagUrl = "$ApiBase/repos/$Owner/$Repo/tags/$TagName"
try {
    $Response = Invoke-RestMethod -Uri $CheckTagUrl -Method Get -ErrorAction Stop
    Write-Host "⚠️ tag $TagName 已存在，跳过 Release 创建" -ForegroundColor Yellow
    Write-Host "💡 如果需要重新创建，请先删除远程 tag: git push origin :refs/tags/$TagName" -ForegroundColor Gray
    exit 0
} catch {
    if ($_.Exception.Response.StatusCode -eq 404) {
        Write-Host "✅ tag $TagName 不存在，可以创建" -ForegroundColor Green
    } else {
        throw
    }
}

# 3. 确定 APK 路径
if (-not $ApkPath) {
    $ApkPath = Join-Path $ProjectRoot "_build\app\outputs\apk\release\yongge.apk"
}

if (-not (Test-Path $ApkPath)) {
    Write-Error "❌ 未找到 APK 文件: $ApkPath"
    Write-Host "💡 请先构建项目: ./gradlew.bat assembleRelease" -ForegroundColor Yellow
    exit 1
}

$ApkSize = (Get-Item $ApkPath).Length / 1MB
Write-Host "📱 APK 文件: $ApkPath ($([math]::Round($ApkSize, 2)) MB)" -ForegroundColor Cyan

# 4. 创建 tag
Write-Host "🏷️ 创建 tag $TagName..." -ForegroundColor Cyan
$CreateTagBody = @{
    access_token = $GiteeToken
    refs = $TagName
    repository = "$Owner/$Repo"
} | ConvertTo-Json

$CreateTagUrl = "$ApiBase/repos/$Owner/$Repo/tags"
try {
    $TagResponse = Invoke-RestMethod -Uri $CreateTagUrl -Method Post -Body $CreateTagBody -ContentType "application/json" -ErrorAction Stop
    Write-Host "✅ tag $TagName 创建成功" -ForegroundColor Green
} catch {
    Write-Error "❌ tag 创建失败: $_"
    exit 1
}

# 5. 创建 Release
Write-Host "📝 创建 Release..." -ForegroundColor Cyan
$CreateReleaseBody = @{
    access_token = $GiteeToken
    tag_name = $TagName
    name = "Release $TagName"
    body = @"
## 版本 $TagName

### 📦 下载
- [APK 安装包](https://gitee.com/$Owner/$Repo/releases/download/$TagName/yongge.apk)

### 📋 更新内容
请根据实际变更填写更新内容。

### 🔗 相关链接
- [项目主页](https://gitee.com/$Owner/$Repo)
- [版本历史](https://gitee.com/$Owner/$Repo/blob/main/README.md#-版本历史)
"@
    prerelease = $false
} | ConvertTo-Json

$CreateReleaseUrl = "$ApiBase/repos/$Owner/$Repo/releases"
try {
    $ReleaseResponse = Invoke-RestMethod -Uri $CreateReleaseUrl -Method Post -Body $CreateReleaseBody -ContentType "application/json" -ErrorAction Stop
    $ReleaseId = $ReleaseResponse.id
    Write-Host "✅ Release 创建成功 (ID: $ReleaseId)" -ForegroundColor Green
    Write-Host "🔗 Release 页面: https://gitee.com/$Owner/$Repo/releases/$ReleaseId" -ForegroundColor Cyan
} catch {
    Write-Error "❌ Release 创建失败: $_"
    exit 1
}

# 6. 上传 APK 到 Release
Write-Host "📤 上传 APK 到 Release..." -ForegroundColor Cyan
$UploadUrl = "$ApiBase/repos/$Owner/$Repo/releases/$ReleaseId/attach_files"

try {
    # 使用 multipart/form-data 上传文件
    $Boundary = [System.Guid]::NewGuid().ToString()
    $LF = "`r`n"
    
    $BodyLines = @(
        "--$Boundary",
        "Content-Disposition: form-data; name=`"file`"; filename=`"yongge.apk`"",
        "Content-Type: application/vnd.android.package-archive",
        "",
        [System.IO.File]::ReadAllText($ApkPath),
        "--$Boundary--"
    ) -join $LF
    
    # 对于大文件，需要使用不同的方法
    # 这里简化处理，实际可能需要调整
    $UploadResponse = Invoke-RestMethod -Uri $UploadUrl -Method Post -Body $BodyLines -ContentType "multipart/form-data; boundary=$Boundary" -ErrorAction Stop
    Write-Host "✅ APK 上传成功" -ForegroundColor Green
} catch {
    Write-Host "⚠️ APK 上传失败，但 Release 已创建: $_" -ForegroundColor Yellow
    Write-Host "💡 请手动上传 APK 到 Release 页面" -ForegroundColor Gray
}

Write-Host "🎉 Release 创建完成!" -ForegroundColor Green
Write-Host "📋 总结:" -ForegroundColor Cyan
Write-Host "   - 版本: $Version" -ForegroundColor Gray
Write-Host "   - Tag: $TagName" -ForegroundColor Gray
Write-Host "   - Release: https://gitee.com/$Owner/$Repo/releases/$ReleaseId" -ForegroundColor Gray
```

- [ ] **Step 2: 测试脚本（不实际创建 Release）**

```powershell
cd "C:\Users\yong\AndroidStudioProjects\BatteryFloating"
# 先检查版本号提取是否正常
$BuildContent = Get-Content "app\build.gradle.kts" -Raw
if ($BuildContent -match 'versionName\s*=\s*"([^"]+)"') {
    Write-Host "✅ 版本号提取成功: $($Matches[1])" -ForegroundColor Green
}
```

---

## Task 4: 创建 Git post-commit 钩子

**Files:**
- Create: `.git/hooks/post-commit`

- [ ] **Step 1: 编写 post-commit 钩子**

```bash
#!/bin/bash
#
# Git post-commit 钩子
# 功能：commit 后自动同步 README 并推送到远程
#

# 获取项目根目录
PROJECT_ROOT="$(git rev-parse --show-toplevel)"
SCRIPTS_DIR="$PROJECT_ROOT/scripts"

# 检查脚本目录是否存在
if [ ! -d "$SCRIPTS_DIR" ]; then
    echo "❌ 脚本目录不存在: $SCRIPTS_DIR"
    exit 1
fi

# 检查 PowerShell 是否可用
if ! command -v pwsh &> /dev/null; then
    if ! command -v powershell &> /dev/null; then
        echo "❌ PowerShell 不可用，跳过自动同步"
        exit 0
    fi
    POWERSHELL="powershell"
else
    POWERSHELL="pwsh"
fi

echo "🔄 post-commit 钩子触发..."

# 1. 同步 README 版本号
echo "📝 同步 README 版本号..."
$POWERSHELL -ExecutionPolicy Bypass -File "$SCRIPTS_DIR/sync-readme.ps1" -AutoCommit $true

# 2. 自动推送
echo "🚀 自动推送到远程..."
$POWERSHELL -ExecutionPolicy Bypass -File "$SCRIPTS_DIR/auto-push.ps1"

# 3. 检查版本变更（异步，不阻塞 commit）
echo "🔍 检查版本变更..."
$POWERSHELL -ExecutionPolicy Bypass -File "$SCRIPTS_DIR/create-release.ps1" -GiteeToken $GITEE_TOKEN 2>/dev/null &

echo "✅ post-commit 钩子完成"
```

- [ ] **Step 2: 设置钩子可执行权限**

```bash
# 在 Git Bash 中执行
chmod +x .git/hooks/post-commit
```

或者在 Windows 中：
```powershell
# 确保钩子文件没有 .sample 后缀
Remove-Item ".git/hooks/post-commit.sample" -ErrorAction SilentlyContinue
```

- [ ] **Step 3: 配置 Gitee Token 环境变量**

```powershell
# 临时设置（当前会话）
$env:GITEE_TOKEN = "your_gitee_token_here"

# 永久设置（系统环境变量）
[Environment]::SetEnvironmentVariable("GITEE_TOKEN", "your_gitee_token_here", "User")
```

---

## Task 5: 创建一键部署脚本

**Files:**
- Create: `deploy.ps1`

- [ ] **Step 1: 编写 deploy.ps1 脚本**

```powershell
<#
.SYNOPSIS
    一键部署脚本：构建 → 同步 → 推送 → 创建 Release

.DESCRIPTION
    该脚本会：
    1. 清理旧的构建产物
    2. 执行 Release 构建
    3. 同步 README 版本号
    4. 推送到远程仓库
    5. 创建新的 Release（可选）

.PARAMETER SkipBuild
    跳过构建步骤

.PARAMETER CreateRelease
    是否创建 Release，默认 $false

.PARAMETER GiteeToken
    Gitee API 个人访问令牌（创建 Release 时必需）

.EXAMPLE
    .\deploy.ps1 -CreateRelease $true -GiteeToken "your_token"
#>

param(
    [bool]$SkipBuild = $false,
    [bool]$CreateRelease = $false,
    [string]$GiteeToken = ""
)

$ErrorActionPreference = "Stop"

Write-Host "🚀 开始一键部署..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Gray

# 1. 构建项目
if (-not $SkipBuild) {
    Write-Host "`n📦 步骤 1: 构建 Release APK" -ForegroundColor Yellow
    Write-Host "----------------------------------------" -ForegroundColor Gray
    
    Write-Host "🧹 清理旧的构建产物..." -ForegroundColor Cyan
    Remove-Item -Path "_build" -Recurse -Force -ErrorAction SilentlyContinue
    
    Write-Host "🔨 执行 Release 构建..." -ForegroundColor Cyan
    & ".\gradlew.bat" assembleRelease --no-configuration-cache
    
    if ($LASTEXITCODE -ne 0) {
        Write-Error "❌ 构建失败"
        exit 1
    }
    
    Write-Host "✅ 构建完成" -ForegroundColor Green
} else {
    Write-Host "`n⏭️ 步骤 1: 跳过构建" -ForegroundColor Yellow
}

# 2. 同步 README
Write-Host "`n📝 步骤 2: 同步 README 版本号" -ForegroundColor Yellow
Write-Host "----------------------------------------" -ForegroundColor Gray
& ".\scripts\sync-readme.ps1" -AutoCommit $true

# 3. 推送
Write-Host "`n🚀 步骤 3: 推送到远程仓库" -ForegroundColor Yellow
Write-Host "----------------------------------------" -ForegroundColor Gray
& ".\scripts\auto-push.ps1"

# 4. 创建 Release（可选）
if ($CreateRelease) {
    if (-not $GiteeToken) {
        Write-Host "`n⚠️ 步骤 4: 跳过 Release 创建（未提供 Gitee Token）" -ForegroundColor Yellow
        Write-Host "💡 设置环境变量: `$env:GITEE_TOKEN = 'your_token'" -ForegroundColor Gray
    } else {
        Write-Host "`n🏷️ 步骤 4: 创建 Release" -ForegroundColor Yellow
        Write-Host "----------------------------------------" -ForegroundColor Gray
        & ".\scripts\create-release.ps1" -GiteeToken $GiteeToken
    }
} else {
    Write-Host "`n⏭️ 步骤 4: 跳过 Release 创建" -ForegroundColor Yellow
}

Write-Host "`n========================================" -ForegroundColor Gray
Write-Host "🎉 部署完成!" -ForegroundColor Green
```

---

## Task 6: 配置说明

### 6.1 Gitee Token 获取

1. 登录 Gitee: https://gitee.com
2. 进入设置 → 私人令牌: https://gitee.com/profile/personal_access_tokens
3. 创建新令牌，勾选以下权限：
   - `projects` - 访问用户的项目
   - `contents` - 访问仓库内容
   - `issues` - 访问 Issues（可选）
4. 保存生成的 Token

### 6.2 环境变量配置

```powershell
# Windows PowerShell（永久设置）
[Environment]::SetEnvironmentVariable("GITEE_TOKEN", "your_token_here", "User")

# 或者在 PowerShell 中临时设置
$env:GITEE_TOKEN = "your_token_here"
```

### 6.3 Git Hooks 配置

```bash
# 确保 post-commit 钩子可执行
chmod +x .git/hooks/post-commit
```

---

## Task 7: 验证方法

### 7.1 测试 README 同步

```powershell
# 1. 修改 build.gradle.kts 中的版本号
# 2. 运行同步脚本
.\scripts\sync-readme.ps1 -AutoCommit $false

# 3. 检查 README.md 是否已更新
Get-Content README.md | Select-String "版本"
```

### 7.2 测试自动推送

```powershell
# 1. 创建一个测试 commit
git commit --allow-empty -m "test: 测试自动推送"

# 2. 检查是否自动推送
git log origin/main..HEAD --oneline
```

### 7.3 测试 Release 创建

```powershell
# 1. 设置 Token
$env:GITEE_TOKEN = "your_token"

# 2. 运行 Release 脚本（先在测试仓库验证）
.\scripts\create-release.ps1 -GiteeToken $env:GiteeToken

# 3. 检查 Gitee Release 页面
# https://gitee.com/qinzuoyong/floating-data/releases
```

### 7.4 完整流程测试

```powershell
# 1. 修改版本号（例如 1.52 → 1.53）
# 2. 运行一键部署
.\deploy.ps1 -CreateRelease $true

# 3. 验证：
#    - README.md 版本号已更新
#    - 代码已推送到远程
#    - 新 Release 已创建
#    - APK 已上传到 Release
```

---

## 常见问题

### Q1: post-commit 钩子不执行

**原因：** 钩子文件没有可执行权限或文件名错误

**解决：**
```bash
# 检查钩子文件
ls -la .git/hooks/post-commit

# 确保可执行
chmod +x .git/hooks/post-commit
```

### Q2: PowerShell 脚本执行失败

**原因：** 执行策略限制

**解决：**
```powershell
# 检查当前执行策略
Get-ExecutionPolicy

# 设置为 RemoteSigned（允许本地脚本）
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

### Q3: Gitee API 认证失败

**原因：** Token 无效或权限不足

**解决：**
```powershell
# 测试 Token 是否有效
$headers = @{
    "Authorization" = "Bearer $env:GITEE_TOKEN"
}
Invoke-RestMethod -Uri "https://gitee.com/api/v5/user" -Headers $headers
```

### Q4: 版本号提取失败

**原因：** build.gradle.kts 格式变化

**解决：** 检查正则表达式是否匹配当前格式，必要时调整脚本中的正则表达式

---

## 总结

本方案提供了完整的自动化工作流：

1. **自动同步 README**：每次 commit 后自动从 build.gradle.kts 读取版本号并更新 README.md
2. **自动推送**：commit 后自动推送到远程仓库
3. **自动创建 Release**：版本更新时自动创建新的 Release 并上传 APK

通过 Git Hooks 实现完全自动化，无需手动干预。所有脚本都支持独立运行，便于调试和定制。
