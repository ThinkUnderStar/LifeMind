<script setup lang="ts">
/**
 * 会话栏。按后端返回的顺序（update_time 倒序）直接渲染，前端不再排序——
 * 两处排序规则各写一遍，早晚会不一致。
 *
 * 当前会话用「深一档的行底 + 左侧 3px 竖条 + 标题提到墨色」三重标记。
 * 行底必须比未选行肉眼可辨：原来借 --signal-soft 只有 2% 亮度差，
 * 等于只靠竖条区分，扫一眼分不出当前是哪条。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  loadSessions,
  logout,
  newSession,
  openFeedback,
  openNotice,
  openPluginLibrary,
  openProfile,
  openSession,
  state,
  switchSessionView,
  toast,
} from '../core/store'
import { applyTheme, currentTheme, THEMES, type ThemeKey } from '../core/theme'
import ChevronIcon from '../components/ChevronIcon.vue'
import UiIcon from '../components/UiIcon.vue'
import * as sessionApi from '../api/session'
import { parseDateTime, relativeDayText } from '../core/date'
import brandLogo from '../assets/brand/lifemind-logo.png'

const busy = ref(false)

/* ---------------- 顶部功能区块 ---------------- */

/** 左栏三个功能入口都切顶层独立页，和会话列表互不影响 */
function goPlugins() {
  openPluginLibrary()
}

function goNotice() {
  openNotice()
}

function goFeedback() {
  openFeedback()
}

/* ---------------- 栏底账号菜单 ---------------- */

const accountOpen = ref(false)
const themeOpen = ref(false)
const theme = ref<ThemeKey>(currentTheme())

function chooseTheme(key: ThemeKey) {
  theme.value = key
  applyTheme(key)
  themeOpen.value = false
  accountOpen.value = false
}

function goProfile() {
  accountOpen.value = false
  themeOpen.value = false
  openProfile()
}

function doLogout() {
  accountOpen.value = false
  themeOpen.value = false
  logout()
}

function onDocClick(event: MouseEvent) {
  if (!(event.target as HTMLElement).closest('.me-wrap')) {
    accountOpen.value = false
    themeOpen.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))

/**
 * 改名。用双击标题而不是再加一个按钮：会话行只有 34px 高，
 * 悬停时要同时放「归档」「删除」，再挤一个进去三个都会变得难点中。
 * 双击是列表里改名的通用手势，行的 title 上也写了这一句。
 */
const renaming = ref<{ id: number; text: string } | null>(null)
const renamingBusy = ref(false)

function startRename(item: { id: number; title: string }) {
  renaming.value = { id: item.id, text: item.title || '新对话' }
}

function cancelRename() {
  renaming.value = null
}

async function commitRename() {
  const target = renaming.value
  if (!target || renamingBusy.value) return
  const title = target.text.trim()
  if (!title) return cancelRename()
  if (title === state.sessions.find((s) => s.id === target.id)?.title) return cancelRename()

  renamingBusy.value = true
  try {
    await sessionApi.renameSession(target.id, title)
    renaming.value = null
    await loadSessions(true)
  } catch (error) {
    toast(error instanceof Error ? error.message : '改名失败', 'error')
  } finally {
    renamingBusy.value = false
  }
}

const initial = computed(() => (state.account || '我').trim().charAt(0).toUpperCase())

/** 当前会话还没发过消息：再点「新对话」只会复用它，按钮给一句说明，免得以为没点上 */
const currentUntouched = computed(
  () => state.sessionId !== null && !state.messages.some((m) => m.role === 'user'),
)
const isArchivedView = computed(() => state.sessionView === 'archived')

async function createSession() {
  if (busy.value) return
  busy.value = true
  try {
    await newSession()
  } finally {
    busy.value = false
  }
}

async function remove(id: number) {
  try {
    await sessionApi.deleteSession(id)
    if (state.sessionId === id) {
      state.sessionId = null
      state.messages = []
    }
    await loadSessions(true)
  } catch (error) {
    toast(error instanceof Error ? error.message : '删除失败', 'error')
  }
}

async function archive(id: number) {
  try {
    await sessionApi.archiveSession(id, true)
    if (state.sessionId === id) {
      state.sessionId = null
      state.messages = []
      state.pending = null
    }
    await loadSessions(true)
  } catch (error) {
    toast(error instanceof Error ? error.message : '归档失败', 'error')
  }
}

async function restore(id: number) {
  try {
    await sessionApi.archiveSession(id, false)
    await loadSessions(true)
    toast('已恢复到会话列表')
  } catch (error) {
    toast(error instanceof Error ? error.message : '恢复失败', 'error')
  }
}

function whenOf(raw: string | null): string {
  const date = parseDateTime(raw)
  return date ? relativeDayText(date) : ''
}
</script>

<template>
  <aside class="rail">
    <!-- 品牌收在左栏顶部：不横跨整个界面，主区域从上到下就是左右两块 -->
    <div class="brand">
      <img class="mark" :src="brandLogo" alt="" />
      <span class="name">LifeMind</span>
    </div>

    <!-- 功能区块：和会话列表分开的一组入口，三个入口各自打开一个独立整页 -->
    <nav class="functions" aria-label="功能">
      <button
        class="fn"
        type="button"
        :data-on="state.mainView === 'plugins'"
        @click="goPlugins"
      >
        <UiIcon name="apps" :size="15" />
        <span>插件服务库</span>
      </button>
      <button
        class="fn"
        type="button"
        :data-on="state.mainView === 'notice'"
        @click="goNotice"
      >
        <UiIcon name="bell" :size="15" />
        <span>通知</span>
        <span v-if="state.noticeUnread" class="fn-badge">{{ state.noticeUnread > 99 ? '99+' : state.noticeUnread }}</span>
      </button>
      <button class="fn" type="button" :data-on="state.mainView === 'feedback'" @click="goFeedback">
        <UiIcon name="feedback" :size="15" />
        <span>意见反馈</span>
      </button>
    </nav>

    <div class="rail-head">
      <h2 class="title">
        <button
          class="view-btn"
          type="button"
          :data-on="!isArchivedView"
          @click="switchSessionView('active')"
        >会话</button>
        <span class="view-sep">/</span>
        <button
          class="view-btn"
          type="button"
          :data-on="isArchivedView"
          @click="switchSessionView('archived')"
        >已归档</button>
      </h2>
      <button
        v-if="!isArchivedView"
        class="btn btn-quiet new"
        type="button"
        :disabled="busy"
        :title="currentUntouched ? '当前对话还没用过，再点还是这条' : '开一个新对话'"
        @click="createSession"
      >
        ＋ 新对话
      </button>
    </div>

    <!-- 拉取中显示骨架，不用空状态顶一帧：刷新时明明有会话，闪一句「还没有会话」很突兀 -->
    <ul v-if="state.sessionsLoading" class="list skeleton" aria-label="会话加载中">
      <li v-for="i in 5" :key="i" class="sk-row">
        <span class="sk-line"></span>
      </li>
    </ul>

    <ul v-else-if="state.sessions.length" class="list">
      <li v-for="item in state.sessions" :key="item.id">
        <div class="row" :data-active="state.sessionId === item.id">
          <form v-if="renaming?.id === item.id" class="rename" @submit.prevent="commitRename">
            <input
              v-model="renaming.text"
              class="rename-field"
              type="text"
              maxlength="100"
              autofocus
              @keydown.esc="cancelRename"
              @blur="commitRename"
            />
          </form>
          <button
            v-else
            class="open"
            type="button"
            title="打开这个会话；双击标题可以改名"
            @click="openSession(item.id)"
            @dblclick="startRename(item)"
          >
            <span class="text">{{ item.title || '新对话' }}</span>
            <span class="when">{{ whenOf(item.update_time) }}</span>
          </button>
          <div v-if="renaming?.id !== item.id" class="ops">
            <button v-if="isArchivedView" type="button" title="恢复到会话列表" @click="restore(item.id)">恢复</button>
            <button v-else type="button" title="归档" @click="archive(item.id)">归档</button>
            <button type="button" title="删除" class="danger" @click="remove(item.id)">删除</button>
          </div>
        </div>
      </li>
    </ul>

    <p v-else class="empty">
      {{ isArchivedView ? '还没有归档的会话。' : '还没有会话。点「新对话」，或者直接在中间说一句你要安排的事。' }}
    </p>

    <!-- 栏底账号：主题切换 / 个人主页 / 退出都收在这里；个人主页是独立整页，不占工作区标签 -->
    <div class="me-wrap">
      <button
        class="me"
        type="button"
        :aria-expanded="accountOpen"
        title="账号与设置"
        @click="accountOpen = !accountOpen"
      >
        <span class="me-avatar">{{ initial }}</span>
        <span class="me-name">{{ state.account || '已登录' }}</span>
      </button>

      <div v-if="accountOpen" class="me-pop">
        <div class="me-pop-head">
          <span class="me-avatar large">{{ initial }}</span>
          <span class="me-pop-id">{{ state.account || '已登录' }}</span>
        </div>

        <!-- 主题色收成一条入口，点开再弹子菜单，不让三个色块平铺占满账号菜单 -->
        <button
          class="me-pop-item has-sub"
          type="button"
          :aria-expanded="themeOpen"
          @click="themeOpen = !themeOpen"
        >
          <span class="swatch" :style="{ background: THEMES.find((t) => t.key === theme)?.color }"></span>
          <span>主题色</span>
          <span class="sub-current">{{ THEMES.find((t) => t.key === theme)?.name }}</span>
          <ChevronIcon dir="right" :size="13" />
        </button>

        <button
          class="me-pop-item"
          type="button"
          :data-on="state.mainView === 'profile'"
          @click="goProfile"
        >个人主页</button>
        <button class="me-pop-item danger" type="button" @click="doLogout">退出登录</button>
      </div>

      <!-- 主题色子菜单：从账号菜单右侧弹出，选完两层的关掉 -->
      <div v-if="accountOpen && themeOpen" class="theme-menu" role="menu" aria-label="主题色">
        <button class="theme-menu-back" type="button" @click="themeOpen = false">
          <ChevronIcon dir="left" :size="13" />主题色
        </button>
        <button
          v-for="item in THEMES"
          :key="item.key"
          class="theme-menu-item"
          type="button"
          role="menuitemradio"
          :aria-checked="theme === item.key"
          :data-on="theme === item.key"
          @click="chooseTheme(item.key)"
        >
          <span class="swatch" :style="{ background: item.color }"></span>
          <span class="theme-menu-name">{{ item.name }}</span>
          <UiIcon v-if="theme === item.key" name="check" :size="14" />
        </button>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.rail {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--rule);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
  height: 60px;
  padding: 0 var(--s4);
  border-bottom: 1px solid var(--rule);
}

.mark {
  width: 30px;
  height: 30px;
  object-fit: contain;
}

.name {
  font-family: 'Segoe Script', 'Brush Script MT', cursive;
  font-size: 21px;
  line-height: 1;
  letter-spacing: 0.01em;
  color: var(--ink);
}

/* 功能区块：一条一个入口，排在会话列表上方、和会话用分隔线隔开 */
.functions {
  display: grid;
  gap: 2px;
  flex: 0 0 auto;
  padding: var(--s2);
  border-bottom: 1px solid var(--rule);
}

.fn {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 10px;
  border-radius: var(--r-lg);
  font-size: var(--t-sm);
  color: var(--ink-soft);
  text-align: left;
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}

.fn:hover {
  background: var(--paper-sunken);
  color: var(--ink);
}

/* 插件服务库是顶层视图，进去时这一条点亮，和会话选中态同款语言 */
.fn[data-on='true'] {
  color: var(--ink);
  background: var(--row-active);
  font-weight: 500;
}

.fn svg {
  width: 15px;
  height: 15px;
  flex: 0 0 auto;
}

.fn-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  margin-left: auto;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--signal);
  color: var(--on-signal);
  font-size: 10px;
  line-height: 1;
}

.rail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s2);
  padding: var(--s3) var(--s3) var(--s3) var(--s4);
  border-bottom: 1px solid var(--rule);
}

.title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--t-xs);
  font-weight: 500;
  color: var(--muted);
}

.view-btn {
  padding: 2px 0;
  color: var(--muted-soft);
  transition: color 0.15s ease;
}

.view-btn[data-on='true'] {
  color: var(--ink);
}

.view-btn:hover {
  color: var(--ink);
}

.view-sep {
  color: var(--muted-soft);
  opacity: 0.6;
}

.new {
  padding: 5px 12px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  font-size: var(--t-xs);
  color: var(--ink-soft);
}

.new:hover:not(:disabled) {
  border-color: var(--rule-strong);
  background: var(--paper-sunken);
}

.list {
  flex: 1;
  overflow-y: auto;
  padding: var(--s2);
}

/* 空态也要吃掉剩余高度，才能把栏底的账号条顶下去；否则没会话时它会紧跟在提示文字后面。
   视觉（字号/内距/颜色）沿用全局 .empty，这里只补布局。 */
.empty {
  flex: 1;
}

/* 骨架占位：和真实行等高（约 34px），切换时高度不跳 */
.sk-row {
  display: flex;
  align-items: center;
  height: 34px;
  margin-bottom: var(--s1);
  padding: 0 13px;
  border-radius: var(--r-lg);
  background: var(--paper-sunken);
}

.sk-line {
  display: block;
  width: 68%;
  height: 10px;
  border-radius: 3px;
  background: var(--rule);
  animation: sk-pulse 1.1s ease-in-out infinite;
}

.sk-row:nth-child(2) .sk-line {
  width: 78%;
}

.sk-row:nth-child(3) .sk-line {
  width: 54%;
}

.sk-row:nth-child(4) .sk-line {
  width: 72%;
}

.sk-row:nth-child(5) .sk-line {
  width: 61%;
}

@keyframes sk-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}

/*
 * 一条会话是一枚 34px 高的圆角长条，但**平时不落底色**：未选中就贴在纸底上，
 * 只有鼠标悬停和选中才浮出色块。整栏五条都铺底色会变成五张卡片，视线没有落点，
 * 反而看不出当前是哪条。
 */
.row {
  position: relative;
  display: flex;
  align-items: center;
  margin-bottom: var(--s1);
  border-radius: var(--r-lg);
  background: transparent;
  transition: background var(--dur) var(--ease);
}

/* 悬停给一档浅底，告诉人这一条可以点；比选中档浅，不与选中抢 */
.row:hover {
  background: var(--paper-sunken);
}

.row::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: var(--r-pill);
  background: transparent;
}

.row[data-active='true'],
.row[data-active='true']:hover {
  background: var(--row-active);
}

.row[data-active='true']::before {
  background: var(--signal);
}

.open {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  text-align: left;
  padding: 7px 10px 7px 13px;
  font-size: var(--t-sm);
}

.text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-soft);
}

.row[data-active='true'] .text {
  color: var(--ink);
  font-weight: 500;
}

.when {
  flex: 0 0 auto;
  font-size: var(--t-xs);
  line-height: 1;
  color: var(--muted-soft);
}

.ops {
  display: none;
  gap: 2px;
  padding-right: 8px;
}

/* 悬停时把时间让给操作，行高不变，不会因为多两个按钮把列表挤高 */
.row:hover .when,
.row:focus-within .when {
  display: none;
}

.row:hover .ops,
.row:focus-within .ops {
  display: flex;
}

.ops button {
  font-size: var(--t-xs);
  color: var(--muted);
  padding: 2px 4px;
  border-radius: var(--r-sm);
}

.ops button:hover {
  color: var(--ink);
  background: var(--paper-sunken);
}

.ops .danger:hover {
  color: var(--alert);
}

/* 改名输入框直接长在行里：高度与 .open 一致，改名时列表不跳 */
.rename {
  flex: 1;
  min-width: 0;
  padding: 4px 8px 4px 13px;
}

.rename-field {
  width: 100%;
  padding: 3px 6px;
  border: 1px solid var(--signal-line);
  border-radius: var(--r-sm);
  background: var(--paper);
  font-size: var(--t-sm);
  color: var(--ink);
}

/* 栏底账号：一行到底，只有头像和名字，不加箭头和提示字样 */
.me-wrap {
  position: relative;
  flex: 0 0 auto;
  margin: 0 var(--s2) var(--s2);
}

.me {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 8px 10px;
  border-radius: var(--r-lg);
  text-align: left;
  transition: background var(--dur) var(--ease);
}

.me:hover,
.me[aria-expanded='true'] {
  background: var(--paper-sunken);
}

.me-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--signal);
  color: var(--on-signal);
  font-size: var(--t-xs);
  font-weight: 500;
  line-height: 1;
}

.me-avatar.large {
  width: 34px;
  height: 34px;
  font-size: var(--t-sm);
}

.me-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--t-sm);
  color: var(--ink-soft);
}

/* 账号菜单：贴着账号条上方浮起来，宽度跟着左栏 */
.me-pop {
  position: absolute;
  z-index: var(--z-pop);
  left: 0;
  bottom: calc(100% + 6px);
  width: 100%;
  min-width: 190px;
  padding: 6px;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  background: var(--paper-raised);
  box-shadow: 0 8px 24px rgb(0 0 0 / 12%);
}

.me-pop-head {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 6px 6px 10px;
}

.me-pop-id {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--t-sm);
  color: var(--ink);
}

/* 主题色入口：账号菜单里只占一行，显示当前档位和右箭头，点开再弹子菜单 */
.me-pop-item.has-sub {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 0;
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);
  border-radius: 0;
}

.me-pop-item.has-sub:hover,
.me-pop-item.has-sub[aria-expanded='true'] {
  color: var(--ink);
  background: var(--paper-sunken);
}

.me-pop-item.has-sub :deep(svg) {
  color: var(--muted-soft);
}

.sub-current {
  margin-left: auto;
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

/* 子菜单：贴在账号菜单右侧、底边对齐，不盖住会话列表 */
.theme-menu {
  position: absolute;
  z-index: var(--z-pop);
  left: calc(100% + 6px);
  bottom: calc(100% + 6px);
  width: 176px;
  padding: 6px;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  background: var(--paper-raised);
  box-shadow: 0 8px 24px rgb(0 0 0 / 12%);
}

.theme-menu-back {
  display: flex;
  align-items: center;
  gap: 5px;
  width: 100%;
  padding: 6px 6px 9px;
  border-bottom: 1px solid var(--rule);
  font-size: var(--t-xs);
  color: var(--muted);
  text-align: left;
}

.theme-menu-back:hover {
  color: var(--ink);
}

.theme-menu-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 8px 6px;
  border-radius: var(--r-sm);
  font-size: var(--t-sm);
  color: var(--ink-soft);
  text-align: left;
}

.theme-menu-item:first-of-type {
  margin-top: 4px;
}

.theme-menu-item:hover {
  background: var(--paper-sunken);
}

.theme-menu-item[data-on='true'] {
  color: var(--signal);
  background: var(--signal-soft);
}

.theme-menu-name {
  flex: 1;
}

.swatch {
  display: inline-block;
  flex: 0 0 auto;
  width: 12px;
  height: 12px;
  border: 1px solid var(--rule);
  border-radius: 50%;
}

.me-pop-item {
  display: block;
  width: 100%;
  margin-top: 2px;
  padding: 8px;
  border-radius: var(--r-sm);
  font-size: var(--t-sm);
  color: var(--muted);
  text-align: left;
}

.me-pop-item:hover {
  color: var(--ink);
  background: var(--paper-sunken);
}

.me-pop-item[data-on='true'] {
  color: var(--signal);
  background: var(--signal-soft);
}

.me-pop-item.danger:hover {
  color: var(--alert);
  background: var(--alert-soft);
}
</style>
