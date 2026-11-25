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
}

export interface Toast {
  id: number
  text: string
  kind: 'info' | 'error'
}

const ACCOUNT_KEY = 'lm.account'
const PANEL_OPEN_KEY = 'lm.panel.open'

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

  streaming: false,
  /** 正在跑的工具，成对出现，start 时加、end 时移除 */
  liveTools: [] as ToolFrame[],
  pending: null as ApprovalPayload | null,
  tasks: [] as AgentTask[],

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

export function closeWorkspacePage(key: string) {
  if (state.workspacePages.length <= 1) {
    // 最后一张关掉就补张空白页，工作区不会剩个空壳
    const blank = blankPage()
    state.workspacePages = [blank]
    state.workspacePage = blank.key
    syncPage()
    return
  }
  const idx = state.workspacePages.findIndex((p) => p.key === key)
  if (idx < 0) return
  const wasCurrent = state.workspacePage === key
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

  state.sessionId = null
  state.messages = []
  state.pending = null
  state.tasks = []
  state.panelParams = {}
  state.historyCursor = null
  state.historyHasMore = false
}

/**
 * 清空一个会话的历史记录（不删会话本身）。
 * 清的是当前打开的会话时，同步把聊天区、确认卡、任务、游标都清掉——
 * 否则界面还留着已被后端软删的消息，用户一看以为没清成功。
 * 标题保留（后端不动 conversation 行）：这是「清空记录」，不是「删除会话」。
 */
export async function clearConversation(id: number): Promise<void> {
  if (state.sessionId === id && state.streaming) stop()
  await chatApi.clearConversationMemory(id)
  if (state.sessionId === id) {
    state.messages = []
    state.pending = null
    state.tasks = []
    state.panelParams = {}
    state.historyCursor = null
    state.historyHasMore = false
    state.liveTools = []
  }
  // 清空不改会话行，列表条目还在；静默重取避免整栏骨架闪一下
  await loadSessions(true)
}

export async function openSession(id: number): Promise<void> {
  // 左栏是全局导航：在插件库/通知页点某条会话要回对话视图，否则主区域停在独立页
  state.mainView = 'chat'
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
    // 首轮之后后端可能把标题从「新对话」改成了摘要，静默重取，别把会话栏闪成骨架
    loadSessions(true)
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
    loadSessions(true)
  }
}

export function stop() {
  controller?.abort()
  controller = null
  state.streaming = false
  state.liveTools = []
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
  if (!text || state.streaming || !state.sessionId) return

  const index = state.messages.findIndex((m) => m.id === messageId)
  if (index < 0) {
    toast('这条消息已不在当前列表里，刷新后再试', 'error')
    return
  }

  const user: UiMessage = { key: nextKey(), id: null, role: 'user', content: text, streaming: false }
  const reply: UiMessage = { key: nextKey(), id: null, role: 'assistant', content: '', streaming: true }

  let begun = false
  const begin = () => {
    if (begun) return
    begun = true
    state.messages = [...state.messages.slice(0, index), user, reply]
  }

  state.streaming = true
  state.liveTools = []
  state.pending = null
  controller = new AbortController()

  try {
    await chatApi.backtrackChat(
      { conversationId: state.sessionId, messageId, content: text },
      {
        signal: controller.signal,
        onEvent: (evt) => {
          begin()
          handleEvent(evt, reply)
        },
      },
    )
  } catch (error) {
    if ((error as Error)?.name === 'AbortError') {
      if (begun) reply.content += '\n\n（已停止）'
    } else {
      toast(messageOf(error), 'error')
    }
  } finally {
    // 没 begun 说明请求在进图前就被拒了：界面保持原样，不截断、不留空气泡
    if (begun) reply.streaming = false
    state.streaming = false
    state.liveTools = []
    controller = null
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
