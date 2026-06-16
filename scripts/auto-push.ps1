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

# 自动检测 git 路径（优先 PATH，回退到原安装路径）
$GitPath = try { (Get-Command git -ErrorAction Stop).Source } catch { "D:\Git\cmd\git.exe" }

Write-Host "开始自动推送到远程仓库..." -ForegroundColor Cyan

# 获取项目根目录
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

# 1. 检查是否有未推送的 commit
$LocalCommits = & $GitPath log "$Remote/$Branch..HEAD" --oneline 2>$null
if (-not $LocalCommits) {
    Write-Host "没有需要推送的 commit" -ForegroundColor Yellow
    exit 0
}

$CommitCount = ($LocalCommits | Measure-Object).Count
Write-Host "发现 $CommitCount 个未推送的 commit:" -ForegroundColor Cyan
$LocalCommits | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }

# 2. 执行推送
Write-Host "正在推送到 $Remote/$Branch..." -ForegroundColor Cyan
try {
    & $GitPath push $Remote $Branch 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "推送成功!" -ForegroundColor Green
    } else {
        throw "git push 返回非零退出码: $LASTEXITCODE"
    }
} catch {
    Write-Host "推送失败: $_" -ForegroundColor Red
    Write-Host "可能的原因:" -ForegroundColor Yellow
    Write-Host "   1. 网络连接问题" -ForegroundColor Gray
    Write-Host "   2. 远程仓库有新的变更（需要先 pull）" -ForegroundColor Gray
    Write-Host "   3. 认证失败（检查凭证配置）" -ForegroundColor Gray
    exit 1
}

Write-Host "推送完成!" -ForegroundColor Green
