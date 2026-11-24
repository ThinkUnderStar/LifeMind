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

/**
 * 注册。后端不签发令牌，成功只返回 Result<Void>（data 为 null），
 * 所以这里返回 void，调用方拿到成功后自己把用户送回登录态。
 *
 * 字段规则由后端说了算，前端只做同规则的提前拦截（提示文案对齐 ValidateUtils）：
 * - username  `^[a-zA-Z][a-zA-Z0-9_]{3,15}$`  字母开头，4~16 位
 * - password  8~20 位，必须同时含字母与数字
 * - nickname  1~16 位，中文 / 字母 / 数字 / 下划线 / 连字符
 */
export interface RegisterPayload {
  username: string
  password: string
  /** 仅用于服务端比对，不落库 */
  confirmPassword: string
  nickname: string
  tempKey: string
  captchaCode: string
}

export function register(payload: RegisterPayload): Promise<void> {
  return post<void>(`${SERVICE.account}/auth/register`, payload)
}

/**
 * 手机 / 邮箱验证码登录。
 *
 * 两个验证码都要带：图形验证码证明「是人」，短信/邮件验证码证明「号码是他的」。
 * 图形验证码先换 tempKey（见 newTempKey）再取图，和密码登录是同一套。
 */
export interface CodeLoginPayload {
  /** 手机号或邮箱，与调用的端点一致 */
  target: string
  code: string
  tempKey: string
  captchaCode: string
  rememberMe?: number
}

export function loginByPhone(payload: CodeLoginPayload): Promise<LoginResult> {
  return post<LoginResult>(`${SERVICE.account}/auth/login/phone`, {
    phone: payload.target,
    code: payload.code,
    tempKey: payload.tempKey,
    captchaCode: payload.captchaCode,
    rememberMe: payload.rememberMe ?? 0,
  })
}

export function loginByEmail(payload: CodeLoginPayload): Promise<LoginResult> {
  return post<LoginResult>(`${SERVICE.account}/auth/login/email`, {
    email: payload.target,
    code: payload.code,
    tempKey: payload.tempKey,
    captchaCode: payload.captchaCode,
    rememberMe: payload.rememberMe ?? 0,
  })
}

/**
 * 发送短信 / 邮件验证码。
 *
 * 发送环节不做图形验证码校验（后端注释：人机校验放在登录时），也不判断号码是否注册——
 * 否则这个接口就成了「查这个手机号有没有账号」的探测器。因此调用方不要拿它做存在性判断。
 *
 * 这个接口同时服务于三处：验证码登录、绑定/换绑手机邮箱、用验证码改密。
 * 它们校验的是同一个 Redis 键，所以没必要为每处再拆一个发送入口。
 */
export function sendPhoneCode(phone: string): Promise<void> {
  return post<void>(`${SERVICE.account}/auth/code/send/phone`, { phone })
}

export function sendEmailCode(email: string): Promise<void> {
  return post<void>(`${SERVICE.account}/auth/code/send/email`, { email })
}

/** 注销账号（软删除）。成功后令牌立即作废，调用方必须清本地登录态 */
export function deactivateAccount(): Promise<void> {
  return post<void>(`${SERVICE.account}/auth/deactivate`)
}
