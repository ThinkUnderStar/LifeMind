/**
 * 日程插件的数据访问。按后端三个模块分组：事项、系列、提醒、统计、备忘。
 *
 * 两个容易踩的点，都在这里封住不让上层见到：
 * 1. 时间参数是 `yyyy-MM-dd`（LocalDate），时刻参数是 `yyyy-MM-dd HH:mm:ss`
 *    （LocalDateTime）。混用会被后端拒，但报错信息看不出是格式问题。
 * 2. 系列改/删都要 `scope`：`all` 改整个系列，`future` 从 `anchorItemId` 那一次起。
 *    `future` 不带 anchorItemId 后端会 400，这里在类型上就要求成对出现。
 */
import { SERVICE, del, get, post, put, qs } from './http'
import type {
  BatchResult,
  DailyStat,
  Item,
  ItemQuery,
  Memo,
  Overview,
  Page,
  Reminder,
  Series,
} from '../core/types'

const ITEM = `${SERVICE.schedule}/item`
const SERIES = `${SERVICE.schedule}/series`
const MEMO = `${SERVICE.schedule}/memo`
const STAT = `${SERVICE.schedule}/statistics`
const REMINDER = `${SERVICE.schedule}/reminder`

/* ---------------- 事项 ---------------- */

export function pageItems(query: ItemQuery = {}): Promise<Page<Item>> {
  return get<Page<Item>>(`${ITEM}/page`, {
    pageNum: query.pageNum ?? 1,
    pageSize: query.pageSize ?? 50,
    itemType: query.itemType,
    status: query.status,
    keyword: query.keyword,
    overdue: query.overdue,
    date: query.date,
    startDate: query.startDate,
    endDate: query.endDate,
    seriesId: query.seriesId,
  })
}

export function getItem(id: number): Promise<Item> {
  return get<Item>(`${ITEM}/${id}`)
}

export interface ItemCreatePayload {
  itemType: 'schedule' | 'todo'
  title: string
  content?: string
  location?: string
  startTime?: string
  endTime?: string
  dueTime?: string
  allDay?: number
  priority?: number
  tags?: string
  remindOffsets?: number[]
  requestId?: string
}

export function createItem(payload: ItemCreatePayload): Promise<Item> {
  return post<Item>(`${ITEM}/create`, payload)
}

export function updateItem(payload: Partial<ItemCreatePayload> & { id: number }): Promise<Item> {
  return put<Item>(`${ITEM}/update`, payload)
}

/** 完成 / 取消完成。同一个接口，靠 completed 区分，不做两个端点 */
export function completeItem(id: number, completed = true): Promise<void> {
  return put<void>(`${ITEM}/complete`, { id, completed })
}

export function cancelItem(id: number): Promise<void> {
  return put<void>(`${ITEM}/cancel`, { id })
}

export function deleteItem(id: number): Promise<void> {
  return del<void>(`${ITEM}/${id}`)
}

/**
 * 批量取详情 / 批量完成 / 批量删除。
 *
 * 后端这三个批量接口都是「逐条回报、一条失败不影响其余」，所以完成与删除返回 BatchResult
 * 而不是 void：界面需要知道哪几条没成，不能笼统说「操作成功」。
 */
export function batchGetItems(ids: number[]): Promise<Item[]> {
  return get<Item[]>(`${ITEM}/batch`, { ids: ids.join(',') })
}

export function batchCompleteItems(ids: number[], completed = true): Promise<BatchResult> {
  return put<BatchResult>(`${ITEM}/batch/complete`, { ids, completed })
}

export function batchDeleteItems(ids: number[]): Promise<BatchResult> {
  return post<BatchResult>(`${ITEM}/batch/delete`, { ids })
}

/* ---------------- 统计 ---------------- */

export function overview(): Promise<Overview> {
  return get<Overview>(`${STAT}/overview`)
}

export function dailyStats(startDate?: string, endDate?: string): Promise<DailyStat[]> {
  return get<DailyStat[]>(`${STAT}/daily`, { startDate, endDate })
}

/* ---------------- 提醒 ---------------- */

export function upcomingReminders(days = 7): Promise<Reminder[]> {
  return get<Reminder[]>(`${REMINDER}/upcoming`, { days })
}

export function remindersOfItem(itemId: number): Promise<Reminder[]> {
  return get<Reminder[]>(`${REMINDER}/list`, { itemId })
}

/* ---------------- 备忘 ---------------- */

export function pageMemos(pageNum = 1, pageSize = 50, keyword?: string): Promise<Page<Memo>> {
  return get<Page<Memo>>(`${MEMO}/page`, { pageNum, pageSize, keyword })
}

/** 单条详情。备忘内容可能很长，列表里只给摘要，展开时再取全文 */
export function getMemo(id: number): Promise<Memo> {
  return get<Memo>(`${MEMO}/${id}`)
}

export function createMemo(payload: {
  title: string
  content?: string
  tags?: string
}): Promise<Memo> {
  return post<Memo>(`${MEMO}/create`, payload)
}

export function updateMemo(payload: {
  id: number
  title?: string
  content?: string
  tags?: string
}): Promise<Memo> {
  return put<Memo>(`${MEMO}/update`, payload)
}

export function deleteMemo(id: number): Promise<void> {
  return del<void>(`${MEMO}/${id}`)
}

export function pinMemo(id: number, pinned: boolean): Promise<void> {
  return put<void>(`${MEMO}/pin`, { id, pinned })
}

/* ---------------- 重复系列 ---------------- */

export function listSeries(): Promise<Series[]> {
  return get<Series[]>(`${SERIES}/list`)
}

export function getSeries(id: number): Promise<Series> {
  return get<Series>(`${SERIES}/${id}`)
}

export interface SeriesCreatePayload {
  itemType: 'schedule' | 'todo'
  title: string
  content?: string
  location?: string
  priority?: number
  allDay?: number
  tags?: string
  freq: 'DAILY' | 'WEEKLY' | 'MONTHLY'
  interval?: number
  byDay?: string[]
  untilDate?: string
  countLimit?: number
  baseTime: string
  durationMinutes?: number
  remindOffsets?: number[]
  requestId?: string
}

export function createSeries(payload: SeriesCreatePayload): Promise<Series> {
  return post<Series>(`${SERIES}/create`, payload)
}

/**
 * 改系列。`scope=future` 时 `anchorItemId` 必填——服务端靠它判断「从哪一次起」，
 * 光给日期它无法确定用户指的是哪一条实例。
 */
export type SeriesUpdatePayload = {
  id: number
  title?: string
  content?: string
  location?: string
  priority?: number
  allDay?: number
  tags?: string
  freq?: 'DAILY' | 'WEEKLY' | 'MONTHLY'
  interval?: number
  byDay?: string[]
  untilDate?: string
  countLimit?: number
  remindOffsets?: number[]
} & ({ scope: 'all' } | { scope: 'future'; anchorItemId: number })

export function updateSeries(payload: SeriesUpdatePayload): Promise<Series> {
  return put<Series>(`${SERIES}/update`, payload)
}

export type SeriesDeletePayload =
  | { id: number; scope: 'all' }
  | { id: number; scope: 'future'; anchorItemId: number }

export function deleteSeries(payload: SeriesDeletePayload): Promise<void> {
  return post<void>(`${SERIES}/delete`, payload)
}
