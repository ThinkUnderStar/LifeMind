<script setup lang="ts">
/**
 * 外壳：顶栏 + 对话 + 右侧工作区。
 *
 * 中间只放对话（通知不再顶掉对话）；浏览器式的多页都在**右侧工作区**自己身上
 * （见 PanelHost），通知也是那里的一页。工作区默认收起，顶栏随时能开；打开后
 * 是一张空白页，列着能用的插件，点一个就地打开，也能新建页再开别的。
 *
 * 右侧宽度可拖、开合与宽度都记在本地；窄屏（≤1180）换成底部三段切换。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { logout, openNoticeWorkspace, openProfileWorkspace, openWorkspace, pollNotices, state, toggleWorkspace } from '../core/store'
import { THEMES, applyTheme, currentTheme, type ThemeKey } from '../core/theme'
import SessionRail from './SessionRail.vue'
import ChatColumn from './ChatColumn.vue'
import PanelHost from './PanelHost.vue'
import ToastLayer from '../components/ToastLayer.vue'
import NoticeLayer from '../components/NoticeLayer.vue'
import brandLogo from '../assets/brand/lifemind-logo.png'

const pane = ref<'rail' | 'chat' | 'panel'>('chat')
let timer: number | null = null

/* ---------------- 通知 ---------------- */

const noticeOpen = computed(
  () =>
    state.workspaceOpen &&
    state.workspacePages.find((p) => p.key === state.workspacePage)?.kind === 'notice',
)

/** 铃铛：把通知作为工作区一页打开，对话留在中间；窄屏跟着切到工作区 */
function openNotifications() {
  openNoticeWorkspace()
  if (window.innerWidth <= 1180) pane.value = 'panel'
}

/* ---------------- 个人主页 ---------------- */

/** 左侧栏底部的账号：个人主页也是工作区一页，对话不被顶掉 */
function openProfile() {
  openProfileWorkspace()
  if (window.innerWidth <= 1180) pane.value = 'panel'
}

/* ---------------- 明暗主题（只在头像下拉里，顶栏不再单列一个） ---------------- */

const theme = ref<ThemeKey>(currentTheme())
const activeTheme = computed(() => THEMES.find((t) => t.key === theme.value)!)

function chooseTheme(key: ThemeKey) {
  theme.value = key
  applyTheme(key)
  accountOpen.value = false
}

/* ---------------- 账户 ---------------- */

const accountOpen = ref(false)
const initial = computed(() => (state.account || '我').trim().charAt(0).toUpperCase())

function onDocClick(event: MouseEvent) {
  const el = event.target as HTMLElement
  if (!el.closest('.account-picker')) accountOpen.value = false
}

/* ---------------- 中间内容区与工作区 ---------------- */

function showWorkspace() {
  openWorkspace()
  pane.value = 'panel'
}

function togglePanel() {
  toggleWorkspace()
  if (window.innerWidth <= 1180) pane.value = state.workspaceOpen ? 'panel' : 'chat'
}

/* ---------------- 工作区宽度（拖拽） ---------------- */

const OPEN_KEY = 'lm.panel.open'
const WIDTH_KEY = 'lm.panel.width'

function clampWidth(value: number): number {
  const max = Math.min(760, Math.max(360, window.innerWidth - 420))
  return Math.max(320, Math.min(max, value))
}

const panelWidth = ref(clampWidth(Number(localStorage.getItem(WIDTH_KEY)) || 420))
const dragging = ref(false)
let dragStartX = 0
let dragStartWidth = 0

function startDrag(event: PointerEvent) {
  if (!state.workspaceOpen || window.innerWidth <= 1180) return
  dragging.value = true
  dragStartX = event.clientX
  dragStartWidth = panelWidth.value
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
}

function moveDrag(event: PointerEvent) {
  if (!dragging.value) return
  panelWidth.value = clampWidth(dragStartWidth + (dragStartX - event.clientX))
}

function endDrag(event: PointerEvent) {
  if (!dragging.value) return
  dragging.value = false
  const el = event.currentTarget as HTMLElement
  if (el.hasPointerCapture?.(event.pointerId)) el.releasePointerCapture(event.pointerId)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  localStorage.setItem(WIDTH_KEY, String(Math.round(panelWidth.value)))
}

function nudge(delta: number) {
  panelWidth.value = clampWidth(panelWidth.value + delta)
  localStorage.setItem(WIDTH_KEY, String(Math.round(panelWidth.value)))
}

const mainStyle = computed(() => ({
  gridTemplateColumns: state.workspaceOpen
    ? `var(--w-rail) minmax(0, 1fr) ${panelWidth.value}px`
    : `var(--w-rail) minmax(0, 1fr) 0px`,
}))

onMounted(() => {
  document.addEventListener('click', onDocClick)
  void pollNotices()
  timer = window.setInterval(() => void pollNotices(), 30_000)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  if (timer !== null) window.clearInterval(timer)
})
</script>

<template>
  <div class="shell" :data-pane="pane">
    <header class="bar">
      <div class="brand">
        <img class="mark" :src="brandLogo" alt="" />
        <span class="name">LifeMind</span>
      </div>

      <div class="bar-right">
        <button
          class="chip workspace-toggle"
          type="button"
          :data-active="state.workspaceOpen"
          title="打开或收起工作区"
          @click="togglePanel"
        >
          <svg class="work-icon" viewBox="0 0 20 20" aria-hidden="true">
            <path d="M3 4h5v5H3zM11 4h6v5h-6zM3 12h5v4H3zM11 12h6v4h-6z" />
          </svg>
          <span>工作区</span>
        </button>

        <div class="notice-picker">
          <button
            class="chip bell"
            type="button"
            :data-active="noticeOpen"
            title="通知"
            @click="openNotifications"
          >
            <svg class="bell-icon" viewBox="0 0 20 20" aria-hidden="true">
              <path d="M10 2.5a4.5 4.5 0 0 0-4.5 4.5v3L4 12.5h12l-1.5-2.5V7A4.5 4.5 0 0 0 10 2.5Z" />
              <path d="M8.2 15a2 2 0 0 0 3.6 0" />
            </svg>
            <span class="bell-label">通知</span>
            <span v-if="state.noticeUnread" class="badge">{{ state.noticeUnread > 99 ? '99+' : state.noticeUnread }}</span>
          </button>

        </div>

        <div class="account-picker">
          <button
            class="avatar"
            type="button"
            :aria-expanded="accountOpen"
            :title="state.account || '账户'"
            @click="accountOpen = !accountOpen"
          >
            {{ initial }}
          </button>
          <div v-if="accountOpen" class="account-pop">
            <div class="account-head">
              <span class="avatar large">{{ initial }}</span>
              <div class="account-id">
                <p class="account-name">{{ state.account || '已登录' }}</p>
                <p class="account-sub">当前账号</p>
              </div>
            </div>

            <div class="account-themes">
              <button
                v-for="item in THEMES"
                :key="item.key"
                type="button"
                :data-active="theme === item.key"
                @click="chooseTheme(item.key)"
              >
                <span class="swatch" :style="{ background: item.color }"></span>
                {{ item.name }}
              </button>
            </div>

            <button class="account-out" type="button" @click="logout">退出登录</button>
          </div>
        </div>
      </div>
    </header>

    <div class="main" :style="mainStyle">
      <SessionRail class="rail" @open-profile="openProfile" />

      <ChatColumn class="view chat" />

      <div v-if="state.workspaceOpen" class="panel-slot">
        <div
          class="resizer"
          role="separator"
          aria-label="拖动调整工作区宽度"
          aria-orientation="vertical"
          tabindex="0"
          @pointerdown="startDrag"
          @pointermove="moveDrag"
          @pointerup="endDrag"
          @pointercancel="endDrag"
          @keydown.left.prevent="nudge(24)"
          @keydown.right.prevent="nudge(-24)"
        >
          <span class="grip"></span>
        </div>
        <PanelHost class="panel" />
      </div>
    </div>

    <nav class="tabs" aria-label="切换区域">
      <button type="button" :aria-current="pane === 'rail'" @click="pane = 'rail'">会话</button>
      <button type="button" :aria-current="pane === 'chat'" @click="pane = 'chat'">对话</button>
      <button type="button" :aria-current="pane === 'panel'" @click="showWorkspace">工作区</button>
    </nav>

    <ToastLayer />
    <NoticeLayer />
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-rows: 60px minmax(0, 1fr);
  height: 100%;
  overflow: hidden;
}

.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s5);
  padding: 0 28px;
  border-bottom: 1px solid var(--rule);
  background: var(--paper-raised);
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
}

.mark {
  width: 34px;
  height: 34px;
  object-fit: contain;
}

.name {
  font-family: 'Segoe Script', 'Brush Script MT', cursive;
  font-size: 23px;
  line-height: 1;
  letter-spacing: 0.01em;
  color: var(--ink);
}

.bar-right {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 11px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  font-size: var(--t-sm);
  color: var(--muted);
  white-space: nowrap;
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease),
    background var(--dur) var(--ease);
}

.chip:hover,
.chip[data-active='true'] {
  color: var(--ink);
  border-color: var(--rule-strong);
}

.chip[data-active='true'] {
  background: var(--paper-sunken);
}

.bell {
  position: relative;
}

.bell-icon,
.work-icon {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--signal);
  color: var(--on-signal);
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
}

/* ---------------- 主体 ---------------- */

.main {
  display: grid;
  min-height: 0;
  overflow: hidden;
}

.view {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.panel-slot {
  position: relative;
  display: flex;
  min-width: 0;
}

.panel {
  flex: 1;
  min-width: 0;
}

.resizer {
  position: absolute;
  z-index: 8;
  left: -5px;
  top: 0;
  bottom: 0;
  width: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: col-resize;
  touch-action: none;
}

.grip {
  width: 2px;
  height: 28px;
  border-radius: 2px;
  background: var(--rule-strong);
  transition: background var(--dur) var(--ease);
}

.resizer:hover .grip,
.resizer:focus-visible .grip {
  background: var(--signal);
}

/* ---------------- 账户 ---------------- */

.account-picker {
  position: relative;
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--signal);
  color: var(--on-signal);
  font-size: var(--t-sm);
  font-weight: 500;
  line-height: 1;
}

.avatar:hover {
  background: var(--signal-deep);
}

.avatar.large {
  width: 38px;
  height: 38px;
  font-size: var(--t-md);
}

.account-pop {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: var(--z-pop);
  width: 208px;
  padding: 6px;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  background: var(--paper-raised);
}

.account-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 8px 12px;
}

.account-name {
  font-size: var(--t-sm);
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-sub {
  margin-top: 2px;
  font-size: var(--t-xs);
  color: var(--muted);
}

.account-themes {
  display: grid;
  gap: 2px;
  padding: 6px 0;
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);
}

.account-themes button {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 8px;
  border-radius: var(--r-sm);
  font-size: var(--t-sm);
  color: var(--ink-soft);
  text-align: left;
}

.account-themes button:hover {
  background: var(--paper-sunken);
}

.account-themes button[data-active='true'] {
  color: var(--signal);
  background: var(--signal-soft);
}

.swatch {
  display: inline-block;
  flex: 0 0 auto;
  width: 12px;
  height: 12px;
  border-radius: 50%;
}

.account-out {
  display: block;
  width: 100%;
  margin-top: 6px;
  padding: 8px;
  border-radius: var(--r-sm);
  font-size: var(--t-sm);
  color: var(--muted);
  text-align: left;
}

.account-out:hover {
  color: var(--alert);
  background: var(--alert-soft);
}

/* ---------------- 窄屏 ---------------- */

.tabs {
  display: none;
}

@media (max-width: 1180px) {
  .shell {
    grid-template-rows: 56px minmax(0, 1fr) auto;
  }

  .bar {
    padding: 0 18px;
  }

  .main {
    grid-template-columns: minmax(0, 1fr) !important;
  }

  .rail,
  .view,
  .panel-slot {
    display: none;
  }

  .shell[data-pane='rail'] .rail,
  .shell[data-pane='chat'] .view,
  .shell[data-pane='panel'] .panel-slot {
    display: flex;
    flex: 1;
    min-width: 0;
  }

  .tabs {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    border-top: 1px solid var(--rule);
    background: var(--paper-raised);
    padding-bottom: env(safe-area-inset-bottom);
  }

  .tabs button {
    margin: 6px 5px;
    padding: 9px 0;
    border-radius: var(--r-pill);
    font-size: var(--t-xs);
    color: var(--muted);
  }

  .tabs button[aria-current='true'] {
    color: var(--ink);
    background: var(--signal-soft);
    box-shadow: inset 0 0 0 1px var(--signal-line);
  }
}

@media (max-width: 720px) {
  .bar {
    padding: 0 14px;
    gap: 8px;
  }

  .brand {
    gap: 7px;
  }

  .mark {
    width: 28px;
    height: 28px;
  }

  .name {
    font-size: 20px;
  }

  .bar-right {
    gap: 6px;
  }

  .chip {
    padding: 5px 8px;
    gap: 5px;
  }

  .bell-label {
    display: none;
  }
}
</style>
