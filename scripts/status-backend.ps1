<#
.SYNOPSIS
    查看 LifeMind 后端各服务的端口与进程状态。
.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status-backend.ps1
#>

$ErrorActionPreference = 'SilentlyContinue'

$ports = @(
    @{ Name = 'gateway';  Port = 8001 }
    @{ Name = 'account';  Port = 8002 }
    @{ Name = 'message';  Port = 8003 }
    @{ Name = 'agent';    Port = 8004 }
    @{ Name = 'schedule'; Port = 8005 }
)

$rows = foreach ($p in $ports) {
    $conn = Get-NetTCPConnection -LocalPort $p.Port -State Listen
    if ($conn) {
        $proc = Get-Process -Id $conn[0].OwningProcess
        [pscustomobject]@{
            Service = $p.Name
            Port    = $p.Port
            State   = 'LISTENING'
            PID     = $conn[0].OwningProcess
            Memory  = '{0} MB' -f [math]::Round($proc.WorkingSet64 / 1MB)
        }
    } else {
        [pscustomobject]@{
            Service = $p.Name
            Port    = $p.Port
            State   = 'DOWN'
            PID     = '-'
            Memory  = '-'
        }
    }
}

$rows | Format-Table -AutoSize

Write-Host '--- 依赖（Docker）---' -ForegroundColor Cyan
docker ps --format '{{.Names}} | {{.Status}}' 2>$null
