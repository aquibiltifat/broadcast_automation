$path = 'c:\Users\hp\Downloads\group-weaver-ai-main\group-weaver-ai-main\android-app\app\src\main\java\com\groupweaver\ai\service\WhatsAppAccessibilityService.kt'
$content = Get-Content $path
$count = $content.Count
$newContent = $content[0..1185] + '    }' + $content[1412..($count-1)]
Set-Content -Path $path -Value $newContent -Encoding UTF8
