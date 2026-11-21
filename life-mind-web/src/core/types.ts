/**
 * 后端契约的唯一事实来源，逐字段对照 Java DTO 与 Python schema 抄下来的。
 * 组件里不出现 any，接口一改这里先报错。
 *
 * 两套命名并存不是笔误：Java 侧（account/schedule/message）是驼峰，
 * Python 侧（agent）是蛇形。前端不做全局转换，各自按原样声明，
 * 转换层只存在于「视图需要」的地方，避免出现一个「万能 camelize」把
 * 后端某天新加的字段也悄悄改名。
 */

/** 统一响应体。Java 的 Result 与 Python 的 Result 都是这个形状 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
  timestamp?: number
}

/** 分页。Java 侧返回 MyBatis-Plus 的 IPage，Python 侧是 PageData，字段名恰好一致 */
export interface Page<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages?: number
}

/* =========================== 账号 / 插件 =========================== */

export interface LoginResult {
  jwtToken: string
  rememberMe: number
}

export interface PluginInfo {
  pluginKey: string
  name: string
  description: string
  icon: string
  version: string
  enabled: boolean
}

/* =========================== 会话 / 消息 =========================== */

/** agent 侧会话，时间是 ISO 风格（Python datetime 序列化），注意与 Java 的空格格式不同 */
export interface Conversation {
  id: number
  title: string
  status: number
  create_time: string | null
  update_time: string | null
}

export interface ChatMessage {
  id: number
  role: 'user' | 'assistant' | 'system'
  content: string
  status: number
  task_bind_id: string | null
  create_time: string | null
}

export interface MessagePage {
  records: ChatMessage[]
  has_more: boolean
  next_before_id: number | null
}

/** 任务清单。status 只有 pending/running/done 三值，done 表示「这条结束了」而非「成功」 */
export interface AgentTask {
  id: number
  goal: string
  status: 'pending' | 'running' | 'done'
  result: string | null
  create_time: string | null
  update_time: string | null
}

/** 挂起的人工确认。interrupt 的形状与 SSE 的 interrupt 帧同源 */
export interface PendingInterrupt {
  has_pending: boolean
  interrupt: ApprovalPayload | null
}

export interface ApprovalPayload {
  question: string
  options: Array<'approve' | 'reject' | 'edit'>
}

/* =========================== 日程插件 =========================== */

/**
 * 事项。一行 = 一个具体时刻：日程看 startTime，待办看 dueTime。
 * 时间字符串是 Java 的 "yyyy-MM-dd HH:mm:ss"（空格分隔），不是 ISO，
 * 直接喂给 new Date() 在 Safari 上会得到 Invalid Date，必须走 parseDateTime。
 */
export interface Item {
  id: number
  itemType: 'schedule' | 'todo'
  title: string
  content: string | null
  location: string | null
  startTime: string | null
  endTime: string | null
  dueTime: string | null
  allDay: number
  priority: number
  status: number
  completedTime: string | null
  tags: string | null
  remindOffsets: number[] | null
  requestId: string | null
  source: string
  seriesId: number | null
  seriesIndex: number | null
  createTime: string | null
  updateTime: string | null
}

export interface ItemQuery {
  pageNum?: number
  pageSize?: number
  itemType?: 'schedule' | 'todo'
  status?: number
  keyword?: string
  overdue?: boolean
  date?: string
  startDate?: string
  endDate?: string
  seriesId?: number
}

/** 重复系列。baseTime 对日程是开始时刻、对待办是截止时刻 */
export interface Series {
  id: number
  itemType: 'schedule' | 'todo'
  title: string
  content: string | null
  location: string | null
  priority: number
  allDay: number
  tags: string | null
  freq: 'DAILY' | 'WEEKLY' | 'MONTHLY'
  interval: number
  byDay: string[] | null
  untilDate: string | null
  countLimit: number | null
  baseTime: string
  durationMinutes: number | null
  remindOffsets: number[] | null
  status: number
  source: string
  itemCount: number
  firstItemId: number | null
  lastItemId: number | null
  createTime: string | null
  updateTime: string | null
}

export interface Memo {
  id: number
  title: string
  content: string | null
  tags: string | null
  pinned: number
  createTime: string | null
  updateTime: string | null
}

export interface Reminder {
  id: number
  itemId: number
  remindTime: string
  offsetMinutes: number
  status: number
}

export interface Overview {
  todayScheduleCount: number
  todayTodoCount: number
  todayTodoDoneCount: number
  overdueTodoCount: number
  weekTodoCount: number
  weekTodoDoneCount: number
  weekCompletionRate: number
}

export interface DailyStat {
  date: string
  scheduleCount: number
  todoCount: number
  todoDoneCount: number
}

/* =========================== 通知 =========================== */

export interface Notice {
  id: number
  type: string
  title: string
  content: string | null
  senderId: number | null
  bizType: string | null
  bizId: string | null
  jumpUrl: string | null
  level: number
  /** 1 = 到点要打断用户。前端只轮询这个为 1 且未读的 */
  needPopup: number
  isRead: number
  readTime: string | null
  extra: string | null
  createTime: string | null
}

/* =========================== SSE 帧 =========================== */

/**
 * `/chat/send|resume|backtrack` 的帧，统一为 `data: {"type":...,"data":...}`。
 *
 * text      回复片段，直接追加
 * tool      插件工具开始/结束，start 与 end 带同一个 id，前端据此配对
 * ui        该打开哪个插件面板、定位到哪一天或哪一条（只在 start 时推）
 * interrupt 需要用户确认，弹卡后调 /chat/resume
 * done      本轮结束
 * error     执行失败
 */
export type SseEvent =
  | { type: 'text'; data: string }
  | { type: 'tool'; data: ToolFrame }
  | { type: 'ui'; data: UiFrame }
  | { type: 'interrupt'; data: ApprovalPayload }
  | { type: 'done'; data?: null }
  | { type: 'error'; data: string }

export interface ToolFrame {
  id: string
  phase: 'start' | 'end'
  plugin: string
  action: string
}

export interface UiFrame {
  id: string
  plugin: string
  /** 插件内部的面板名，如 week / list / reminder / memo / stats */
  view: string
  /** 人话动作名，如「查看日程」 */
  label: string
  params: { date?: string; id?: number }
}
