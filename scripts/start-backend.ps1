<#
.SYNOPSIS
    启动 LifeMind 后端全部服务：4 个 Java 微服务 + 1 个 Python agent。

.DESCRIPTION
    只负责把进程拉起来并落到 .runlogs/ 下，不做健康检查的阻塞等待。
    依赖已经就绪的东西（Docker 里的 Nacos / MySQL / Redis / Milvus / RocketMQ）。

    为什么用脚本而不是一条条敲命令：
    1. PowerShell 5.1 + Start-Process 有个坑 —— 当进程环境里同时存在
       `http_proxy` 和 `HTTP_PROXY`（大小写两份）时，创建子进程会抛
         Start-Process: 已添加项。字典中的关键字:"https_proxy"...
       因为 Windows 环境变量大小写不敏感、而 PS 按字典塞。这里先把小写那份清掉。
    2. agent 不能直接 `uvicorn main:app`，必须走 run.py（SelectorEventLoop），
       否则 psycopg 在 ProactorEventLoop 上直接拒绝连接。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-backend.ps1
#>

$ErrorActionPreference = 'Stop'

# —— 坑 1：清掉重复的小写代理变量，否则 Start-Process 直接抛异常 ——
foreach ($n in 'http_proxy', 'https_proxy') {
    [Environment]::SetEnvironmentVariable($n, $null, 'Process')
}

$root = Split-Path -Parent $PSScriptRoot
$log = Join-Path $root '.runlogs'
if (-not (Test-Path $log)) { New-Item -ItemType Directory -Path $log | Out-Null }

$java = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $java) { throw 'PATH 里找不到 java，请先确认 JDK 已安装。' }

$agentDir = Join-Path $root 'life-mind-agent'
$python = Join-Path $agentDir '.venv\Scripts\python.exe'
if (-not (Test-Path $python)) { throw "找不到 agent 的虚拟环境：$python" }

# name / 可执行文件 / 参数 / 工作目录 / 期望端口
$targets = @(
    @{ Name = 'account';  Exe = $java; Args = @('-jar', (Join-Path $root 'life-mind-account\target\life-mind-account-0.0.1-SNAPSHOT.jar')); Cwd = $root;     Port = 8002 }
    @{ Name = 'gateway';  Exe = $java; Args = @('-jar', (Join-Path $root 'life-mind-gateway\target\life-mind-gateway-0.0.1-SNAPSHOT.jar')); Cwd = $root;     Port = 8001 }
    @{ Name = 'message';  Exe = $java; Args = @('-jar', (Join-Path $root 'life-mind-message\target\life-mind-message-0.0.1-SNAPSHOT.jar')); Cwd = $root;     Port = 8003 }
    @{ Name = 'schedule'; Exe = $java; Args = @('-jar', (Join-Path $root 'life-mind-schedule\target\life-mind-schedule-0.0.1-SNAPSHOT.jar')); Cwd = $root;   Port = 8005 }
    @{ Name = 'agent';    Exe = $python; Args = @('run.py'); Cwd = $agentDir; Port = 8004 }
)

foreach ($t in $targets) {
    $jarOrScript = if ($t.Name -eq 'agent') { Join-Path $agentDir 'run.py' } else { $t.Args[1] }
    if (-not (Test-Path $jarOrScript)) {
        Write-Host "[跳过] $($t.Name)：找不到 $jarOrScript" -ForegroundColor Yellow
        continue
    }

    # 端口已占用就认为在跑，避免重复拉起
    $busy = Get-NetTCPConnection -LocalPort $t.Port -State Listen -ErrorAction SilentlyContinue
    if ($busy) {
        Write-Host "[已在运行] $($t.Name) 端口 $($t.Port) 已监听（PID $($busy[0].OwningProcess)）" -ForegroundColor DarkGray
        continue
    }

    $p = Start-Process -FilePath $t.Exe `
        -ArgumentList $t.Args `
        -WorkingDirectory $t.Cwd `
        -RedirectStandardOutput (Join-Path $log "$($t.Name).out.log") `
        -RedirectStandardError  (Join-Path $log "$($t.Name).err.log") `
        -WindowStyle Hidden -PassThru

    Write-Host "[启动] $($t.Name) -> PID $($p.Id)  端口 $($t.Port)" -ForegroundColor Green
}

Write-Host ''
Write-Host "日志目录：$log" -ForegroundColor Cyan
Write-Host 'Java 服务注册 Nacos 需要半分钟到一分钟，用 scripts\status-backend.ps1 看进度。' -ForegroundColor Cyan
