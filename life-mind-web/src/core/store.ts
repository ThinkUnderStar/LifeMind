/**
 * 全局状态与动作。没有引入状态库：这个界面只有一份状态、一棵组件树，
 * 一个 reactive 对象加一组函数就够，多引一个库只是多一层要学的东西。
 *
 * 分工会被反复用到，先说清：
 * - 这里只管「对话与外壳」的状态：会话、消息、流、确认卡、右侧工作区的页面组、顶层独立页。
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
  WorkspacePage,
} from './types'
import { login as loginApi } from '../api/auth'
import { listPlugins, setPluginEnabled } from '../api/plugin'
import * as sessionApi from '../api/session'
import * as chatApi from '../api/chat'
import { popupNotices, markRead, pageNotices, unreadCount } from '../api/notice'
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
  /** 与后端 chat_message.task_bind_id 同值，历史消息据此挂上那一轮的任务清单 */
  taskBindId?: string | null
  /** 这一轮排了哪几步、各自走到哪；流式时由 tasks 帧整表覆盖，历史消息按 bind id 回填 */
  tasks?: AgentTask[]
}

/**
 * 一轮还在跑的对话。切会话不打断别处的流，所以消息数组、确认卡、工具提示
 * 都得跟着这一轮走，不能只挂在全局那一份 state 上——否则 A 会话的确认卡
 * 会跑到 B 会话，A 的流式文字也会被 B 的 openSession 冲掉。
 *
 * messages 就是 state.messages 背后的那个响应式数组：当前显示时两者是同一个对象，
 * 切走后 state.messages 指向别处，这一轮仍持有原数组继续写，切回来原样接上。
 */
interface SessionRun {
  sessionId: number
  messages: UiMessage[]
  replyKey: string | null
  /** 这一轮已收到的最后一帧下标（-1 表示还没收到）；刷新后据此从后端续传 */
  lastSeq: number
  pending: ApprovalPayload | null
  liveTools: ToolFrame[]
  streaming: boolean
  /** fetch 被中断（用户点停止或刷新页面）时置位：这轮的半截回复要留给快照 */
  aborted: boolean
  controller: AbortController | null
  historyCursor: number | null
  historyHasMore: boolean
}

/**
 * 刷新后用来续传的状态。图和帧都活在后端，浏览器这边只需要：
 * 当前消息数组（含还没落库的半截回复）+ 该从哪一帧继续读。
 */
interface StreamSnapshot {
  sessionId: number
  messages: UiMessage[]
  replyKey: string | null
  lastSeq: number
  pending: ApprovalPayload | null
}

export interface Toast {
  id: number
  text: string
  kind: 'info' | 'error'
}

const ACCOUNT_KEY = 'lm.account'
const PANEL_OPEN_KEY = 'lm.panel.open'
const WEB_SEARCH_KEY = 'lm.websearch'
const SESSION_KEY = 'lm.session'
const SESSION_VIEW_KEY = 'lm.session.view'
/**
 * 刷新会杀掉 SSE 连接，但图在后端后台照跑。浏览器只需记住「读到第几帧」，
 * 刷新后用这个游标重连，把断线期间的帧补回来接着输出。
 * 用 sessionStorage：只活在当前标签页，关掉标签就没了，不会把半截话带到下次打开。
 */
const SNAPSHOT_KEY = 'lm.stream.snapshot'

let seq = 0
const nextKey = () => `t-${++seq}`

/** 工作区页面用本地序号，同一个插件也能开好几张页 */
let pageSeq = 0
const nextPageKey = () => `page-${++pageSeq}`
const blankPage = (): WorkspacePage => ({ key: nextPageKey(), kind: 'plugin', pluginKey: null, label: '新页面' })
const firstPage = blankPage()

export const state = reactive({
  booted: false,
  authed: false,
  /** 登录用的用户名，只用来在侧栏显示一句「谁在用」，JWT 里没有昵称 */
  account: localStorage.getItem(ACCOUNT_KEY) || '',

  /**
   * 顶层界面。chat = 左栏 + 对话 + 右工作区；另外四个是左栏入口对应的独立整页。
   * 五者互斥：进独立页时对话侧整块收起，退回后原样恢复（会话、工作区页都还在）。
   * 规则：从左栏（功能区块与账号菜单）进去的，一律是独立页，不占工作区标签。
   * 不持久化：刷新回到对话，避免停在已经不是工作区一页的页面上。
   */
  mainView: 'chat' as 'chat' | 'plugins' | 'notice' | 'feedback' | 'profile',

  sessions: [] as Conversation[],
  /** 会话栏当前看哪一类：正常会话 / 已归档。后端按 archived 参数过滤，前端不自己筛 */
  sessionView: 'active' as 'active' | 'archived',
  /** 会话列表正在拉取。启动/登录后先显示骨架，避免空状态先闪一帧再被数据替换 */
  sessionsLoading: true,
  sessionId: null as number | null,
  messages: [] as UiMessage[],
  historyCursor: null as number | null,
  historyHasMore: false,
  /** 刷新后正在拉会话和历史消息。此时别把空的 messages 当成「欢迎页」渲染 */
  chatLoading: false,

  streaming: false,
  /**
   * 本轮是否允许联网搜索。默认关（后端也是显式 1 才联网），用户点一次记住选择。
   * 开关放运行时 config 传给 agent，模型改不了它，只能在开着时调搜索工具。
   */
  webSearch: localStorage.getItem(WEB_SEARCH_KEY) === '1',
  /** 正在跑的工具，成对出现，start 时加、end 时移除 */
  liveTools: [] as ToolFrame[],
  pending: null as ApprovalPayload | null,

  plugins: [] as PluginInfo[],

  /**
   * 右侧工作区：一组浏览器式的页面，默认收起但随时能主动打开。
   * 空白页列出可打开的插件，点一个就在当前页打开；也能新建页再开另一个。
   * 这里只放插件面板：左栏里的通知、意见反馈、个人主页、插件服务库都是独立界面，不占标签。
   */
  workspaceOpen: localStorage.getItem(PANEL_OPEN_KEY) === '1',
  workspacePages: [firstPage] as WorkspacePage[],
  workspacePage: firstPage.key,
  /** 当前显示的插件页参数。切页时由那张页还原，面板组件只读这三个字段 */
  activePlugin: '',
  activeTab: 'day',
  panelParams: {} as { date?: string; id?: number },
  /** 递增即表示「面板该重新取数了」，面板 watch 它 */
  panelEpoch: 0,

  notices: [] as Notice[],
  /** 通知箱列表（下拉里看的全部记录）。与 notices 分开：那个是弹窗队列，这个是历史 */
  noticeList: [] as Notice[],
  noticeUnread: 0,
  noticeListLoading: false,
  /** 已弹过的通知 id。后端没有 notified_time，同一条未读会被反复取到，只能在界面侧记一份 */
  popped: [] as number[],

  toasts: [] as Toast[],
})

let toastSeq = 0
let panelTimer: number | null = null

/** 所有还在跑（或刚跑完、尚未重新拉过历史）的轮次，按会话 id 索引 */
const runs = new Map<number, SessionRun>()
let snapshotTimer: number | null = null
/** 区分「用户点停止」和「刷新/关闭页面」：前者标「已停止」，后者交给快照复原 */
let unloading = false

function runOf(sid: number | null): SessionRun | null {
  return sid == null ? null : runs.get(sid) ?? null
}

/** 某会话开始新一轮时认领它当前显示的数组；没有就把空数组交给它 */
function ensureRun(sid: number): SessionRun {
  let run = runs.get(sid)
  if (!run) {
    const active = state.sessionId === sid
    run = {
      sessionId: sid,
      messages: active ? state.messages : [],
      replyKey: null,
      lastSeq: -1,
      pending: active ? state.pending : null,
      liveTools: [],
      streaming: false,
      aborted: false,
      controller: null,
      historyCursor: active ? state.historyCursor : null,
      historyHasMore: active ? state.historyHasMore : false,
    }
    runs.set(sid, run)
  }
  return run
}

/** 后台轮次的状态只在它正显示时同步到界面，避免串到别的会话 */
function syncActive(run: SessionRun | null) {
  if (!run || run.sessionId !== state.sessionId) return
  state.messages = run.messages
  state.pending = run.pending
  state.liveTools = run.liveTools
  state.streaming = run.streaming
  state.historyCursor = run.historyCursor
  state.historyHasMore = run.historyHasMore
}

function persistSelected(id: number | null) {
  if (id === null) localStorage.removeItem(SESSION_KEY)
  else localStorage.setItem(SESSION_KEY, String(id))
}

/* ---------------- 半截回复的刷新快照 ---------------- */

function readSnapshots(): Record<string, StreamSnapshot> {
  try {
    return JSON.parse(sessionStorage.getItem(SNAPSHOT_KEY) || '{}') as Record<string, StreamSnapshot>
  } catch {
    return {}
  }
}

function writeSnapshots(map: Record<string, StreamSnapshot>) {
  try {
    sessionStorage.setItem(SNAPSHOT_KEY, JSON.stringify(map))
  } catch {
    // 存不下就算了：快照是兜底，不该因为配额把对话打断
  }
}

/** 把一轮的消息与续传游标写进快照。done 之后就删，免得刷新时重连一个已结束的流 */
function saveSnapshot(run: SessionRun) {
  // aborted 也算：刷新时 fetch 先被中止把 streaming 置 false，
  // 但这时候半截文本正是最该留给快照的，不能因为流已停就跳过。
  if (!run.streaming && !run.pending && !run.aborted) return
  const map = readSnapshots()
  map[String(run.sessionId)] = {
    sessionId: run.sessionId,
    messages: JSON.parse(JSON.stringify(run.messages)) as UiMessage[],
    replyKey: run.replyKey,
    lastSeq: run.lastSeq,
    pending: run.pending,
  }
  writeSnapshots(map)
}

/** 文本帧来得很快，节流写，避免逐字序列化整个气泡 */
function scheduleSnapshot() {
  if (snapshotTimer !== null) return
  snapshotTimer = window.setTimeout(() => {
    snapshotTimer = null
    for (const run of runs.values()) saveSnapshot(run)
  }, 500)
}

function dropSnapshot(sid: number) {
  const map = readSnapshots()
  if (!(String(sid) in map)) return
  delete map[String(sid)]
  writeSnapshots(map)
}

/** 页面被关掉/刷新时最后存一次，接住节流窗口里还没落盘的那几百毫秒 */
window.addEventListener('beforeunload', () => {
  unloading = true
})
window.addEventListener('pagehide', () => {
  unloading = true
  if (snapshotTimer !== null) {
    window.clearTimeout(snapshotTimer)
    snapshotTimer = null
  }
  for (const run of runs.values()) saveSnapshot(run)
})

/* =========================== 联网开关 =========================== */

/** 切一次联网开关，顺手记住——下次打开还是上次的选择 */
export function toggleWebSearch() {
  state.webSearch = !state.webSearch
  localStorage.setItem(WEB_SEARCH_KEY, state.webSearch ? '1' : '0')
}

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
  state.chatLoading = true
  try {
    await Promise.all([loadPlugins(), loadSessions()])
  } finally {
    state.chatLoading = false
  }
}

/** 改完账户名后同步本地记录：栏底账号与下次启动读的都是这里 */
export function setAccount(username: string) {
  localStorage.setItem(ACCOUNT_KEY, username)
  state.account = username
}

/**
 * 已经拿到令牌时的统一收尾。
 *
 * 密码登录、手机验证码登录、邮箱验证码登录拿到的都是同一个 LoginResponse，
 * 但后两种没有「用户名」可填：后端只回令牌。显示名用手机号/邮箱充数，
 * 打开个人主页拿到昵称后自然会换成更好看的那个。
 */
export async function enterWithToken(jwtToken: string, label: string): Promise<void> {
  writeToken(jwtToken)
  localStorage.setItem(ACCOUNT_KEY, label)
  state.account = label
  state.authed = true
  state.chatLoading = true
  try {
    await Promise.all([loadPlugins(), loadSessions()])
  } finally {
    state.chatLoading = false
  }
}

export function logout() {
  writeToken(null)
  // 登出要把所有后台还在跑的流一起收掉，不能只停当前显示的那轮
  for (const run of runs.values()) run.controller?.abort()
  runs.clear()
  clearLateReplyTimers()
  persistSelected(null)
  if (snapshotTimer !== null) {
    window.clearTimeout(snapshotTimer)
    snapshotTimer = null
  }
  try {
    sessionStorage.removeItem(SNAPSHOT_KEY)
  } catch {
    /* 忽略 */
  }
  state.streaming = false
  state.authed = false
  state.sessions = []
  state.sessionId = null
  state.messages = []
  state.pending = null
  state.liveTools = []
  state.notices = []
  state.noticeList = []
  state.noticeUnread = 0
  state.plugins = []
  state.mainView = 'chat'
  const fresh = blankPage()
  state.workspacePages = [fresh]
  state.workspacePage = fresh.key
  state.workspaceOpen = false
  state.activePlugin = ''
  localStorage.setItem(PANEL_OPEN_KEY, '0')
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
    // 解绑后工作区不该再留这个插件的页：把已开的同名页关掉，免得停用的服务还摆在标签栏里
    if (!enabled) closePluginPages(pluginKey)
    toast(enabled ? '已绑定' : '已解绑')
  } catch (error) {
    toast(messageOf(error), 'error')
  }
}

/** 关掉工作区里某个插件的全部页；一张都不剩就补张空白页，不留空壳 */
function closePluginPages(pluginKey: string) {
  const kept = state.workspacePages.filter((p) => !(p.kind === 'plugin' && p.pluginKey === pluginKey))
  if (!kept.length) {
    const blank = blankPage()
    state.workspacePages = [blank]
    state.workspacePage = blank.key
  } else {
    state.workspacePages = kept
    if (!kept.some((p) => p.key === state.workspacePage)) state.workspacePage = kept[0].key
  }
  syncPage()
  state.panelEpoch += 1
}

/** 左栏四个功能入口：都切顶层独立页，不占工作区标签、不顶掉正在看的对话 */
export function openPluginLibrary() {
  state.mainView = 'plugins'
}

/** 通知进页时拉一次列表；弹窗队列与它无关，照常轮询 */
export function openNotice() {
  state.mainView = 'notice'
  void loadNoticeList()
}

export function openFeedback() {
  state.mainView = 'feedback'
}

/** 左栏底部账号菜单进个人主页 */
export function openProfile() {
  state.mainView = 'profile'
}

export function backToChat() {
  state.mainView = 'chat'
}

/* =========================== 工作区页面 =========================== */

function currentPage(): WorkspacePage | undefined {
  return state.workspacePages.find((p) => p.key === state.workspacePage)
}

function persistPanelOpen() {
  localStorage.setItem(PANEL_OPEN_KEY, state.workspaceOpen ? '1' : '0')
}

/** 面板组件只看这三个全局字段；切页时由那张页记录的内容还原 */
function syncPage() {
  const page = currentPage()
  const pluginKey = page?.kind === 'plugin' ? page.pluginKey : null
  const manifest = pluginKey ? resolvePlugin(pluginKey) : undefined
  state.activePlugin = pluginKey ?? ''
  state.activeTab = page?.tab ?? manifest?.defaultTab ?? 'day'
  state.panelParams = { ...(page?.params ?? {}) }
}

/** 面板内部可能改了分段或日期，切走前存回这一页 */
function savePage() {
  const page = currentPage()
  if (page?.kind !== 'plugin' || !page.pluginKey) return
  page.tab = state.activeTab
  page.params = { ...state.panelParams }
}

/** 顶栏的「工作区」按钮：随时开合，第一次打开时给一张空白页 */
export function toggleWorkspace() {
  if (state.workspaceOpen) {
    state.workspaceOpen = false
  } else {
    if (!state.workspacePages.length) {
      const blank = blankPage()
      state.workspacePages.push(blank)
      state.workspacePage = blank.key
    }
    state.workspaceOpen = true
    syncPage()
  }
  persistPanelOpen()
}

export function openWorkspace() {
  if (!state.workspaceOpen) toggleWorkspace()
}

/** 新建一张页；带插件 key 就是直接新开一个插件页，不带就是空白页 */
export function addWorkspacePage(pluginKey?: string, tab?: string) {
  const manifest = pluginKey ? resolvePlugin(pluginKey) : undefined
  if (pluginKey && !manifest) {
    toast('这个插件还没有做出来')
    return
  }
  savePage()
  const page: WorkspacePage = {
    key: nextPageKey(),
    kind: 'plugin',
    pluginKey: pluginKey ?? null,
    label: pluginKey ? manifest!.name : '新页面',
    tab: tab ?? manifest?.defaultTab,
    params: {},
  }
  state.workspacePages.push(page)
  state.workspacePage = page.key
  state.workspaceOpen = true
  persistPanelOpen()
  syncPage()
  state.panelEpoch += 1
}

/** 空白页上点插件：就地打开；当前已经是插件页就另开一页，不覆盖已看的内容 */
export function openPluginHere(pluginKey: string) {
  const manifest = resolvePlugin(pluginKey)
  if (!manifest) {
    toast('这个插件还没有做出来')
    return
  }
  const page = currentPage()
  if (page && page.kind === 'plugin' && !page.pluginKey) {
    page.pluginKey = pluginKey
    page.label = manifest.name
    page.tab = manifest.defaultTab
    page.params = {}
    state.workspaceOpen = true
    persistPanelOpen()
    syncPage()
    state.panelEpoch += 1
  } else {
    addWorkspacePage(pluginKey)
  }
}

export function selectWorkspacePage(key: string) {
  if (key === state.workspacePage || !state.workspacePages.some((p) => p.key === key)) return
  savePage()
  state.workspacePage = key
  syncPage()
  state.panelEpoch += 1
}

/** 工作区默认页 = 没绑定插件的空白页（显示插件列表那张） */
function isDefaultPage(page: WorkspacePage | undefined): boolean {
  return !!page && !page.pluginKey
}

/**
 * 关一张页：
 * - 只剩最后一张、且它不是默认页 → 回到默认页（补一张空白页），工作区保持打开；
 * - 只剩最后一张、且它就是默认页 → 关闭整个工作区；
 * - 还有别的页 → 正常移除，当前页则切到相邻那张。
 */
export function closeWorkspacePage(key: string) {
  const idx = state.workspacePages.findIndex((p) => p.key === key)
  if (idx < 0) return
  const closing = state.workspacePages[idx]
  const wasCurrent = state.workspacePage === key

  if (state.workspacePages.length === 1) {
    if (isDefaultPage(closing)) {
      // 默认页是最后一张：关掉即收起工作区，页面清空，下次打开会补一张新的默认页
      state.workspacePages = []
      state.workspacePage = ''
      state.workspaceOpen = false
      state.activePlugin = ''
      state.activeTab = 'day'
      state.panelParams = {}
      persistPanelOpen()
      return
    }
    // 最后一张是插件页：不关工作区，回到默认页
    const blank = blankPage()
    state.workspacePages = [blank]
    state.workspacePage = blank.key
    syncPage()
    state.panelEpoch += 1
    return
  }

  state.workspacePages.splice(idx, 1)
  if (wasCurrent) {
    state.workspacePage = state.workspacePages[Math.min(idx, state.workspacePages.length - 1)].key
    syncPage()
    state.panelEpoch += 1
  }
}

/**
 * agent 用到服务时：空白页就地打开，已有同插件页就复用定位，否则新开一页。
 * 连续用到同一个服务不会堆一叠重复标签，手动新建的页也不会被顶掉。
 */
export function openServicePage(key: string, tab?: string) {
  const manifest = resolvePlugin(key)
  if (!manifest) {
    toast('这个插件还没有做出来')
    return
  }
  // 解绑的服务不进工作区：agent 仍可能推 ui 帧过来，这里挡在入口，不然停用的插件又被打开
  const info = state.plugins.find((p) => p.pluginKey === key)
  if (info && !info.enabled) {
    toast(`「${manifest.name}」已解绑，可在插件服务库里重新绑定`)
    return
  }
  const page = currentPage()
  if (page && page.kind === 'plugin' && !page.pluginKey) {
    page.pluginKey = key
    page.label = manifest.name
    page.tab = tab ?? manifest.defaultTab
    page.params = {}
  } else if (page?.pluginKey === key) {
    if (tab) page.tab = tab
  } else {
    const existing = state.workspacePages.find((p) => p.pluginKey === key)
    if (existing) {
      savePage()
      state.workspacePage = existing.key
      if (tab) existing.tab = tab
    } else {
      addWorkspacePage(key, tab)
      return
    }
  }
  state.workspaceOpen = true
  persistPanelOpen()
  syncPage()
}

/* =========================== 会话 =========================== */

/**
 * 取会话列表。silent 用于归档/恢复/删除这类局部刷新：列表还在，只是少一条，
 * 不该把整栏换成骨架再换回来（那一下闪烁很扎眼）。启动、登录、切视图仍走骨架。
 */
export async function loadSessions(silent = false): Promise<void> {
  if (!silent) state.sessionsLoading = true
  try {
    const page = await sessionApi.listSessions({
      size: 40,
      archived: state.sessionView === 'archived',
    })
    state.sessions = page.records
  } catch (error) {
    toast(messageOf(error), 'error')
  } finally {
    if (!silent) state.sessionsLoading = false
  }
}

/** 切换「会话 / 已归档」。列表重取，但不碰当前打开的对话——归档会话仍能读能聊 */
export async function switchSessionView(view: 'active' | 'archived'): Promise<void> {
  if (state.sessionView === view) return
  state.sessionView = view
  localStorage.setItem(SESSION_VIEW_KEY, view)
  await loadSessions()
}

/**
 * 开一个新对话：只清空界面，不建库。
 *
 * 会话要等用户真的发出第一句话才创建（见 send），这样列表里不会出现空的
 * 「新对话」，也就没有「空会话被归档」这种怪事。反复点这里都只是清空，
 * 不会出现多条空会话。
 */
export async function newSession(): Promise<void> {
  // 左栏是全局导航：在插件库/通知页点「新对话」要回对话视图，否则界面没反应
  state.mainView = 'chat'

  // 归档视图里点新对话，先切回正常视图才能看到稍后创建的会话
  if (state.sessionView === 'archived') {
    state.sessionView = 'active'
    await loadSessions()
  }

  // 只清界面：别的会话可能还在流式跑，它们的 run 留在 Map 里继续写，切回去能接上
  state.sessionId = null
  state.messages = []
  state.pending = null
  state.liveTools = []
  state.streaming = false
  state.panelParams = {}
  state.historyCursor = null
  state.historyHasMore = false
  persistSelected(null)
}

/**
 * 清空一个会话的历史记录（不删会话本身）。
 * 清的是当前打开的会话时，同步把聊天区、确认卡、任务、游标都清掉——
 * 否则界面还留着已被后端软删的消息，用户一看以为没清成功。
 * 标题保留（后端不动 conversation 行）：这是「清空记录」，不是「删除会话」。
 */
export async function clearConversation(id: number): Promise<void> {
  const run = runs.get(id)
  if (run) {
    run.controller?.abort()
    runs.delete(id)
  }
  dropSnapshot(id)
  syncActive(runOf(state.sessionId))
  await chatApi.clearConversationMemory(id)
  if (state.sessionId === id) {
    state.messages = []
    state.pending = null
    state.liveTools = []
    state.streaming = false
    state.panelParams = {}
    state.historyCursor = null
    state.historyHasMore = false
    state.liveTools = []
  }
  // 清空不改会话行，列表条目还在；静默重取避免整栏骨架闪一下
  await loadSessions(true)
}

/**
 * 一轮对话的任务清单。按 task_bind_id 分组：任务属于哪条 AI 回复由后端共用的那个 UUID 决定，
 * 不靠时间或顺序猜。整份缓存一次，翻历史分页时直接查，不用每页再请求一遍任务接口。
 */
let tasksByBind = new Map<string, AgentTask[]>()
/** 同一会话可能被连续打开多次，只让最后一次的异步结果落到界面上 */
let openSeq = 0

function groupTasksByBind(tasks: AgentTask[]): Map<string, AgentTask[]> {
  const byBind = new Map<string, AgentTask[]>()
  for (const task of tasks) {
    if (!task.task_bind_id) continue
    const list = byBind.get(task.task_bind_id)
    if (list) list.push(task)
    else byBind.set(task.task_bind_id, [task])
  }
  return byBind
}

/* 刷新落在「后端还没落库」的窗口里时，openSession 只能拉到用户消息或一条半截回复。
 * 快照在上一轮续流结束时已经删掉，后面再刷新也不会主动回头看，于是看起来像 AI 没回。
 * 这里给这种会话挂几次延迟对账，后端一旦落库就把完整回复补回来。 */
const lateReplyTimers: number[] = []

function clearLateReplyTimers(): void {
  while (lateReplyTimers.length) window.clearTimeout(lateReplyTimers.pop()!)
}

/**
 * 把后端已落库的回复补到当前会话。返回 true 表示已经完整（或本来就完整）。
 *
 * 判断依据是「最后一条用户消息之后有没有带真实 id 的 assistant」：
 * 本地半截/空回复的 id 是 null（pushReply 建的），后端落库的才有 id。
 * 所以末尾是 user、是空气泡、还是半截文本，都能被识别成「还没落库」，不会再漏。
 *
 * 只动最后一条用户消息之后的那段，不整表覆盖——翻历史加载的更早消息要原样留着。
 */
async function reconcileReplyOnce(sid: number): Promise<boolean> {
  if (state.sessionId !== sid || state.streaming) return false
  // 看这一轮是否真的还在流式，而不是「Map 里有没有 run」——
  // send 结束后 run 会一直留在 Map 里（没删），用它判断会让对账永远跳过。
  const run = runOf(sid)
  if (run && run.streaming) return false

  let lastUserIdx = -1
  for (let i = state.messages.length - 1; i >= 0; i--) {
    if (state.messages[i].role === 'user') { lastUserIdx = i; break }
  }
  if (lastUserIdx < 0) return true // 还没有用户消息，无需对账

  const committed = state.messages.slice(lastUserIdx + 1)
    .some((m) => m.role === 'assistant' && m.id !== null)
  if (committed) return true

  try {
    const [page, allTasks] = await Promise.all([
      chatApi.listMessages(sid, null, 30),
      chatApi.listTasks(sid).catch(() => [] as AgentTask[]),
    ])
    // await 期间用户可能切了会话或这一轮又开跑，迟到的结果不能盖上去
    const currentRun = runOf(sid)
    if (state.sessionId !== sid || state.streaming || (currentRun && currentRun.streaming)) return false
    const records = page.records.filter((m) => m.role !== 'system')
    const backendLast = [...records].reverse().find((m) => m.role === 'assistant')
    if (!backendLast) return false // 后端也还没落库，等下一次定时器

    tasksByBind = groupTasksByBind(allTasks)
    const reply: UiMessage = {
      key: `m-${backendLast.id}`,
      id: backendLast.id,
      role: 'assistant',
      content: backendLast.content,
      streaming: false,
      taskBindId: backendLast.task_bind_id,
      tasks: backendLast.task_bind_id ? tasksByBind.get(backendLast.task_bind_id) : undefined,
    }
    // 末尾那条未落库的 assistant（空气泡或半截）直接换成后端完整回复，否则追加
    const tail = state.messages[state.messages.length - 1]
    state.messages = tail && tail.role === 'assistant' && tail.id === null
      ? [...state.messages.slice(0, -1), reply]
      : [...state.messages, reply]
    state.historyCursor = page.next_before_id
    state.historyHasMore = page.has_more
    // 残留的 run 也认回补回后的数组与游标，否则切走再切回来会被它的旧状态盖掉
    if (currentRun) {
      currentRun.messages = state.messages
      currentRun.historyCursor = state.historyCursor
      currentRun.historyHasMore = state.historyHasMore
    }
    return true
  } catch {
    return false // 对账失败不影响当前界面，下一次定时器再试
  }
}

function scheduleLateReplyCheck(sid: number): void {
  // 纯对话也要过 gate + 记忆检索 + responder，慢的时候二三十秒才落库，兜到 80s。
  // 每次定时器都重新判断「是否已有落库回复」，补上后其余定时器自然空转。
  for (const delay of [1500, 4000, 10000, 20000, 35000, 55000, 80000]) {
    lateReplyTimers.push(window.setTimeout(() => {
      void reconcileReplyOnce(sid)
    }, delay))
  }
}

export async function openSession(id: number, silent = false): Promise<boolean> {
  // 左栏是全局导航：在插件库/通知页点某条会话要回对话视图，否则主区域停在独立页
  state.mainView = 'chat'
  const seq = ++openSeq
  clearLateReplyTimers()

  // 切走不 stop()：A 会话还在流就让它继续跑，别把半截回复掐掉。
  // 只把界面上这几个可能与上次同步后又被改过的字段写回它自己的 run。
  const previous = runOf(state.sessionId)
  if (previous) {
    previous.pending = state.pending
    previous.liveTools = state.liveTools
    previous.streaming = state.streaming
  }

  state.sessionId = id
  state.panelParams = {}
  persistSelected(id)

  // 回到还有自己在跑的那一轮：直接用它攒下的数组，不重新拉历史（会把没落库的半截冲掉）
  const run = runs.get(id)
  if (run) {
    syncActive(run)
    return true
  }

  state.messages = []
  state.pending = null
  state.liveTools = []
  state.streaming = false
  state.historyCursor = null
  state.historyHasMore = false

  try {
    const [page, allTasks] = await Promise.all([
      chatApi.listMessages(id, null, 30),
      // 任务清单是辅助信息：取不到就当没有，不能因为它让整个会话打不开
      chatApi.listTasks(id).catch(() => [] as AgentTask[]),
    ])
    // 用户可能在等接口时又切了一轮，迟到的结果不能盖到别的会话上
    if (state.sessionId !== id || seq !== openSeq) return false
    tasksByBind = groupTasksByBind(allTasks)
    state.messages = page.records
      // system 是给模型看的项目约定，不是对话内容
      .filter((m) => m.role !== 'system')
      .map((m) => ({
        key: `m-${m.id}`,
        id: m.id,
        role: m.role === 'user' ? 'user' : 'assistant',
        content: m.content,
        streaming: false,
        taskBindId: m.task_bind_id,
        tasks: m.task_bind_id ? tasksByBind.get(m.task_bind_id) : undefined,
      }))
    state.historyCursor = page.next_before_id
    state.historyHasMore = page.has_more

    // 断线重连：上次卡在确认上，这次进来要接着问，否则用户以为 agent 死了
    const pending = await chatApi.getPendingInterrupt(id)
    if (state.sessionId !== id || seq !== openSeq) return false
    if (pending.has_pending && pending.interrupt) state.pending = pending.interrupt

    // 没有本地流就挂延迟对账：后端可能还在生成（末尾是 user），
    // 或刚落库但拉历史时还没写完（末尾是半截/空 assistant）。
    // reconcileReplyOnce 自己判断要不要补，所以这里无条件挂、可重复挂。
    if (!runs.has(id)) scheduleLateReplyCheck(id)
    return true
  } catch (error) {
    // 拉历史失败可能只是后端这一刻不可用。挂上持续对账，后端恢复后自动补回，
    // 别让用户卡在一个空会话里。
    if (!runs.has(id)) scheduleLateReplyCheck(id)
    if (!silent) toast(messageOf(error), 'error')
    return false
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
        taskBindId: m.task_bind_id,
        // 任务清单只随会话整体取一次（含更早的轮次），翻历史时直接查这份分组
        tasks: m.task_bind_id ? tasksByBind.get(m.task_bind_id) : undefined,
      }))
    state.messages = [...older, ...state.messages]
    state.historyCursor = page.next_before_id
    state.historyHasMore = page.has_more
    // 重新赋值过数组，后台那一轮要认回新的这个，翻页游标也跟着存一份
    const run = runOf(state.sessionId)
    if (run) {
      run.messages = state.messages
      run.historyCursor = state.historyCursor
      run.historyHasMore = state.historyHasMore
    }
  } catch (error) {
    toast(messageOf(error), 'error')
  }
}

/* =========================== 对话 =========================== */

/** ui 帧 → 自动开对应服务页并切过去，通知面板刷新。多处工具连续触发只刷一次 */
function applyUiFrame(frame: UiFrame) {
  const manifest = resolvePlugin(frame.plugin)
  if (!manifest) return

  openServicePage(manifest.key, resolveTab(manifest, frame.view))
  // 定位参数记在当前工作区页上，切走再回来不会串到别的页
  const page = currentPage()
  if (page) page.params = { ...frame.params }
  syncPage()

  if (panelTimer !== null) window.clearTimeout(panelTimer)
  panelTimer = window.setTimeout(() => {
    state.panelEpoch += 1
    panelTimer = null
  }, 220)
}

/**
 * 按 key 从 state.messages 里取回响应式 Proxy。
 *
 * 关键：state 是 reactive，push 进去的普通对象在数组里会被包成 Proxy，
 * 而 push 前持有的原始引用改属性不会触发视图更新——流式逐字就会"攒着不显示"，
 * 直到别的状态变化牵连重渲染才一次性刷出来。所有流式写入都必须走数组里的 Proxy。
 */
function findReply(messages: UiMessage[], key: string | null): UiMessage | null {
  if (key === null) return null
  return messages.find((m) => m.key === key) ?? null
}

/**
 * 事件落到发起它的那一轮，不是当前显示的那一轮。
 * 这样切走以后 A 的流继续写 A 的数组，B 的确认卡也不会被 A 的 interrupt 顶掉。
 */
function handleEvent(evt: SseEvent, replyKey: string | null, sid: number) {
  const run = runOf(sid)
  if (!run) return
  // 重连游标：后端帧列表从 0 编号，我们收到一帧就 +1（初始 -1）。
  // expired 是重连失败的特殊帧，不在后端帧序列里，不能计数。
  if (evt.type !== 'expired') run.lastSeq += 1
  // 每帧重新查找：既拿到响应式 Proxy，也兼容 rewrite 延迟赋值数组的场景
  const reply = findReply(run.messages, replyKey)
  switch (evt.type) {
    case 'text':
      if (reply) reply.content += evt.data
      scheduleSnapshot()
      break

    case 'tasks':
      // 整表覆盖：后端每次给的是完整清单（pending/running/done 都在），不做增量合并
      if (reply) reply.tasks = evt.data
      break

    case 'tool':
      if (evt.data.phase === 'start') {
        run.liveTools = [...run.liveTools, evt.data]
      } else {
        run.liveTools = run.liveTools.filter((t) => t.id !== evt.data.id)
      }
      break

    case 'ui':
      // 后台会话的工具面板不能把当前界面的工作区页顶掉
      if (run.sessionId === state.sessionId) applyUiFrame(evt.data)
      break

    case 'interrupt':
      // 一次可能有多个工具要确认，服务端是逐个弹的，所以这里不合并、只覆盖当前这张
      run.pending = evt.data
      saveSnapshot(run)
      break

    case 'done':
      if (reply) reply.streaming = false
      break

    case 'error':
      if (reply) reply.streaming = false
      toast(evt.data || '对话执行失败', 'error')
      break

    case 'expired':
      // 后端缓存已清（重启或轮次过期）：图可能已落库也可能没有。
      // 重新拉一次历史；历史里没有这条回复时，才把本地半截标成中断。
      if (reply) reply.streaming = false
      void recoverAfterExpiry(sid, replyKey)
      break
  }
  syncActive(run)
}

/**
 * 重连时发现后端帧缓冲已过期。后台图可能仍在跑（稍后落库），也可能进程重启被终止。
 * 稍等一下再拉历史：有完整回复就用它替换半截；仍然没有才标中断。
 */
async function recoverAfterExpiry(sid: number, replyKey: string | null): Promise<void> {
  await new Promise((r) => window.setTimeout(r, 1200))

  // 先从帧缓冲之外的一条路找完整回复：直接读后端历史。
  // 走 run 而不是 reconcileReplyOnce，是因为此刻续流 run 还在 runs 里，
  // reconcileReplyOnce 会因为「有活跃流」而跳过。
  const run = runOf(sid)
  if (run && state.sessionId === sid) {
    try {
      const page = await chatApi.listMessages(sid, null, 30)
      const last = page.records.filter((m) => m.role === 'assistant').pop()
      const reply = findReply(run.messages, replyKey)
      if (last && reply) {
        const index = run.messages.findIndex((m) => m.key === replyKey)
        if (index >= 0) {
          run.messages[index] = {
            ...reply,
            id: last.id,
            content: last.content,
            streaming: false,
            taskBindId: last.task_bind_id,
          }
        }
        syncActive(run)
        return
      }
    } catch {
      /* 拉不到就走下面的持续对账 */
    }
  }

  // 后端这一刻还没落库很正常（纯对话二三十秒才写完）。
  // 不再打「输出已中断」——那会把本来能补回来的回复判死刑；
  // 改成挂持续对账，图一旦落库就自动补上。
  scheduleLateReplyCheck(sid)
}

/** 追加一条空的 AI 回复占位，返回它的 key（用 key 而不是对象，避免持有非响应式原始引用） */
function pushReply(run: SessionRun): string {
  const key = nextKey()
  run.messages.push({ key, id: null, role: 'assistant', content: '', streaming: true })
  return key
}

export async function send(text: string): Promise<void> {
  const content = text.trim()
  if (!content || state.streaming) return

  // 还没建库的「新对话」：第一句话要发出去了才真正创建会话。
  // 创建失败就不发——否则消息挂在前端、后端没有对应会话，刷新即丢。
  if (!state.sessionId) {
    try {
      const created = await sessionApi.createSession()
      state.sessionId = created.id
      state.sessions = [created, ...state.sessions.filter((s) => s.id !== created.id)]
    } catch (error) {
      toast(messageOf(error), 'error')
      return
    }
  }

  // 整轮绑定这个会话：请求发出后再切走，事件仍写回这里，不会串到新会话
  const sid = state.sessionId
  const run = ensureRun(sid)
  persistSelected(sid)
  run.messages.push({ key: nextKey(), id: null, role: 'user', content, streaming: false })
  const replyKey = pushReply(run)
  run.replyKey = replyKey
  run.lastSeq = -1
  run.pending = null
  run.liveTools = []
  run.streaming = true
  run.aborted = false
  const controller = new AbortController()
  run.controller = controller
  // 立刻写一份空快照：可能在第一个字吐出来之前就刷新，那时也要知道这里中断过
  dropSnapshot(sid)
  saveSnapshot(run)
  syncActive(run)

  let aborted = false
  try {
    await chatApi.sendChat(
      { conversationId: sid, content, webSearch: state.webSearch },
      { signal: controller.signal, onEvent: (evt) => handleEvent(evt, replyKey, sid) },
    )
  } catch (error) {
    const reply = findReply(run.messages, replyKey)
    // 刷新时浏览器可能抛 AbortError，也可能抛普通 "Failed to fetch"；
    // 两种都是连接被切断，快照都得留着，否则刷新后这轮就彻底没了。
    if ((error as Error)?.name === 'AbortError' || unloading) {
      aborted = true
      run.aborted = true
      // 刷新页面不是用户主动停止，别往快照里塞「已停止」；复原时会标「输出已中断」
      if (!unloading && reply) reply.content += '\n\n（已停止）'
      saveSnapshot(run)
    } else {
      // 空回复就别留一个空气泡在界面上
      if (reply && !reply.content) run.messages = run.messages.filter((m) => m.key !== replyKey)
      toast(messageOf(error), 'error')
    }
  } finally {
    const reply = findReply(run.messages, replyKey)
    if (reply) reply.streaming = false
    // 中断的这轮要留住 replyKey，pagehide 时才能把半截文本写进快照
    if (!aborted) run.replyKey = null
    run.streaming = false
    run.controller = null
    if (!aborted) dropSnapshot(sid)
    syncActive(run)
    // 首轮之后后端可能把标题从「新对话」改成了摘要，静默重取，别把会话栏闪成骨架
    loadSessions(true)
  }
}

/** 回应确认卡。之后可能再次收到 interrupt（多个工具逐个确认） */
export async function respond(choice: 'approve' | 'reject', editContent?: string): Promise<void> {
  const sid = state.sessionId
  if (sid === null || !state.pending) return
  // 只回应当前这张卡所属的会话；后台那轮的卡不会跑到这里来
  const run = ensureRun(sid)
  const replyKey = pushReply(run)
  run.replyKey = replyKey
  run.lastSeq = -1
  run.pending = null
  run.liveTools = []
  run.streaming = true
  run.aborted = false
  const controller = new AbortController()
  run.controller = controller
  // 立刻写一份空快照：可能在第一个字吐出来之前就刷新，那时也要知道这里中断过
  dropSnapshot(sid)
  saveSnapshot(run)
  syncActive(run)

  let aborted = false
  try {
    await chatApi.resumeChat(
      { conversationId: sid, choice, editContent, webSearch: state.webSearch },
      { signal: controller.signal, onEvent: (evt) => handleEvent(evt, replyKey, sid) },
    )
  } catch (error) {
    const reply = findReply(run.messages, replyKey)
    if (reply && !reply.content) run.messages = run.messages.filter((m) => m.key !== replyKey)
    if ((error as Error)?.name === 'AbortError' || unloading) {
      aborted = true
      run.aborted = true
      saveSnapshot(run)
    } else {
      toast(messageOf(error), 'error')
    }
  } finally {
    const reply = findReply(run.messages, replyKey)
    if (reply) reply.streaming = false
    if (!aborted) run.replyKey = null
    run.streaming = false
    run.controller = null
    if (!aborted) dropSnapshot(sid)
    syncActive(run)
    loadSessions(true)
  }
}

/** 只停当前显示的这一轮；别的会话的流继续跑 */
export function stop() {
  const run = runOf(state.sessionId)
  if (!run) {
    state.streaming = false
    state.liveTools = []
    return
  }
  run.controller?.abort()
  run.controller = null
  run.streaming = false
  const reply = findReply(run.messages, run.replyKey)
  if (reply) reply.streaming = false
  run.replyKey = null
  dropSnapshot(run.sessionId)
  syncActive(run)
}

/**
 * 改写一条历史用户消息，并从那里重新生成（时间回溯）。
 *
 * 这是不可逆的：后端会清掉该消息及其之后的消息、任务与向量切片。
 * 所以本地也只在**流真的开始之后**才截断界面——如果请求在进图之前就被拒
 * （会话不属于我、消息不是用户消息），界面还完整，用户可以接着用。
 *
 * 只有历史消息能改：state.messages 里 id 为 null 的是本次刚发的临时消息，
 * 后端还没有它的记录，拿不到 message_id 就无法回溯。
 */
export async function rewrite(messageId: number, content: string): Promise<void> {
  const text = content.trim()
  const sid = state.sessionId
  if (!text || state.streaming || sid === null) return

  const run = ensureRun(sid)
  const index = run.messages.findIndex((m) => m.id === messageId)
  if (index < 0) {
    toast('这条消息已不在当前列表里，刷新后再试', 'error')
    return
  }

  const user: UiMessage = { key: nextKey(), id: null, role: 'user', content: text, streaming: false }
  const replyKey = nextKey()
  const reply: UiMessage = { key: replyKey, id: null, role: 'assistant', content: '', streaming: true }

  let begun = false
  const begin = () => {
    if (begun) return
    begun = true
    run.messages = [...run.messages.slice(0, index), user, reply]
    syncActive(run)
  }

  run.replyKey = replyKey
  run.lastSeq = -1
  run.streaming = true
  run.aborted = false
  run.liveTools = []
  run.pending = null
  const controller = new AbortController()
  run.controller = controller
  // 立刻写一份空快照：可能在第一个字吐出来之前就刷新，那时也要知道这里中断过
  dropSnapshot(sid)
  saveSnapshot(run)
  syncActive(run)

  let aborted = false
  try {
    await chatApi.backtrackChat(
      { conversationId: sid, messageId, content: text },
      {
        signal: controller.signal,
        onEvent: (evt) => {
          begin()
          handleEvent(evt, replyKey, sid)
        },
      },
    )
  } catch (error) {
    const live = findReply(run.messages, replyKey)
    if ((error as Error)?.name === 'AbortError' || unloading) {
      aborted = true
      run.aborted = true
      if (!unloading && live) live.content += '\n\n（已停止）'
      saveSnapshot(run)
    } else {
      toast(messageOf(error), 'error')
    }
  } finally {
    // 没 begun 说明请求在进图前就被拒了：界面保持原样，不截断、不留空气泡
    const live = findReply(run.messages, replyKey)
    if (live) live.streaming = false
    if (!aborted) run.replyKey = null
    run.streaming = false
    run.controller = null
    if (!aborted) dropSnapshot(sid)
    syncActive(run)
    loadSessions(true)
  }
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
  // 角标用全量未读数，不能拿弹窗数凑：notPopup 的通知也算未读
  void refreshUnreadCount()
}

/** 未读角标。失败静默，下一轮轮询会重试 */
export async function refreshUnreadCount(): Promise<void> {
  if (!state.authed) return
  try {
    state.noticeUnread = await unreadCount()
  } catch {
    // 角标不是关键信息，拉取失败不打扰
  }
}

/** 打开铃铛下拉时拉一次通知箱列表（最近 30 条，含已读） */
export async function loadNoticeList(): Promise<void> {
  if (!state.authed) return
  state.noticeListLoading = true
  try {
    const page = await pageNotices({ pageNum: 1, pageSize: 30 })
    state.noticeList = page.records
  } catch {
    // 列表拉失败就留空态，不弹 toast 打断正在做的事
  } finally {
    state.noticeListLoading = false
  }
}

/** 通知箱里点一条：标已读，同时更新角标和弹窗队列 */
export async function readNotice(id: number): Promise<void> {
  state.noticeList = state.noticeList.map((n) => (n.id === id ? { ...n, isRead: 1 } : n))
  state.notices = state.notices.filter((n) => n.id !== id)
  try {
    await markRead(id)
    await refreshUnreadCount()
  } catch {
    // 本地已按已读显示；失败下次刷新会被纠正
  }
}

/** 全部已读：后端没有批量接口，逐条标（并发发，不等一条一往返） */
export async function readAllNotices(): Promise<void> {
  const unread = state.noticeList.filter((n) => n.isRead === 0).map((n) => n.id)
  if (!unread.length) return
  state.noticeList = state.noticeList.map((n) => ({ ...n, isRead: 1 }))
  state.notices = []
  try {
    await Promise.all(unread.map((id) => markRead(id)))
    await refreshUnreadCount()
  } catch {
    // 同上，本地先按已读显示
  }
}

export async function dismissNotice(id: number): Promise<void> {
  state.notices = state.notices.filter((n) => n.id !== id)
  try {
    await markRead(id)
    void refreshUnreadCount()
  } catch {
    // 标已读失败也无所谓：本地已经不再展示它了
  }
}

/* =========================== 启动 =========================== */

/**
 * 历史里已经有「快照这一轮」的完整回复时，直接用历史，不再用快照覆盖。
 *
 * 旧逻辑只在本地已有半截文字且和历史前缀一致时才跳过。若刷新发生在第一个字
 * 之前，本地快照里是一条空回复，即使后端已经落库，也会拿空快照把历史盖掉，
 * 用户就看到「只剩一句用户消息」。匹配时只认历史里最后一条用户消息，避免
 * 用户重复发同一句话时误命中上一轮的回复。
 */
function historyAlreadyHasReply(snap: StreamSnapshot): boolean {
  const snapshotUser = [...snap.messages].reverse().find((m) => m.role === 'user')
  if (!snapshotUser) return false
  const history = state.messages
  for (let i = history.length - 1; i >= 0; i--) {
    if (history[i].role !== 'user') continue
    if (history[i].content !== snapshotUser.content) return false
    return history.slice(i + 1).some((m) => m.role === 'assistant')
  }
  return false
}

/**
 * 续流结束后再向后端对一次账：把本地这条回复换成已落库的完整内容。
 *
 * 正常补帧时两者一致，这里只是兜底——万一重连期间帧缓冲被别的轮次替换、
 * 或某帧没落到响应式数组上，也不会给用户留下半截话。
 */
async function adoptHistoryReply(sid: number, run: SessionRun, replyKey: string | null): Promise<void> {
  if (!replyKey || runs.get(sid) !== run) return
  try {
    const page = await chatApi.listMessages(sid, null, 30)
    const last = page.records.filter((m) => m.role === 'assistant').pop()
    const reply = findReply(run.messages, replyKey)
    if (!last || !reply || runs.get(sid) !== run) return
    if (reply.content !== last.content || reply.id === null) {
      reply.content = last.content
      reply.id = last.id
      reply.streaming = false
      reply.taskBindId = last.task_bind_id
      syncActive(run)
    }
  } catch {
    /* 历史暂时拉不到就维持续流结果，不打扰用户 */
  }
}

/**
 * 刷新后用快照里的游标重连后端，把断线期间攒下的帧补回来继续输出。
 *
 * 图在后端后台独立跑，刷新只是断了 SSE，不终止生成。所以这里不再补「输出已中断」
 * 的静态消息，而是恢复消息数组 + 游标，让后半截字接着长出来。
 * 只有后端缓存已过期（进程重启等）才会退回静态中断提示。
 */
async function restoreSnapshot(sid: number): Promise<void> {
  const snap = readSnapshots()[String(sid)]
  // 刷新后所有流都没了，其余会话的快照也一起作废
  try {
    sessionStorage.removeItem(SNAPSHOT_KEY)
  } catch {
    /* 忽略 */
  }
  if (!snap || state.sessionId !== sid) return

  const partial = snap.replyKey
    ? snap.messages.find((m) => m.key === snap.replyKey)
    : null
  // 后端已经落库了完整回复：openSession 拉到的历史就是终态，不用再重连，
  // 否则半截 + 补帧会和历史里那条完整回复并排出现两次。
  // 快照为空时也要走这里——这正是「刷新太早」导致只剩用户消息的那个坑。
  if (historyAlreadyHasReply(snap)) return
  const historyLast = state.messages.filter((m) => m.role === 'assistant').pop()
  if (historyLast && historyLast.content.includes('输出已中断')) return

  // 恢复这一轮的状态：消息数组（含还没落库的半截）+ 帧游标。
  // 快照反序列化出的是普通数组，必须先放进 state.messages 拿到 Vue 的 Proxy，
  // 再让 run.messages 指向它——直接持有原始数组，逐字写入不会触发视图更新。
  state.messages = snap.messages
  const run: SessionRun = {
    sessionId: sid,
    messages: state.messages,
    replyKey: snap.replyKey,
    lastSeq: snap.lastSeq,
    pending: snap.pending,
    liveTools: [],
    streaming: true,
    aborted: false,
    controller: null,
    historyCursor: state.historyCursor,
    historyHasMore: state.historyHasMore,
  }
  runs.set(sid, run)
  if (snap.pending) state.pending = snap.pending
  state.streaming = true
  // 把半截那条设回流式：TaskList 展开、输入框变停止，重连完成后再收起
  const livePartial = findReply(run.messages, snap.replyKey)
  if (livePartial) livePartial.streaming = true
  syncActive(run)

  const controller = new AbortController()
  run.controller = controller
  try {
    await chatApi.attachChat(sid, snap.lastSeq, {
      signal: controller.signal,
      onEvent: (evt) => handleEvent(evt, snap.replyKey, sid),
    })
  } catch (error) {
    // 重连本身失败（网络问题、用户切走导致 abort）：保留半截，交给持续对账补回。
    // 不打「输出已中断」——后端后台任务多半还在跑，稍后就会落库。
    scheduleLateReplyCheck(sid)
    if ((error as Error)?.name !== 'AbortError') toast(messageOf(error), 'error')
  } finally {
    // 重连可能没补齐（帧缓冲被顶掉、某帧漏渲染等）：结束时以后端历史为准校正这条回复
    await adoptHistoryReply(sid, run, snap.replyKey)
    const reply = findReply(run.messages, snap.replyKey)
    if (reply) reply.streaming = false
    run.replyKey = null
    run.streaming = false
    run.controller = null
    runs.delete(sid)
    // 续流期间 handleEvent 的节流又把快照写回来了，这里彻底删掉，
    // 否则下次刷新会拿一个已完成轮次的旧游标去重连
    dropSnapshot(sid)
    syncActive(run)
    // adoptHistoryReply 只拉了一次；若那一刻后端还没落库，这里再挂持续对账兜底。
    // 此刻 run 已从 Map 删除，reconcileReplyOnce 能正常执行。
    scheduleLateReplyCheck(sid)
    // 首轮之后后端可能改了标题，静默重取
    loadSessions(true)
  }
}

export async function boot(): Promise<void> {
  // 数据没回来之前 messages 必然是空的，别让 ChatColumn 把它当成欢迎页闪出来
  state.chatLoading = true
  try {
    if (readToken()) {
      state.authed = true
      const view = localStorage.getItem(SESSION_VIEW_KEY)
      if (view === 'active' || view === 'archived') state.sessionView = view
      await Promise.all([loadPlugins(), loadSessions()])

      // 刷新后回到上次选中的会话。不要依赖会话列表里能不能找到它：列表只取第一页，
      // 会话被归档/排序靠后时会找不到，但按 id 仍可能正常打开，直接拿它试。
      const stored = Number(localStorage.getItem(SESSION_KEY) || '')
      if (stored) {
        const opened = await openSession(stored, true)
        if (opened) {
          // 快照续流要一直跑到图结束（可能几十秒），不能 await——
          // 否则 boot 的 finally 迟迟不执行，loading 转圈会盖住整段流式输出。
          // openSession 已把历史拉回来了，先让首屏显示，续流在后台补帧即可。
          void restoreSnapshot(stored)
        } else {
          // 打开失败（后端暂时不可用、会话在归档视图等）不要清选中态：
          // 一清掉下次刷新就回「新对话」，用户会以为消息丢了。保留 id，
          // 下次刷新还能重试；这一轮先把消息区留空。
          state.sessionId = stored
          persistSelected(stored)
        }
      }
    }
  } finally {
    state.chatLoading = false
    state.booted = true
  }
}

window.addEventListener('lm:unauthorized', () => {
  logout()
  toast('登录已过期，请重新登录', 'error')
})
