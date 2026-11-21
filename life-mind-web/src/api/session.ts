/**
 * 会话管理。全部是普通 JSON 接口，不触达图，所以改会话标题不会让 agent 重跑。
 *
 * 时间字段是 Python datetime 的序列化结果，与 Java 的「空格分隔」格式不同，
 * 解析一律走 core/date.ts 的 parseDateTime，它两种都认。
 */
import { SERVICE, del, get, post, put } from './http'
import type { Conversation, Page } from '../core/types'

export interface SessionQuery {
  keyword?: string
  page?: number
  size?: number
  archived?: boolean
}

export function listSessions(query: SessionQuery = {}): Promise<Page<Conversation>> {
  return get<Page<Conversation>>(`${SERVICE.agent}/v1/session/list`, {
    keyword: query.keyword,
    page: query.page ?? 1,
    size: query.size ?? 30,
    archived: query.archived ?? false,
  })
}

export function createSession(title?: string): Promise<Conversation> {
  return post<Conversation>(`${SERVICE.agent}/v1/session/create`, title ? { title } : {})
}

export function renameSession(conversationId: number, title: string): Promise<void> {
  return put<void>(`${SERVICE.agent}/v1/session/title`, { conversation_id: conversationId, title })
}

export function archiveSession(conversationId: number, archived: boolean): Promise<void> {
  return put<void>(`${SERVICE.agent}/v1/session/archive`, {
    conversation_id: conversationId,
    archived,
  })
}

export function deleteSession(conversationId: number): Promise<void> {
  return del<void>(`${SERVICE.agent}/v1/session/${conversationId}`)
}
