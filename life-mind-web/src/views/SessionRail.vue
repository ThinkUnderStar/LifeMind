<script setup lang="ts">
/**
 * 会话栏。按后端返回的顺序（update_time 倒序）直接渲染，前端不再排序——
 * 两处排序规则各写一遍，早晚会不一致。
 *
 * 当前会话用左侧 2px 竖条标记，不用背景色块：整栏都是浅底，
 * 色块会把它切成「一张卡片」，和全站「不用卡片」的取向冲突。
 */
import { ref } from 'vue'
import { loadSessions, newSession, openSession, state, toast } from '../core/store'
import * as sessionApi from '../api/session'
import { parseDateTime, relativeDayText } from '../core/date'

const busy = ref(false)

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
    await loadSessions()
  } catch (error) {
    toast(error instanceof Error ? error.message : '删除失败', 'error')
  }
}

async function archive(id: number) {
  try {
    await sessionApi.archiveSession(id, true)
    if (state.sessionId === id) state.sessionId = null
    await loadSessions()
  } catch (error) {
    toast(error instanceof Error ? error.message : '归档失败', 'error')
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
      <h2 class="title">会话</h2>
      <button class="btn btn-quiet new" type="button" :disabled="busy" @click="createSession">
        新对话
      </button>
    </div>

    <ul v-if="state.sessions.length" class="list">
      <li v-for="item in state.sessions" :key="item.id">
        <div class="row" :data-active="state.sessionId === item.id">
          <button class="open" type="button" @click="openSession(item.id)">
            <span class="text">{{ item.title || '新对话' }}</span>
            <span class="when">{{ whenOf(item.update_time) }}</span>
          </button>
          <div class="ops">
            <button type="button" title="归档" @click="archive(item.id)">归档</button>
            <button type="button" title="删除" class="danger" @click="remove(item.id)">删除</button>
          </div>
        </div>
      </li>
    </ul>

    <p v-else class="empty">
      还没有会话。点「新对话」，或者直接在中间说一句你要安排的事。
    </p>
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
  font-size: var(--t-xs);
  font-weight: 500;
  color: var(--muted);
}

.new {
  padding: 4px 8px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
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
  padding: var(--s2) 0;
}

.row {
  position: relative;
  display: flex;
  align-items: center;
}

.row::before {
  content: '';
  position: absolute;
  left: 0;
  top: 4px;
  bottom: 4px;
  width: 2px;
  background: transparent;
}

.row[data-active='true']::before {
  background: var(--signal);
}

.open {
  flex: 1;
  min-width: 0;
  display: block;
  text-align: left;
  padding: 7px var(--s4);
  font-size: var(--t-sm);
}

.open:hover {
  background: var(--paper-sunken);
}

.text {
  display: block;
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
  display: block;
  margin-top: 1px;
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

.ops {
  display: none;
  gap: 2px;
  padding-right: var(--s3);
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
</style>
