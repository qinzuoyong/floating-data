<#
.SYNOPSIS
    自动创建 Gitee Release 并上传 APK

.DESCRIPTION
    该脚本会：
    1. 从 build.gradle.kts 提取版本号
    2. 检查版本是否已有对应的 tag 和 Release
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

# 自动检测 git 路径（优先 PATH，回退到原安装路径）
$GitPath = try { (Get-Command git -ErrorAction Stop).Source } catch { "D:\Git\cmd\git.exe" }

# Gitee API configuration
$Owner = "qinzuoyong"
$Repo = "floating-data"
$ApiBase = "https://gitee.com/api/v5"

# Get project root
$ProjectRoot = Split-Path -Parent $PSScriptRoot

Write-Host "开始创建 Release..." -ForegroundColor Cyan

# 1. Extract version from build.gradle.kts
$BuildGradlePath = Join-Path $ProjectRoot "app\build.gradle.kts"
if (-not (Test-Path $BuildGradlePath)) {
    Write-Error "未找到 build.gradle.kts: $BuildGradlePath"
    exit 1
}

$BuildContent = Get-Content $BuildGradlePath -Raw
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
$Headers = @{ "Authorization" = "Bearer $GiteeToken" }
try {
    $Releases = Invoke-RestMethod -Uri $CheckReleaseUrl -Method Get -Headers $Headers -ErrorAction Stop
    $ExistingRelease = $Releases | Where-Object { $_.tag_name -eq $TagName }
    if ($ExistingRelease) {
        Write-Host "Release $TagName 已存在 (ID: $($ExistingRelease.id))" -ForegroundColor Yellow
        Write-Host "Release 页面: https://gitee.com/$Owner/$Repo/releases/$($ExistingRelease.id)" -ForegroundColor Cyan
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
    access_token = $GiteeToken
    tag_name = $TagName
    name = "Release $TagName"
    target_commitish = $CommitSha
    body = "## 版本 $TagName`n`n### 下载`n- [APK 安装包](https://gitee.com/$Owner/$Repo/releases/download/$TagName/yongge.apk)`n`n### 相关链接`n- [项目主页](https://gitee.com/$Owner/$Repo)`n- [版本历史](https://gitee.com/$Owner/$Repo/blob/main/README.md#-版本历史)"
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
    
    $UploadResponse = Invoke-RestMethod -Uri $UploadUrl -Method Post -Body $BodyLines -ContentType "multipart/form-data; boundary=$Boundary" -ErrorAction Stop
    Write-Host "APK 上传成功" -ForegroundColor Green
} catch {
    Write-Host "APK 上传失败，但 Release 已创建: $_" -ForegroundColor Yellow
    Write-Host "请手动上传 APK 到 Release 页面" -ForegroundColor Gray
}

Write-Host "Release 创建完成!" -ForegroundColor Green
Write-Host "总结:" -ForegroundColor Cyan
Write-Host "   - 版本: $Version" -ForegroundColor Gray
Write-Host "   - Tag: $TagName" -ForegroundColor Gray
Write-Host "   - Release: https://gitee.com/$Owner/$Repo/releases/$ReleaseId" -ForegroundColor Gray
