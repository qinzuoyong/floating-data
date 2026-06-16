<#
.SYNOPSIS
    自动创建 Gitee Release 并上传 APK

.DESCRIPTION
    该脚本会：
    1. 从 build.gradle.kts 提取版本号
    2. 检查版本是否已有对应的 tag 和 Release
    3. 创建新的 tag 和 Release
    4. 上传 APK 文件到 Release

    Token 读取优先级：
    1. 参数 GiteeToken
    2. 环境变量 $env:GITEE_TOKEN
    3. 配置文件 ~/.reasonix/gitee-config.json

.PARAMETER GiteeToken
    Gitee API 个人访问令牌（可选，也可从配置文件读取）

.PARAMETER ApkPath
    APK 文件路径，默认为构建输出路径
#>

param(
    [string]$GiteeToken = "",
    [string]$ApkPath = ""
)

$ErrorActionPreference = "Stop"

# 自动检测 git 路径
$GitPath = try { (Get-Command git -ErrorAction Stop).Source } catch { "D:\Git\cmd\git.exe" }

# 系统默认编码
$DefaultEncoding = [System.Text.Encoding]::Default

# Gitee API configuration
$Owner = "qinzuoyong"
$Repo = "floating-data"
$ApiBase = "https://gitee.com/api/v5"

# Get project root
$ProjectRoot = Split-Path -Parent $PSScriptRoot

Write-Host "开始创建 Release..." -ForegroundColor Cyan

# ===== Token 获取逻辑 =====
function Get-GiteeToken {
    param([string]$ParamToken)
    
    # 1. 参数直接传入
    if ($ParamToken) { return $ParamToken }
    
    # 2. 环境变量
    $envToken = [System.Environment]::GetEnvironmentVariable("GITEE_TOKEN", "User")
    if ($envToken) { return $envToken }
    
    # 3. 配置文件
    $configPath = Join-Path ([System.Environment]::GetFolderPath("UserProfile")) ".reasonix\gitee-config.json"
    if (Test-Path $configPath) {
        try {
            $config = Get-Content $configPath -Raw | ConvertFrom-Json
            if ($config.token) { return $config.token }
        } catch {
            Write-Host "读取配置文件失败: $_" -ForegroundColor Yellow
        }
    }
    
    return $null
}

$Token = Get-GiteeToken -ParamToken $GiteeToken
if (-not $Token) {
    Write-Host "Gitee Token 未配置！" -ForegroundColor Red
    Write-Host "请通过以下任一方式配置：" -ForegroundColor Yellow
    Write-Host "  1. 创建配置文件 ~/.reasonix/gitee-config.json:" -ForegroundColor Gray
    Write-Host '     { "token": "your_token_here" }' -ForegroundColor Gray
    Write-Host "  2. 设置环境变量: [Environment]::SetEnvironmentVariable('GITEE_TOKEN', 'xxx', 'User')" -ForegroundColor Gray
    Write-Host "  3. 获取 Token: https://gitee.com/profile/personal_access_tokens" -ForegroundColor Gray
    Write-Host "     勾选权限: projects + contents" -ForegroundColor Gray
    exit 1
}

# 1. Extract version from build.gradle.kts
$BuildGradlePath = Join-Path $ProjectRoot "app\build.gradle.kts"
if (-not (Test-Path $BuildGradlePath)) {
    Write-Error "未找到 build.gradle.kts: $BuildGradlePath"
    exit 1
}

$BuildContent = [System.IO.File]::ReadAllText($BuildGradlePath, $DefaultEncoding)
if ($BuildContent -match 'versionName\s*=\s*"([^"]+)"') {
    $Version = $Matches[1]
    Write-Host "检测到版本号: $Version" -ForegroundColor Green
} else {
    Write-Error "无法从 build.gradle.kts 提取版本号"
    exit 1
}

$TagName = "v$Version"

# 2. Check if Release already exists
Write-Host "检查 Release $TagName 是否已存在..." -ForegroundColor Cyan
$CheckReleaseUrl = "$ApiBase/repos/$Owner/$Repo/releases"
try {
    $Releases = Invoke-RestMethod -Uri $CheckReleaseUrl -Method Get -Headers @{ "Authorization" = "Bearer $Token" } -ErrorAction Stop
    $ExistingRelease = $Releases | Where-Object { $_.tag_name -eq $TagName }
    if ($ExistingRelease) {
        Write-Host "Release $TagName 已存在 (ID: $($ExistingRelease.id))" -ForegroundColor Yellow
        Write-Host "Release 页面: https://gitee.com/$Owner/$Repo/releases/$($ExistingRelease.id)" -ForegroundColor Cyan
        
        # 检查是否需要上传 APK
        if ($ExistingRelease.assets -and $ExistingRelease.assets.Count -gt 0) {
            Write-Host "Release 已包含附件，跳过上传" -ForegroundColor Green
        } else {
            Write-Host "Release 无附件，尝试上传 APK..." -ForegroundColor Cyan
        }
        exit 0
    }
} catch {
    Write-Host "检查 Release 失败: $_" -ForegroundColor Yellow
}

# 3. Determine APK path
if (-not $ApkPath) {
    $ApkPath = Join-Path $ProjectRoot "_build\app\outputs\apk\release\yongge.apk"
}

if (-not (Test-Path $ApkPath)) {
    Write-Error "未找到 APK 文件: $ApkPath"
    Write-Host "请先构建项目: ./gradlew.bat assembleRelease" -ForegroundColor Yellow
    exit 1
}

$ApkSize = (Get-Item $ApkPath).Length / 1MB
Write-Host "APK 文件: $ApkPath ($([math]::Round($ApkSize, 2)) MB)" -ForegroundColor Cyan

# 4. Get current commit SHA for target_commitish
Write-Host "获取当前 commit SHA..." -ForegroundColor Cyan
Set-Location $ProjectRoot
$CommitSha = & $GitPath rev-parse HEAD
Write-Host "当前 commit: $CommitSha" -ForegroundColor Green

# 5. Create Release (with target_commitish to create tag)
Write-Host "创建 Release $TagName..." -ForegroundColor Cyan
$CreateReleaseBody = @{
    access_token = $Token
    tag_name = $TagName
    name = "Release $TagName"
    target_commitish = $CommitSha
    body = "## 版本 $TagName`n`n### 下载`n- [APK 安装包](https://gitee.com/$Owner/$Repo/releases/download/$TagName/yongge.apk)`n`n### 相关链接`n- [项目主页](https://gitee.com/$Owner/$Repo)`n- [版本历史](https://gitee.com/$Owner/$Repo/blob/main/README.md)"
    prerelease = "false"
}

$CreateReleaseUrl = "$ApiBase/repos/$Owner/$Repo/releases"
try {
    $ReleaseResponse = Invoke-RestMethod -Uri $CreateReleaseUrl -Method Post -Body $CreateReleaseBody -ContentType "application/x-www-form-urlencoded" -ErrorAction Stop
    $ReleaseId = $ReleaseResponse.id
    Write-Host "Release 创建成功 (ID: $ReleaseId)" -ForegroundColor Green
    Write-Host "Release 页面: https://gitee.com/$Owner/$Repo/releases/$ReleaseId" -ForegroundColor Cyan
} catch {
    Write-Error "Release 创建失败: $_"
    exit 1
}

# 6. Upload APK to Release
Write-Host "上传 APK 到 Release..." -ForegroundColor Cyan
$UploadUrl = "$ApiBase/repos/$Owner/$Repo/releases/$ReleaseId/attach_files"

try {
    $Boundary = [System.Guid]::NewGuid().ToString()
    $LF = "`r`n"
    
    # 使用 .NET 二进制读取（避免 PowerShell 大文件 base64 内存问题）
    $FileBytes = [System.IO.File]::ReadAllBytes($ApkPath)
    $FileContent = [System.Convert]::ToBase64String($FileBytes)
    
    $BodyLines = @(
        "--$Boundary",
        "Content-Disposition: form-data; name=`"file`"; filename=`"yongge.apk`"",
        "Content-Type: application/vnd.android.package-archive",
        "",
        $FileContent,
        "--$Boundary--"
    ) -join $LF
    
    $Headers = @{ "Authorization" = "Bearer $Token" }
    $UploadResponse = Invoke-RestMethod -Uri $UploadUrl -Method Post -Body $BodyLines -Headers $Headers -ContentType "multipart/form-data; boundary=$Boundary" -ErrorAction Stop
    Write-Host "APK 上传成功!" -ForegroundColor Green
} catch {
    $ErrorMsg = $_.ToString()
    if ($ErrorMsg -match "登录失效|40001") {
        Write-Host "APK 上传失败: Token 已过期" -ForegroundColor Red
        Write-Host "请更新 ~/.reasonix/gitee-config.json 中的 token" -ForegroundColor Yellow
        Write-Host "获取新 Token: https://gitee.com/profile/personal_access_tokens" -ForegroundColor Gray
    } else {
        Write-Host "APK 上传失败，但 Release 已创建: $_" -ForegroundColor Yellow
        Write-Host "请手动上传 APK: https://gitee.com/$Owner/$Repo/releases/$ReleaseId" -ForegroundColor Gray
    }
}

Write-Host "Release 创建完成!" -ForegroundColor Green
Write-Host "总结:" -ForegroundColor Cyan
Write-Host "   - 版本: $Version" -ForegroundColor Gray
Write-Host "   - Tag: $TagName" -ForegroundColor Gray
Write-Host "   - Release: https://gitee.com/$Owner/$Repo/releases/$ReleaseId" -ForegroundColor Gray
