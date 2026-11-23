/**
 * 对话接口。三个流式入口（send / resume / backtrack）的帧格式完全一致，
 * 所以这里只写一次 `stream`，差异只在请求体。
 *
 * 错误处理分两种，不能混：
 * - 流还没开始就失败（会话不存在、没有挂起的中断）→ 后端返回普通 JSON，状态码 400/404，
 *   这里解成 ApiError 抛出，界面按普通错误提示；
 * - 流已经开始后失败 → 后端在流里发一帧 `{"type":"error"}`，此时不能再 throw，
 *   因为 HTTP 状态码已经是 200 了，只能把 error 帧交给上层渲染。
 */
import { BASE, ApiError, SERVICE, del, get, notifyUnauthorized, qs, readToken } from './http'
import { readSseStream } from '../core/sse'
import type {
  AgentTask,
  ChatMessage,
  MessagePage,
  PendingInterrupt,
  SseEvent,
} from '../core/types'

export interface StreamOptions {
  signal?: AbortSignal
  onEvent: (evt: SseEvent) => void
}

/**
 * 发一个流式请求并逐帧回调。
 *
 * 不返回聚合结果：调用方（store）在 onEvent 里就地更新状态，
 * 这样用户看到的文字是边生成边长出来的，而不是等 done 之后一次性刷出。
 */
async function stream(path: string, body: unknown, options: StreamOptions): Promise<void> {
  const token = readToken()

  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal: options.signal,
  })

  if (res.status === 401) {
    notifyUnauthorized()
    throw new ApiError(401, '登录已过期，请重新登录')
  }

  const contentType = res.headers.get('content-type') || ''
  if (!contentType.includes('text/event-stream')) {
    // 走到这里说明请求在图开始执行之前就被拒了，响应体是标准 Result
    const text = await res.text()
    let code = res.status
    let message = `请求失败（HTTP ${res.status}）`
    try {
      const payload = JSON.parse(text) as { code?: number; message?: string }
      if (typeof payload.code === 'number') code = payload.code
      if (payload.message) message = payload.message
    } catch {
      if (text) message = text.slice(0, 200)
    }
    throw new ApiError(code, message)
  }

  await readSseStream(res, (payload) => options.onEvent(payload as SseEvent))
}

export interface SendChatPayload {
  conversationId: number
  content: string
  webSearch?: boolean
}

export function sendChat(payload: SendChatPayload, options: StreamOptions): Promise<void> {
  return stream(
    `${SERVICE.agent}/v1/chat/send`,
    {
      conversation_id: payload.conversationId,
      content: payload.content,
      enable_web_search: payload.webSearch ? 1 : 0,
    },
    options,
  )
}

export interface ResumePayload {
  conversationId: number
  choice: 'approve' | 'reject' | 'edit'
  editContent?: string
  webSearch?: boolean
}

export function resumeChat(payload: ResumePayload, options: StreamOptions): Promise<void> {
  return stream(
    `${SERVICE.agent}/v1/chat/resume`,
    {
      conversation_id: payload.conversationId,
      choice: payload.choice,
      edit_content: payload.editContent,
      enable_web_search: payload.webSearch ? 1 : 0,
    },
    options,
  )
}

export interface BacktrackPayload {
  conversationId: number
  messageId: number
  content: string
}

export function backtrackChat(payload: BacktrackPayload, options: StreamOptions): Promise<void> {
  return stream(
    `${SERVICE.agent}/v1/chat/backtrack`,
    {
      conversation_id: payload.conversationId,
      message_id: payload.messageId,
      content: payload.content,
    },
    options,
  )
}

/** 打开会话时恢复历史消息。用游标而不是页码：聊天会持续新增，页码在翻历史时容易错位 */
export function listMessages(
  conversationId: number,
  beforeId?: number | null,
  size = 30,
): Promise<MessagePage> {
  return get<MessagePage>(
    `${SERVICE.agent}/v1/chat/messages/${conversationId}${qs({ before_id: beforeId ?? undefined, size })}`,
  )
}

export function listTasks(conversationId: number): Promise<AgentTask[]> {
  return get<AgentTask[]>(`${SERVICE.agent}/v1/chat/tasks/${conversationId}`)
}

/**
 * 刷新页面、断线重连后问一句：还有挂起的确认吗？
 * 有就重新弹卡，否则用户会卡在一个永远等不到结果的会话里。
 */
export function getPendingInterrupt(conversationId: number): Promise<PendingInterrupt> {
  return get<PendingInterrupt>(`${SERVICE.agent}/v1/chat/pending/${conversationId}`)
}

/**
 * 清空一个会话的全部历史记录（消息、任务、checkpoint、向量），**不可恢复**。
 * 会话本身保留，还能继续往里发消息——这是「清空记录」，不是「删除会话」。
 */
export function clearConversationMemory(conversationId: number): Promise<void> {
  return del<void>(`${SERVICE.agent}/v1/chat/memory/${conversationId}`)
}
