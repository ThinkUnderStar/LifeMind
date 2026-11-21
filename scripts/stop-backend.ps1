<#
.SYNOPSIS
    停止 LifeMind 后端全部服务（按端口定位进程，只动 8001-8005）。
.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-backend.ps1
#>

$ErrorActionPreference = 'SilentlyContinue'

$ports = @(8001, 8002, 8003, 8004, 8005)

foreach ($port in $ports) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen
    if (-not $conn) {
        Write-Host "[未运行] 端口 $port" -ForegroundColor DarkGray
        continue
    }
    $pid_ = ($conn | Select-Object -First 1).OwningProcess
    $proc = Get-Process -Id $pid_
    Stop-Process -Id $pid_ -Force
    Write-Host "[已停止] 端口 $port -> $($proc.ProcessName) PID $pid_" -ForegroundColor Yellow
}

Write-Host ''
Write-Host 'Docker 里的依赖（Nacos / MySQL / Redis / Milvus / RocketMQ）未动。' -ForegroundColor Cyan
