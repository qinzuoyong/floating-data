<#
.SYNOPSIS
    一键部署脚本：构建 -> 同步 -> 推送 -> 创建 Release

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

# Git 路径
$GitPath = "D:\Git\cmd\git.exe"

Write-Host "开始一键部署..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Gray

# 1. 构建项目
if (-not $SkipBuild) {
    Write-Host "`n步骤 1: 构建 Release APK" -ForegroundColor Yellow
    Write-Host "----------------------------------------" -ForegroundColor Gray
    
    Write-Host "清理旧的构建产物..." -ForegroundColor Cyan
    Remove-Item -Path "_build" -Recurse -Force -ErrorAction SilentlyContinue
    
    Write-Host "执行 Release 构建..." -ForegroundColor Cyan
    & ".\gradlew.bat" assembleRelease --no-configuration-cache
    
    if ($LASTEXITCODE -ne 0) {
        Write-Error "构建失败"
        exit 1
    }
    
    Write-Host "构建完成" -ForegroundColor Green
} else {
    Write-Host "`n步骤 1: 跳过构建" -ForegroundColor Yellow
}

# 2. 同步 README
Write-Host "`n步骤 2: 同步 README 版本号" -ForegroundColor Yellow
Write-Host "----------------------------------------" -ForegroundColor Gray
& ".\scripts\sync-readme.ps1" -AutoCommit $true

# 3. 推送
Write-Host "`n步骤 3: 推送到远程仓库" -ForegroundColor Yellow
Write-Host "----------------------------------------" -ForegroundColor Gray
& ".\scripts\auto-push.ps1"

# 4. 创建 Release（可选）
if ($CreateRelease) {
    if (-not $GiteeToken) {
        Write-Host "`n步骤 4: 跳过 Release 创建（未提供 Gitee Token）" -ForegroundColor Yellow
        Write-Host "设置环境变量: `$env:GITEE_TOKEN = 'your_token'" -ForegroundColor Gray
    } else {
        Write-Host "`n步骤 4: 创建 Release" -ForegroundColor Yellow
        Write-Host "----------------------------------------" -ForegroundColor Gray
        & ".\scripts\create-release.ps1" -GiteeToken $GiteeToken
    }
} else {
    Write-Host "`n步骤 4: 跳过 Release 创建" -ForegroundColor Yellow
}

Write-Host "`n========================================" -ForegroundColor Gray
Write-Host "部署完成!" -ForegroundColor Green
