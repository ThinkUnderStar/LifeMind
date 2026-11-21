/**
 * 时间工具。存在的唯一原因：后端两种服务的时间格式不一样，且都不能直接喂给 Date。
 *
 * - Java 侧（日程）：`"2026-11-25 19:00:00"`，空格分隔。`new Date("2026-11-25 19:00:00")`
 *   在 Chrome 能过，Safari 返回 Invalid Date，这是最容易在移动端炸掉的一处。
 * - Python 侧（会话、消息）：ISO 风格，带 `T`，可能带微秒和时区。
 *
 * 两者都在这里归一：先补成 `T` 分隔，剥掉超出毫秒的精度，再交给 Date。
 * 语义上按本地时间理解——后端存的是 LocalDateTime，没有时区概念，
 * 浏览器按本地解释才与用户看到的一致。
 */

const WEEK_LABEL = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

export function parseDateTime(raw: string | null | undefined): Date | null {
  if (!raw) return null
  let text = String(raw).trim()
  if (!text) return null

  // 空格换成 T：Safari / Firefox 都要求 ISO 风格
  text = text.replace(' ', 'T')
  // 微秒（6 位）截到毫秒（3 位），Date 只认到毫秒，多的部分在部分浏览器里直接判非法
  text = text.replace(/(\.\d{3})\d+/, '$1')

  const date = new Date(text)
  return Number.isNaN(date.getTime()) ? null : date
}

/** 提交给后端的 `yyyy-MM-dd HH:mm:ss`。后端 LocalDateTime 只认这个形状 */
export function toDateTimeString(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    ` ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/** 提交给后端的 `yyyy-MM-dd`（LocalDate 参数用） */
export function toDateString(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 年月日，用作分组键 */
export function dayKey(date: Date): string {
  return toDateString(date)
}

export function startOfDay(date: Date): Date {
  const copy = new Date(date)
  copy.setHours(0, 0, 0, 0)
  return copy
}

export function addDays(date: Date, days: number): Date {
  const copy = new Date(date)
  copy.setDate(copy.getDate() + days)
  return copy
}

/** 周一为一周之首，符合中文日历习惯 */
export function startOfWeek(date: Date): Date {
  const copy = startOfDay(date)
  const weekday = copy.getDay() // 0 = 周日
  const offset = weekday === 0 ? -6 : 1 - weekday
  return addDays(copy, offset)
}

export function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  )
}

/** `19:00`。全天事项没有时刻，调用方用 allDay 判断，这里只负责格式化 */
export function timeText(date: Date | null): string {
  if (!date) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** `11月21日 周六` */
export function dayText(date: Date): string {
  return `${date.getMonth() + 1}月${date.getDate()}日 ${WEEK_LABEL[date.getDay()]}`
}

/** 今天 / 明天 / 昨天 / 8月3日。列表里第一眼要认出的就是「这一天是哪天」 */
export function relativeDayText(date: Date): string {
  const today = startOfDay(new Date())
  const target = startOfDay(date)
  const diff = Math.round((target.getTime() - today.getTime()) / 86_400_000)
  if (diff === 0) return '今天'
  if (diff === 1) return '明天'
  if (diff === -1) return '昨天'
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

export function weekdayShort(date: Date): string {
  return WEEK_LABEL[date.getDay()]
}

/** 提醒偏移的人话：0 → 到点，30 → 提前 30 分钟，1440 → 提前一天 */
export function offsetText(minutes: number): string {
  if (minutes === 0) return '到点'
  if (minutes % 1440 === 0) return `提前 ${minutes / 1440} 天`
  if (minutes % 60 === 0) return `提前 ${minutes / 60} 小时`
  return `提前 ${minutes} 分钟`
}
