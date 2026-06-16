# === Gitee v1.42 发行版创建脚本 ===
# 使用方法: 将下方 $token 替换为你的 Gitee Personal Access Token，然后在 PowerShell 中执行
# Token 获取: https://gitee.com/profile/personal_access_tokens （勾选 projects 权限）

$token = "你的Gitee_Token_粘贴到这里"
$repo = "qinzuoyong/floating-data"

$releaseBody = @"
## 🔋 v1.42 更新内容

### ✨ 新功能
- 🔒 **双击锁定悬浮窗** — 双击悬浮窗可锁定/解锁位置，锁定后禁止拖拽，🔒图标直观提示
- 📱 **锁定悬浮窗开关** — 设置页新增锁定悬浮窗开关，可手动控制

### 🎨 界面优化
- UI 全面美化：统一圆角 16dp、emoji 装饰图标、颜色名称标签
- 按钮样式优化（FilledTonalButton），界面更协调
- 颜色选择器下方显示颜色名称

### 🐛 Bug 修复
- 修复硬编码版本号问题
- 清理冗余代码

### 📊 技术信息
- **最低支持**: Android 14 (API 34)
- **编译 SDK**: 35
- **APK 大小**: 5.33 MB
"@

$body = @{
    access_token = $token
    tag_name     = "v1.42"
    name         = "v1.42"
    body         = $releaseBody
} | ConvertTo-Json -Depth 3

try {
    $resp = Invoke-WebRequest -Uri "https://gitee.com/api/v5/repos/$repo/releases" -Method POST -Body $body -ContentType "application/json; charset=utf-8" -UseBasicParsing -TimeoutSec 15
    Write-Output "✅ Release 创建成功！"
    $releaseData = $resp.Content | ConvertFrom-Json
    Write-Output "Release ID: $($releaseData.id)"
    Write-Output "Release URL: $($releaseData.html_url)"
    
    # 上传 APK 附件
    $apkPath = "C:\Users\yong\AndroidStudioProjects\BatteryFloating\apk\yongge.apk"
    if (Test-Path $apkPath) {
        $releaseId = $releaseData.id
        $boundary = [System.Guid]::NewGuid().ToString()
        $fileBytes = [System.IO.File]::ReadAllBytes($apkPath)
        
        $bodyLines = @(
            "--$boundary",
            "Content-Disposition: form-data; name=`"access_token`"`r`n",
            $token,
            "--$boundary",
            "Content-Disposition: form-data; name=`"file`"; filename=`"yongge.apk`"",
            "Content-Type: application/octet-stream`r`n"
        )
        $bodyEnd = "`r`n--$boundary--`r`n"
        
        $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes(($bodyLines -join "`r`n"))
        $endBytes = [System.Text.Encoding]::UTF8.GetBytes($bodyEnd)
        
        $ms = New-Object System.IO.MemoryStream
        $ms.Write($bodyBytes, 0, $bodyBytes.Length)
        $ms.Write($fileBytes, 0, $fileBytes.Length)
        $ms.Write($endBytes, 0, $endBytes.Length)
        
        $uploadResp = Invoke-WebRequest -Uri "https://gitee.com/api/v5/repos/$repo/releases/$releaseId/attach_files" -Method POST -Body $ms.ToArray() -ContentType "multipart/form-data; boundary=$boundary" -UseBasicParsing -TimeoutSec 30
        Write-Output "✅ APK 上传成功！"
    }
} catch {
    Write-Output "❌ 失败: $($_.Exception.Message)"
}
