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
    [string]$AutoCommit = "true"
)

$ErrorActionPreference = "Stop"

# 转换为布尔值
if ($AutoCommit -eq "true" -or $AutoCommit -eq "True" -or $AutoCommit -eq "1") {
    $AutoCommitBool = $true
} else {
    $AutoCommitBool = $false
}

# Git 路径
$GitPath = "D:\Git\cmd\git.exe"

# 获取项目根目录
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildGradlePath = Join-Path $ProjectRoot "app\build.gradle.kts"
$ReadmePath = Join-Path $ProjectRoot "README.md"

Write-Host "开始同步 README 版本号..." -ForegroundColor Cyan

# 1. 从 build.gradle.kts 提取版本号
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

# 2. 更新 README.md
if (-not (Test-Path $ReadmePath)) {
    Write-Error "未找到 README.md: $ReadmePath"
    exit 1
}

$ReadmeContent = Get-Content $ReadmePath -Raw
$OldVersionPattern = '(?<=版本:\s*\*\*)[^\*]+(?=\*\*)'

if ($ReadmeContent -match $OldVersionPattern) {
    $OldVersion = $Matches[0]
    if ($OldVersion -eq $Version) {
        Write-Host "版本号已是最新 ($Version)，无需更新" -ForegroundColor Yellow
        exit 0
    }
    
    $NewContent = $ReadmeContent -replace $OldVersionPattern, $Version
    Set-Content -Path $ReadmePath -Value $NewContent -NoNewline
    Write-Host "README.md 已更新: $OldVersion -> $Version" -ForegroundColor Green
} else {
    Write-Error "README.md 中未找到版本号标记 (格式: 版本: **X.XX**)"
    exit 1
}

# 3. 自动 commit（可选）
if ($AutoCommitBool) {
    Set-Location $ProjectRoot
    & $GitPath add README.md
    & $GitPath commit -m "docs: 自动同步版本号到 README ($Version)"
    Write-Host "已自动 commit README 变更" -ForegroundColor Green
}

Write-Host "README 版本同步完成!" -ForegroundColor Green
