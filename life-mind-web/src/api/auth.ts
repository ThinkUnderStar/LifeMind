/**
 * 认证。密码登录必须带图形验证码，这是后端的硬性要求，不是可选项：
 * AuthServiceImpl 在 tempKey 或 captchaCode 缺失时直接抛「验证码临时Key不能为空」。
 *
 * 验证码接口返回的是图片字节流，不是 Result，所以这里既不能用 get<T>()，
 * 也不能把地址写进 <img src> 之外的地方——它是唯一一个不返回 JSON 的接口。
 */
import { BASE, SERVICE, post, qs } from './http'
import type { LoginResult } from '../core/types'

/** tempKey 必须是 UUID：后端用 uuidValidate 校验，格式不对直接拒绝 */
export function newTempKey(): string {
  // crypto.randomUUID 只在安全上下文可用；localhost 与 https 都算，开发期没问题
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

/** 验证码图片地址。带 t 参数防浏览器复用上一张（同一 tempKey 重取会覆盖服务端答案） */
export function captchaImageUrl(tempKey: string): string {
  return `${BASE}${SERVICE.account}/captcha/get${qs({ tempKey, t: Date.now() })}`
}

export interface LoginPayload {
  username: string
  password: string
  tempKey: string
  captchaCode: string
  rememberMe?: number
}

export function login(payload: LoginPayload): Promise<LoginResult> {
  return post<LoginResult>(`${SERVICE.account}/auth/login/password`, {
    username: payload.username,
    password: payload.password,
    tempKey: payload.tempKey,
    captchaCode: payload.captchaCode,
    rememberMe: payload.rememberMe ?? 0,
  })
}
