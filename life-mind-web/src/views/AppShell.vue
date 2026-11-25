<script setup lang="ts">
/**
 * 外壳：顶栏 + 三个顶层界面。
 *
 * chat（默认）= 左栏（功能区块 + 会话）｜对话｜右侧工作区；
 * plugins / notice / feedback / profile = 左栏四个入口对应的独立整页，对话侧整块收起。
 * 五者由左栏切换，左栏作为全局导航一直保留。
 *
 * 没有横跨的顶栏：品牌在左栏顶部，工作区开合与清空记录由 TopActions 在中间列
 * 顶部单独占一行，不浮在内容上；工作区展开时停在其左边，切独立页也不消失。
 * 工作区宽度可拖、开合与宽度都记在本地；窄屏（≤1180）换成底部三段切换。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { openWorkspace, pollNotices, state } from '../core/store'
import SessionRail from './SessionRail.vue'
import ChatColumn from './ChatColumn.vue'
import PanelHost from './PanelHost.vue'
import PluginLibrary from './PluginLibrary.vue'
import NoticePage from './NoticePage.vue'
import ProfilePage from './ProfilePage.vue'
import FeedbackPage from './FeedbackPage.vue'
import TopActions from '../components/TopActions.vue'
import ToastLayer from '../components/ToastLayer.vue'
import NoticeLayer from '../components/NoticeLayer.vue'

const pane = ref<'rail' | 'chat' | 'panel'>('chat')
let timer: number | null = null

/* ---------------- 中间内容区与工作区 ---------------- */

function showWorkspace() {
  openWorkspace()
  pane.value = 'panel'
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
  gridTemplateColumns:
    state.mainView !== 'chat'
      ? `var(--w-rail) minmax(0, 1fr)`
      : state.workspaceOpen
        ? `var(--w-rail) minmax(0, 1fr) ${panelWidth.value}px`
        : `var(--w-rail) minmax(0, 1fr) 0px`,
}))

onMounted(() => {
  void pollNotices()
  timer = window.setInterval(() => void pollNotices(), 30_000)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
})
</script>

<template>
  <div class="shell" :data-pane="pane">
    <!-- 左栏是全局导航，五个顶层视图都留着它，才能在独立页里切回对话 -->
    <div class="main" :style="mainStyle" :data-view="state.mainView">
      <SessionRail class="rail" />

      <!-- 中间列：顶部一行是操作组，下面才是视图内容；按钮不浮在内容上，滚动不遮挡 -->
      <div class="center">
        <TopActions />

        <ChatColumn v-if="state.mainView === 'chat'" class="view chat" />
        <PluginLibrary v-else-if="state.mainView === 'plugins'" class="view library" />
        <NoticePage v-else-if="state.mainView === 'notice'" class="view notice-view" />
        <ProfilePage v-else-if="state.mainView === 'profile'" class="view profile-view" />
        <FeedbackPage v-else class="view feedback-view" />
      </div>

      <div v-if="state.mainView === 'chat' && state.workspaceOpen" class="panel-slot">
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

    <nav v-if="state.mainView === 'chat'" class="tabs" aria-label="切换区域">
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
  grid-template-rows: minmax(0, 1fr) auto;
  height: 100%;
  overflow: hidden;
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

/* 中间列：操作组占顶部一行，视图吃掉剩下的高度，两者互不覆盖 */
.center {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.center > .view {
  flex: 1;
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

/* ---------------- 窄屏 ---------------- */

.tabs {
  display: none;
}

@media (max-width: 1180px) {
  .main {
    grid-template-columns: minmax(0, 1fr) !important;
  }

  .rail,
  .center,
  .panel-slot {
    display: none;
  }

  .shell[data-pane='rail'] .rail,
  .shell[data-pane='chat'] .center,
  .shell[data-pane='panel'] .panel-slot {
    display: flex;
    flex: 1;
    min-width: 0;
  }

  /* 四个独立页：窄屏下整页铺满，不受三段切换影响，靠页内「回到对话」返回 */
  .main:not([data-view='chat']) .rail {
    display: none;
  }

  .main:not([data-view='chat']) .center {
    display: flex;
    flex: 1;
    min-width: 0;
  }

  .main[data-view='plugins'] .library,
  .main[data-view='notice'] .notice-view,
  .main[data-view='profile'] .profile-view,
  .main[data-view='feedback'] .feedback-view {
    display: flex;
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

</style>
