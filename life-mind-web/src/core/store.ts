/**
 * 全局状态与动作。没有引入状态库：这个界面只有一份状态、一棵组件树，
 * 一个 reactive 对象加一组函数就够，多引一个库只是多一层要学的东西。
 *
 * 分工会被反复用到，先说清：
 * - 这里只管「对话与外壳」的状态：会话、消息、流、确认卡、当前插件的哪个分段。
 * - **面板里的业务数据不归这里管**。日程的列表、统计、系列都由面板组件自己拉，
 *   store 只递增 `panelEpoch` 通知它「数据可能变了」。这样加一个插件不必改 store。
 */
import { reactive } from 'vue'
import type {
  AgentTask,
  ApprovalPayload,
  Conversation,
  Notice,
  PluginInfo,
  SseEvent,
  ToolFrame,
  UiFrame,
} from './types'
import { login as loginApi } from '../api/auth'
import { listPlugins, setPluginEnabled } from '../api/plugin'
import * as sessionApi from '../api/session'
import * as chatApi from '../api/chat'
import { popupNotices, markRead } from '../api/notice'
import { readToken, writeToken } from '../api/http'
import { resolvePlugin, resolveTab } from './plugin-registry'

export interface UiMessage {
  /** 本地唯一键。历史消息用 `m-<后端id>`，临时消息用 `t-<序号>` */
  key: string
  /** 后端 id。改写过的那条用户消息要拿它调回溯接口 */
  id: number | null
  role: 'user' | 'assistant'
  content: string
  /** 正在长出来的气泡，用来显示光标 */
  streaming: boolean
}

export interface Toast {
  id: number
  text: string
  kind: 'info' | 'error'
}

const ACCOUNT_KEY = 'lm.account'

let seq = 0
const nextKey = () => `t-${++seq}`

export const state = reactive({
  booted: false,
  authed: false,
  /** 登录用的用户名，只用来在侧栏显示一句「谁在用」，JWT 里没有昵称 */
  account: localStorage.getItem(ACCOUNT_KEY) || '',

  sessions: [] as Conversation[],
  sessionId: null as number | null,
  messages: [] as UiMessage[],
  historyCursor: null as number | null,
  historyHasMore: false,

  streaming: false,
  /** 正在跑的工具，成对出现，start 时加、end 时移除 */
  liveTools: [] as ToolFrame[],
  pending: null as ApprovalPayload | null,
  tasks: [] as AgentTask[],

  plugins: [] as PluginInfo[],
  activePlugin: 'schedule',
  activeTab: 'day',
  panelParams: {} as { date?: string; id?: number },
  /** 递增即表示「面板该重新取数了」，面板 watch 它 */
  panelEpoch: 0,

  notices: [] as Notice[],
  /** 已弹过的通知 id。后端没有 notified_time，同一条未读会被反复取到，只能在界面侧记一份 */
  popped: [] as number[],

  toasts: [] as Toast[],
})

let controller: AbortController | null = null
let toastSeq = 0
let panelTimer: number | null = null

/* =========================== 提示 =========================== */

export function toast(text: string, kind: Toast['kind'] = 'info') {
  const item: Toast = { id: ++toastSeq, text, kind }
  state.toasts.push(item)
  window.setTimeout(() => {
    state.toasts = state.toasts.filter((t) => t.id !== item.id)
  }, kind === 'error' ? 6000 : 3200)
}

function messageOf(error: unknown): string {
  if (error instanceof Error && error.message) return error.message
  return '操作没有完成，请再试一次'
}

/* =========================== 认证 =========================== */

export async function login(payload: {
  username: string
  password: string
  tempKey: string
  captchaCode: string
}): Promise<void> {
  const result = await loginApi(payload)
  writeToken(result.jwtToken)
  localStorage.setItem(ACCOUNT_KEY, payload.username)
  state.account = payload.username
  state.authed = true
  await Promise.all([loadPlugins(), loadSessions()])
}

export function logout() {
  writeToken(null)
  stop()
  state.authed = false
  state.sessions = []
  state.sessionId = null
  state.messages = []
  state.pending = null
  state.liveTools = []
  state.notices = []
  state.plugins = []
}

/* =========================== 插件 =========================== */

export async function loadPlugins(): Promise<void> {
  try {
    state.plugins = await listPlugins()
  } catch (error) {
    toast(messageOf(error), 'error')
  }
}

export async function togglePlugin(pluginKey: string, enabled: boolean): Promise<void> {
  try {
    await setPluginEnabled(pluginKey, enabled)
    state.plugins = state.plugins.map((p) => (p.pluginKey === pluginKey ? { ...p, enabled } : p))
    toast(enabled ? '已启用' : '已停用')
  } catch (error) {
    toast(messageOf(error), 'error')
  }
}

export function openPlugin(key: string, tab?: string) {
  const manifest = resolvePlugin(key)
  if (!manifest) {
    toast('这个插件还没有做出来')
    return
  }
  state.activePlugin = key
  state.activeTab = tab ?? manifest.defaultTab
}

/* =========================== 会话 =========================== */

export async function loadSessions(): Promise<void> {
  try {
    const page = await sessionApi.listSessions({ size: 40 })
    state.sessions = page.records
  } catch (error) {
    toast(messageOf(error), 'error')
  }
}

export async function newSession(): Promise<void> {
  try {
    const created = await sessionApi.createSession()
    state.sessions = [created, ...state.sessions]
    state.sessionId = created.id
    state.messages = []
    state.pending = null
    state.historyCursor = null
    state.historyHasMore = false
  } catch (error) {
    toast(messageOf(error), 'error')
  }
}

export async function openSession(id: number): Promise<void> {
  if (state.streaming) stop()
  state.sessionId = id
  state.messages = []
  state.pending = null
  state.tasks = []
  state.panelParams = {}

  try {
    const page = await chatApi.listMessages(id, null, 30)
    state.messages = page.records
      // system 是给模型看的项目约定，不是对话内容
      .filter((m) => m.role !== 'system')
      .map((m) => ({
        key: `m-${m.id}`,
        id: m.id,
        role: m.role === 'user' ? 'user' : 'assistant',
        content: m.content,
        streaming: false,
      }))
    state.historyCursor = page.next_before_id
    state.historyHasMore = page.has_more

    // 断线重连：上次卡在确认上，这次进来要接着问，否则用户以为 agent 死了
    const pending = await chatApi.getPendingInterrupt(id)
    if (pending.has_pending && pending.interrupt) state.pending = pending.interrupt
  } catch (error) {
    toast(messageOf(error), 'error')
  }
}

export async function loadOlder(): Promise<void> {
  if (!state.sessionId || !state.historyHasMore || !state.historyCursor) return
  try {
    const page = await chatApi.listMessages(state.sessionId, state.historyCursor, 30)
    const older = page.records
      .filter((m) => m.role !== 'system')
      .map((m) => ({
        key: `m-${m.id}`,
        id: m.id,
        role: (m.role === 'user' ? 'user' : 'assistant') as UiMessage['role'],
        content: m.content,
        streaming: false,
      }))
    state.messages = [...older, ...state.messages]
    state.historyCursor = page.next_before_id
    state.historyHasMore = page.has_more
  } catch (error) {
    toast(messageOf(error), 'error')
  }
}

/* =========================== 对话 =========================== */

/** ui 帧 → 切面板并通知刷新。连续多个工具只触发一次刷新，否则面板会连闪 */
function applyUiFrame(frame: UiFrame) {
  const manifest = resolvePlugin(frame.plugin)
  if (!manifest) return

  state.activePlugin = manifest.key
  state.activeTab = resolveTab(manifest, frame.view)
  state.panelParams = { ...frame.params }

  if (panelTimer !== null) window.clearTimeout(panelTimer)
  panelTimer = window.setTimeout(() => {
    state.panelEpoch += 1
    panelTimer = null
  }, 220)
}

function handleEvent(evt: SseEvent, reply: UiMessage | null) {
  switch (evt.type) {
    case 'text':
      if (reply) reply.content += evt.data
      break

    case 'tool':
      if (evt.data.phase === 'start') {
        state.liveTools = [...state.liveTools, evt.data]
      } else {
        state.liveTools = state.liveTools.filter((t) => t.id !== evt.data.id)
      }
      break

    case 'ui':
      applyUiFrame(evt.data)
      break

    case 'interrupt':
      // 一次可能有多个工具要确认，服务端是逐个弹的，所以这里不合并、只覆盖当前这张
      state.pending = evt.data
      break

    case 'done':
      if (reply) reply.streaming = false
      break

    case 'error':
      if (reply) reply.streaming = false
      toast(evt.data || '对话执行失败', 'error')
      break
  }
}

function pushReply(): UiMessage {
  const reply: UiMessage = { key: nextKey(), id: null, role: 'assistant', content: '', streaming: true }
  state.messages.push(reply)
  return reply
}

export async function send(text: string): Promise<void> {
  const content = text.trim()
  if (!content || state.streaming) return

  if (!state.sessionId) await newSession()
  if (!state.sessionId) return

  state.messages.push({ key: nextKey(), id: null, role: 'user', content, streaming: false })
  const reply = pushReply()

  state.streaming = true
  state.liveTools = []
  controller = new AbortController()

  try {
    await chatApi.sendChat(
      { conversationId: state.sessionId, content },
      { signal: controller.signal, onEvent: (evt) => handleEvent(evt, reply) },
    )
  } catch (error) {
    if ((error as Error)?.name === 'AbortError') {
      reply.content += '\n\n（已停止）'
    } else {
      // 空回复就别留一个空气泡在界面上
      if (!reply.content) state.messages = state.messages.filter((m) => m.key !== reply.key)
      toast(messageOf(error), 'error')
    }
  } finally {
    reply.streaming = false
    state.streaming = false
    state.liveTools = []
    controller = null
    // 首轮之后后端可能把标题从「新对话」改成了摘要，重取一次
    loadSessions()
  }
}

/** 回应确认卡。之后可能再次收到 interrupt（多个工具逐个确认） */
export async function respond(choice: 'approve' | 'reject', editContent?: string): Promise<void> {
  if (!state.sessionId || !state.pending) return
  const reply = pushReply()
  state.pending = null
  state.streaming = true
  state.liveTools = []
  controller = new AbortController()

  try {
    await chatApi.resumeChat(
      { conversationId: state.sessionId, choice, editContent },
      { signal: controller.signal, onEvent: (evt) => handleEvent(evt, reply) },
    )
  } catch (error) {
    if (!reply.content) state.messages = state.messages.filter((m) => m.key !== reply.key)
    if ((error as Error)?.name !== 'AbortError') toast(messageOf(error), 'error')
  } finally {
    reply.streaming = false
    state.streaming = false
    state.liveTools = []
    controller = null
    loadSessions()
  }
}

export function stop() {
  controller?.abort()
  controller = null
  state.streaming = false
  state.liveTools = []
}

/* =========================== 通知 =========================== */

/**
 * 拉一次要弹的通知。只弹本地没弹过的。
 *
 * 这是「网页内到点提醒」的落地方式：轮询而不是推送，因为项目里没有
 * WebSocket / SSE 常驻通道可以复用。30 秒一次足够——提醒的精度是分钟级。
 */
export async function pollNotices(): Promise<void> {
  if (!state.authed) return
  try {
    const page = await popupNotices()
    const fresh = page.records.filter((n) => !state.popped.includes(n.id))
    if (fresh.length) {
      state.notices = [...fresh, ...state.notices]
      state.popped = [...state.popped, ...fresh.map((n) => n.id)]
    }
  } catch {
    // 轮询失败不打扰用户，下一轮会自己恢复
  }
}

export async function dismissNotice(id: number): Promise<void> {
  state.notices = state.notices.filter((n) => n.id !== id)
  try {
    await markRead(id)
  } catch {
    // 标已读失败也无所谓：本地已经不再展示它了
  }
}

/* =========================== 启动 =========================== */

export async function boot(): Promise<void> {
  if (readToken()) {
    state.authed = true
    await Promise.all([loadPlugins(), loadSessions()])
  }
  state.booted = true
}

window.addEventListener('lm:unauthorized', () => {
  logout()
  toast('登录已过期，请重新登录', 'error')
})
