/**
 * 用户资料。后端 `/life-mind/account/user/**` 都是「改自己」，不接受用户 ID；
 * 身份由网关注入的请求头确定，所以这里所有方法都不带 id 参数。
 *
 * 校验规则与后端 ValidateUtils 对齐，前端只做提前拦截，最终以服务端为准：
 * - 昵称     `^[\u4e00-\u9fa5a-zA-Z0-9_-]{1,16}$`
 * - 账户名   `^[a-zA-Z][a-zA-Z0-9_]{3,15}$`
 * - 密码     8~20 位，不能纯数字或纯字母
 */
import { SERVICE, get, post, upload } from './http'

export interface UserProfile {
  username: string
  /** 未设置时为 null */
  nickname: string | null
  /** 未绑定为 null */
  email: string | null
  /** 未绑定为 null */
  phone: string | null
  /** 形如 /minio/life-mind-public/users/1/avatar/xxx.png，未设置为 null */
  avatar: string | null
  /** 上次修改账户名的时间，null 表示从未改过 */
  usernameChangeTime: string | null
}

export function getProfile(): Promise<UserProfile> {
  return get<UserProfile>(`${SERVICE.account}/user/profile`)
}

/** 昵称是展示资料，不需要验证码；与当前昵称相同会被后端拒绝 */
export function changeNickname(nickname: string): Promise<void> {
  return post<void>(`${SERVICE.account}/user/nickname`, { nickname })
}

/** 账户名是登录名：不能重名，且一年只能改一次；改完要用新名字登录 */
export function changeUsername(username: string): Promise<void> {
  return post<void>(`${SERVICE.account}/user/username`, { username })
}

/** 改密成功后后端会把令牌版本号 +1，所有已签发的令牌立即失效，需要重新登录 */
export function changePasswordByOriginal(payload: {
  originalPassword: string
  newPassword: string
  confirmPassword: string
}): Promise<void> {
  return post<void>(`${SERVICE.account}/user/password/original`, payload)
}

/** 头像走 multipart，表单字段名必须是 avatar；后端限制常见图片后缀且不超过 2MB */
export function changeAvatar(file: File): Promise<void> {
  const form = new FormData()
  form.append('avatar', file)
  return upload<void>(`${SERVICE.account}/user/avatar`, form)
}
