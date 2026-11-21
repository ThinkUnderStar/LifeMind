#!/usr/bin/env node
/**
 * `npm run dev` 的入口。做三件事：起 Vite、起 nginx、把两者绑在一条命令的生命周期里。
 *
 * 为什么需要这个脚本而不是两条命令：
 * 1. nginx 要用「项目内前缀」启动，配置里的 logs/tmp 相对路径才能落在项目里，
 *    否则 Windows 上会去写 nginx 安装目录，直接 Access denied；
 * 2. nginx 只做转发，Vite 起不来时它转了也没用，所以必须先等 5173 真正能连上；
 * 3. Ctrl+C 时 nginx 是后台常驻进程，不会跟着终端退出，必须显式 -s stop，
 *    否则下次启动会报端口占用。
 *
 * 没装 nginx 时自动降级到 Vite 自带代理：不拦人，但会打印一行说明当前是降级模式。
 * 两条路径的后端目标都是网关 8001，行为一致，差别只在「跨域由谁处理」。
 */
import { spawn, spawnSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { connect } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import process from 'node:process'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const DEV_PORT = 5173 // 必须与 vite.config.ts 的 strictPort 一致
const WEB_PORT = Number(process.env.LM_WEB_PORT || 8088)
const GATEWAY = process.env.LM_GATEWAY || 'http://127.0.0.1:8001'

/**
 * nginx for Windows 内部用 ANSI 版 Win32 API（CreateFileA）打开文件，
 * 路径里出现任何非 ASCII 字符都会失败并报
 *   1113: No mapping for the Unicode character exists in the target multi-byte code page
 * 这和权限无关，把目录 ACL 开到最大也没用；也不能靠 chcp 65001 解决。
 *
 * 实测三种组合（nginx 1.26.2 / Windows）：
 *   中文 prefix + 中文 exe  → 起不来，连 error.log 都写不出一行
 *   中文 exe + ASCII prefix → 起不来，进程根本创建不出来
 *   ASCII exe + ASCII prefix → 正常
 * 所以只要项目路径含中文，就必须把「可执行文件」和「运行时目录」双双挪到 ASCII 路径。
 *
 * 本项目路径是「桌面\勿动\LifeMind」，正好踩中。运行时目录改放 %LOCALAPPDATA%，
 * exe 从 tools/nginx 复制过去（tools 目录本身也带中文）。Vite 不受影响，
 * 它是 Node 程序，走 Unicode API，跑在中文路径下完全正常。
 */
const ASCII_SAFE = /^[\x20-\x7E]*$/
const ASCII_ROOT = ASCII_SAFE.test(ROOT)
const NGINX_DIR = ASCII_ROOT
  ? join(ROOT, '.nginx')
  : join(process.env.LOCALAPPDATA || tmpdir(), 'life-mind-web', 'nginx')
const NGINX_CONF = join(NGINX_DIR, 'run.conf')

let nginxBin = null
let nginxRunning = false
let viteProc = null
let shuttingDown = false

const say = (text) => console.log(text)
const warn = (text) => console.warn(text)

/* ---------------------------- 工具 ---------------------------- */

function canConnect(port, host = '127.0.0.1', timeout = 600) {
  return new Promise((done) => {
    const socket = connect({ port, host })
    const finish = (ok) => {
      socket.destroy()
      done(ok)
    }
    socket.setTimeout(timeout)
    socket.once('connect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
  })
}

async function waitForPort(port, label, tries = 60) {
  for (let i = 0; i < tries; i += 1) {
    if (await canConnect(port)) return true
    await new Promise((r) => setTimeout(r, 300))
  }
  throw new Error(`${label} 在 ${Math.round((tries * 300) / 1000)} 秒内没有起来`)
}

/**
 * 找 nginx。顺序是「显式指定 → PATH → 各个常见安装位置」，
 * 找不到返回 null，由调用方降级。
 */
function findNginx() {
  const explicit = [process.env.NGINX_BIN, process.env.NGINX_HOME && join(process.env.NGINX_HOME, process.platform === 'win32' ? 'nginx.exe' : 'sbin/nginx')]
    .filter(Boolean)

  for (const candidate of explicit) {
    if (existsSync(candidate)) return candidate
  }

  // PATH 里的 nginx
  const probe = spawnSync(process.platform === 'win32' ? 'where' : 'which', ['nginx'], {
    encoding: 'utf8',
  })
  if (!probe.error && probe.status === 0) {
    const first = (probe.stdout || '').split(/\r?\n/)[0].trim()
    if (first && existsSync(first)) return first
  }

  const winPaths = [
    join(ROOT, 'tools/nginx/nginx.exe'),
    'C:/nginx/nginx.exe',
    'C:/tools/nginx/nginx.exe',
    'C:/Program Files/nginx/nginx.exe',
  ]
  if (process.platform === 'win32') {
    for (const p of winPaths) if (existsSync(p)) return p
  }

  return null
}

/**
 * 找到的 nginx 如果躺在非 ASCII 路径下，复制一份到运行时目录再执行。
 * 只复制 exe：本项目的 conf 是自包含的（不 include mime.types、不设 root），
 * 不依赖 nginx 发行版自带的 conf/ 与 html/ 目录。
 */
function toAsciiBinary(binPath) {
  if (ASCII_SAFE.test(binPath)) return binPath

  const target = join(NGINX_DIR, 'bin', 'nginx.exe')
  mkdirSync(dirname(target), { recursive: true })
  const stale = !existsSync(target) || statSync(target).size !== statSync(binPath).size
  if (stale) cpSync(binPath, target)
  return target
}

/** 生成运行时配置。模板里的占位符只换端口，其余原样保留便于对照排查 */
function prepareNginx() {
  mkdirSync(join(NGINX_DIR, 'logs'), { recursive: true })
  mkdirSync(join(NGINX_DIR, 'tmp'), { recursive: true })

  const templatePath = join(ROOT, 'nginx/life-mind-web.conf.template')
  if (!existsSync(templatePath)) throw new Error('找不到 nginx/life-mind-web.conf.template')

  const conf = readFileSync(templatePath, 'utf8').replaceAll('__PORT__', String(WEB_PORT))
  writeFileSync(NGINX_CONF, conf, 'utf8')
}

function stopNginx() {
  if (!nginxBin || !nginxRunning) return
  nginxRunning = false
  spawnSync(nginxBin, ['-p', NGINX_DIR, '-c', NGINX_CONF, '-s', 'stop'], { stdio: 'ignore' })
}

function shutdown(code = 0) {
  if (shuttingDown) return
  shuttingDown = true

  stopNginx()

  if (viteProc && !viteProc.killed) {
    // Windows 下 kill 信号对 node 子进程不可靠，直接杀进程树
    if (process.platform === 'win32' && viteProc.pid) {
      spawnSync('taskkill', ['/pid', String(viteProc.pid), '/T', '/F'], { stdio: 'ignore' })
    } else {
      viteProc.kill('SIGTERM')
    }
  }

  process.exit(code)
}

/* ---------------------------- 主流程 ---------------------------- */

async function main() {
  if (await canConnect(DEV_PORT)) {
    throw new Error(
      `${DEV_PORT} 已经被占用。多半是上次的 dev server 没退干净，\n` +
        `先关掉它（Windows: netstat -ano | findstr :${DEV_PORT} 然后 taskkill /PID <pid> /F）再运行。`,
    )
  }

  const found = findNginx()
  if (found) {
    nginxBin = toAsciiBinary(found)
    if (nginxBin !== found) {
      say(`[web] 项目路径含非 ASCII 字符，nginx 无法原地运行，已改用 ${nginxBin}`)
    }
  }

  if (nginxBin && (await canConnect(WEB_PORT))) {
    throw new Error(`${WEB_PORT} 已经被占用，nginx 起不来。先停掉占用它的进程，或设 LM_WEB_PORT 换一个端口。`)
  }

  // 1. 先起 Vite：它是内容来源，nginx 只是转发层
  const viteEntry = join(ROOT, 'node_modules/vite/bin/vite.js')
  if (!existsSync(viteEntry)) {
    throw new Error('还没有安装依赖，先跑一次 npm install')
  }

  say(`[web] 启动 Vite（${DEV_PORT}）…`)
  viteProc = spawn(process.execPath, [viteEntry, '--port', String(DEV_PORT)], {
    cwd: ROOT,
    stdio: 'inherit',
    env: { ...process.env },
  })
  viteProc.on('exit', (code) => {
    if (!shuttingDown) {
      warn(`\n[web] Vite 退出了（code ${code}），一并关闭 nginx。`)
      shutdown(code ?? 0)
    }
  })

  await waitForPort(DEV_PORT, 'Vite')

  // 2. 再起 nginx
  if (nginxBin) {
    prepareNginx()
    say(`[web] 启动 nginx（${WEB_PORT} → 网关 ${GATEWAY}）…`)

    // 不能用 spawnSync 等 nginx 返回：Windows 没有 fork，nginx.exe 本身就是
    // master 进程，启动后不会退出，spawnSync 会永远阻塞在那一行（端口已经监听，
    // 但脚本再也不往下走）。改成非阻塞启动，用端口探测判断成败。
    let errText = ''
    const nginxProc = spawn(nginxBin, ['-p', NGINX_DIR, '-c', NGINX_CONF], {
      stdio: ['ignore', 'ignore', 'pipe'],
      windowsHide: true,
    })
    nginxProc.stderr?.on('data', (chunk) => {
      errText += String(chunk)
    })
    nginxProc.on('exit', (code) => {
      // master 意外退出：端口会消失，前端表现为「页面打不开」，这里给一句人话
      if (nginxRunning && !shuttingDown) {
        nginxRunning = false
        warn(`\n[web] nginx 意外退出（code ${code}），${WEB_PORT} 已不可用。`)
        warn(errText.trim() || `查看 ${join(NGINX_DIR, 'logs', 'error.log')}`)
      }
    })

    nginxRunning = true
    try {
      await waitForPort(WEB_PORT, `nginx ${WEB_PORT}`, 25)
    } catch {
      throw new Error(
        `nginx 启动失败：\n${errText.trim() || `没有输出，查看 ${join(NGINX_DIR, 'logs', 'error.log')}`}`,
      )
    }

    say('')
    say(`  页面    http://localhost:${WEB_PORT}`)
    say(`  接口    http://localhost:${WEB_PORT}/api/  →  ${GATEWAY}`)
    say(`  热更新  Vite 直连 ${DEV_PORT}（变化通过 nginx 的 websocket 转发）`)
    say('')
    say('  跨域由 nginx 处理，浏览器侧同源，后端不需要额外 CORS 配置。')
  } else {
    say('')
    say(`  页面    http://localhost:${DEV_PORT}`)
    say(`  接口    /api  →  ${GATEWAY}（Vite 代理）`)
    say('')
    warn('  没有找到 nginx，已降级为 Vite 自带代理。')
    warn('  功能一致，但「跨域统一由 nginx 处理」这条约定没有生效。')
    warn('  装好 nginx 后无需改代码：放进 PATH、C:\\nginx，或项目内 tools/nginx/，脚本会自动切回来。')
    warn('  也可以显式指定：NGINX_BIN=/path/to/nginx npm run dev')
  }

  say('')
  say('  按 Ctrl+C 停止。')
  say('')
}

process.on('SIGINT', () => shutdown(0))
process.on('SIGTERM', () => shutdown(0))

main().catch((error) => {
  warn(`\n[web] ${error.message}\n`)
  shutdown(1)
})
