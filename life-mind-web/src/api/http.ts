/**
 * HTTP 基座。所有请求都从这里出去，只有三个职责：
 * 拼路径、带令牌、解 Result。业务语义一律留在各 api 模块。
 *
 * 几件刻意的事：
 *
 * 1. 请求打到 `/api`，不带主机名。开发期由 nginx 转给网关，生产期同一套配置，
 *    前端不需要知道自己跑在哪。想改目标只改 nginx 的 upstream，不动代码。
 * 2. 身份只靠 `Authorization`。前端永远不发 `LifeMind-User-Id` 之类的头——
 *    那是网关校验 JWT 之后自己注入的，前端塞了也会被覆盖，塞错了则是越权尝试。
 * 3. `code !== 200` 抛错而不是返回 undefined。Java 和 Python 两侧成功码都是 200，
 *    但不成功的响应 HTTP 状态码通常仍是 200，只看 res.ok 会把错误当成成功。
 */
import type { ApiResult } from '../core/types'

/**
 * 服务前缀。网关的路由规则是 `/life-mind/{服务名}/**`，
 * 其中 agent 这一条如果不叫 `/life-mind/agent`，只改这里一处。
 */
export const SERVICE = {
  account: '/life-mind/account',
  schedule: '/life-mind/schedule',
  message: '/life-mind/message',
  agent: '/life-mind/agent',
} as const

export const BASE = (import.meta.env.VITE_API_BASE as string) || '/api'

const TOKEN_KEY = 'lm.token'

export class ApiError extends Error {
  code: number
  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

export function readToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function writeToken(token: string | null) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

/** 令牌失效应答统一广播，由外壳决定怎么回登录页，http 层不认识路由也不认识 UI */
export function notifyUnauthorized() {
  window.dispatchEvent(new CustomEvent('lm:unauthorized'))
}

function authHeaders(): Record<string, string> {
  const token = readToken()
  // 网关白名单内的接口（登录、注册、验证码）本来就不需要令牌，带了也无害
  return token ? { Authorization: `Bearer ${token}` } : {}
}

type Query = Record<string, string | number | boolean | null | undefined>

/** 拼查询串。null / undefined / 空串直接丢掉，不发 `?status=` 这种空参数给后端 */
export function qs(params?: Query): string {
  if (!params) return ''
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    search.append(key, String(value))
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

async function parse<T>(res: Response): Promise<T> {
  if (res.status === 401) {
    notifyUnauthorized()
    throw new ApiError(401, '登录已过期，请重新登录')
  }

  const text = await res.text()
  let payload: ApiResult<T> | null = null
  try {
    payload = text ? (JSON.parse(text) as ApiResult<T>) : null
  } catch {
    // 网关直接返回非 JSON（比如 502 的 HTML 错误页），保留状态码让调用方判断
    throw new ApiError(res.status, `网关返回了非 JSON 响应（HTTP ${res.status}）`)
  }

  if (!payload) throw new ApiError(res.status, '响应体为空')
  if (payload.code !== 200) throw new ApiError(payload.code, payload.message || '请求失败')
  return payload.data
}

export async function get<T>(path: string, params?: Query): Promise<T> {
  const res = await fetch(`${BASE}${path}${qs(params)}`, {
    method: 'GET',
    headers: { ...authHeaders() },
  })
  return parse<T>(res)
}

export async function send<T>(
  method: 'POST' | 'PUT' | 'DELETE',
  path: string,
  body?: unknown,
): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return parse<T>(res)
}

export const post = <T>(path: string, body?: unknown) => send<T>('POST', path, body)
export const put = <T>(path: string, body?: unknown) => send<T>('PUT', path, body)
export const del = <T>(path: string) => send<T>('DELETE', path)

/**
 * multipart 上传。不能复用 send()：那里固定 Content-Type: application/json，
 * 手动传 boundary 只会传错。这里交给浏览器自己生成 multipart 边界。
 */
export async function upload<T>(path: string, form: FormData): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { ...authHeaders() },
    body: form,
  })
  return parse<T>(res)
}
