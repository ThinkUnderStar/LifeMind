<script setup lang="ts">
/**
 * 会话栏。按后端返回的顺序（update_time 倒序）直接渲染，前端不再排序——
 * 两处排序规则各写一遍，早晚会不一致。
 *
 * 当前会话用「深一档的行底 + 左侧 3px 竖条 + 标题提到墨色」三重标记。
 * 行底必须比未选行肉眼可辨：原来借 --signal-soft 只有 2% 亮度差，
 * 等于只靠竖条区分，扫一眼分不出当前是哪条。
 */
import { computed, ref } from 'vue'
import { loadSessions, newSession, openSession, state, switchSessionView, toast } from '../core/store'
import * as sessionApi from '../api/session'
import { parseDateTime, relativeDayText } from '../core/date'

const emit = defineEmits<{ (e: 'open-profile'): void }>()

const busy = ref(false)

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

    <!-- 栏底的账号：点开是工作区里的个人主页，不顶掉当前对话。只留头像和名字，不加任何提示字样 -->
    <button class="me" type="button" title="个人主页" @click="emit('open-profile')">
      <span class="me-avatar">{{ initial }}</span>
      <span class="me-name">{{ state.account || '已登录' }}</span>
    </button>
  </aside>
</template>

<style scoped>
.rail {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--rule);
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
.me {
  display: flex;
  align-items: center;
  gap: 9px;
  flex: 0 0 auto;
  margin: 0 var(--s2) var(--s2);
  padding: 8px 10px;
  border-radius: var(--r-lg);
  text-align: left;
  transition: background var(--dur) var(--ease);
}

.me:hover {
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

.me-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--t-sm);
  color: var(--ink-soft);
}
</style>
