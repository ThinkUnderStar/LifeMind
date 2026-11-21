/**
 * 站内通知。
 *
 * 弹窗只认两个条件同时成立：`needPopup=1` 且 `isRead=0`。这是后端为网页内提醒
 * 特意加的口径——`level` 表达事情多要紧，`needPopup` 表达要不要打断用户，
 * 两者会反向（系统公告可能 level=1 但不弹，日程提醒 level=0 却必须弹）。
 *
 * 已知限制：通知表没有「已弹过」的时间字段，所以同一条未读通知在用户点掉之前
 * 会被反复取到。前端的做法是本地记一份已弹 id 集合，关掉不重复弹——
 * 这是界面层的补丁，刷新后失效，等后端补 notified_time 才能真正根治。
 */
import { SERVICE, get, post, qs } from './http'
import type { Notice, Page } from '../core/types'

/** 取「未读且要弹窗」的通知，给轮询用 */
export function popupNotices(): Promise<Page<Notice>> {
  return get<Page<Notice>>(`${SERVICE.message}/notification/page`, {
    pageNum: 1,
    pageSize: 20,
    isRead: 0,
    needPopup: 1,
  })
}

export function pageNotices(params: {
  pageNum?: number
  pageSize?: number
  isRead?: number
  type?: string
}): Promise<Page<Notice>> {
  return get<Page<Notice>>(
    `${SERVICE.message}/notification/page`,
    {
      pageNum: params.pageNum ?? 1,
      pageSize: params.pageSize ?? 20,
      isRead: params.isRead,
      type: params.type,
    },
  )
}

export function markRead(id: number): Promise<void> {
  return post<void>(`${SERVICE.message}/notification/read`, { id })
}

/** 未读数：接口只支持分页，取一页的 total 即可，不必逐条数 */
export async function unreadCount(): Promise<number> {
  const page = await get<Page<Notice>>(`${SERVICE.message}/notification/page${qs({ pageSize: 1, isRead: 0 })}`)
  return page.total
}
