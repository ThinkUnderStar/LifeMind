/**
 * 意见反馈。
 *
 * 普通用户能用两条：
 * - `POST /feedback/submit`：提交，可带一张截图；
 * - `GET /feedback/mine`：查自己提交过的，带处理状态与回复。
 *
 * `/feedback/page` 与 `/feedback/process` 是管理员后台，不进用户界面。
 *
 * 提交走 multipart/form-data，分两个 part：`data` 是一段 JSON（Blob），`file` 是截图。
 * 之所以不再发纯 JSON，是因为要收图：`@RequestBody` 吃不下 multipart 的请求体，
 * 旧签名里那个 `@RequestParam file` 从来没生效过。
 */
import { SERVICE, get, upload } from './http'
import type { FeedbackRecord, FeedbackSubmitPayload, Page } from '../core/types'

export function submitFeedback(payload: FeedbackSubmitPayload): Promise<void> {
  const data = {
    type: payload.type,
    content: payload.content,
    contact: payload.contact ?? null,
  }

  const form = new FormData()
  // 这个 type 不能省：服务端按 content-type 选转换器，标成 application/json 才会反序列化成对象
  form.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }))
  if (payload.file) form.append('file', payload.file)

  return upload<void>(`${SERVICE.message}/feedback/submit`, form)
}

/** 我提交过的反馈，按提交时间倒序 */
export function myFeedback(pageNum = 1, pageSize = 20): Promise<Page<FeedbackRecord>> {
  return get<Page<FeedbackRecord>>(`${SERVICE.message}/feedback/mine`, { pageNum, pageSize })
}
