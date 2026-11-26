<script setup lang="ts">
/**
 * 一条 AI 回复上方的那一轮任务清单。
 *
 * 归属只认 task_bind_id：后端把同一轮的任务与那条 AI 消息绑在同一个 UUID 上，
 * 所以这里不需要按时间或顺序去猜「哪几条属于哪一次回复」。
 *
 * 展开策略：生成中默认展开（用户正等着看它排了哪几步、走到哪）、生成结束自动收起，
 * 收起后点标题行随时能再展开。用户中途手动开合过，也以「结束即收起」为准——
 * 收尾动作保持一个确定的落点，比记住临时操作更少意外。
 */
import { computed, ref, watch } from 'vue'
import ChevronIcon from './ChevronIcon.vue'
import type { AgentTask } from '../core/types'

const props = defineProps<{
  tasks: AgentTask[]
  /** 这条回复还在生成：默认展开，生成结束自动收起 */
  streaming: boolean
}>()

const open = ref(props.streaming)

watch(
  () => props.streaming,
  (now) => {
    open.value = now
  },
)

const STATUS_TEXT: Record<AgentTask['status'], string> = {
  pending: '待执行',
  running: '进行中',
  done: '已结束',
}

const doneCount = computed(() => props.tasks.filter((t) => t.status === 'done').length)
const running = computed(() => props.tasks.some((t) => t.status === 'running'))

const summary = computed(() => {
  const total = props.tasks.length
  if (props.streaming || running.value) return `任务 ${total} · 已完成 ${doneCount.value}/${total}`
  return `任务 ${total} · 全部结束`
})
</script>

<template>
  <div class="tasklist">
    <button
      class="tasklist-head"
      type="button"
      :aria-expanded="open"
      @click="open = !open"
    >
      <ChevronIcon class="caret" :class="{ open }" :size="11" />
      <span class="summary">{{ summary }}</span>
      <span v-if="streaming" class="pulse" aria-hidden="true"></span>
    </button>

    <ul v-if="open" class="tasklist-body">
      <li v-for="task in tasks" :key="task.id" :data-status="task.status">
        <span class="status">{{ STATUS_TEXT[task.status] }}</span>
        <span class="goal">{{ task.goal }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
/* 挂在回复正文上方：左边与正文对齐，整体比正文轻一档，不抢内容 */
.tasklist {
  margin-bottom: var(--s2);
  max-width: 100%;
}

.tasklist-head {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 9px 3px 7px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--muted);
  font-size: var(--t-xs);
  line-height: 1.5;
  cursor: pointer;
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease);
}

.tasklist-head:hover {
  color: var(--ink-soft);
  border-color: var(--rule-strong);
}

.tasklist-head .caret {
  transition: transform var(--dur) var(--ease);
}

.tasklist-head .caret.open {
  transform: rotate(90deg);
}

.tasklist-head .pulse {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--signal);
  animation: taskPulse 1.4s var(--ease) infinite;
}

@keyframes taskPulse {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 1; }
}

/* 展开后是一张纸片，不用列表符号：状态在左、目标在右，长目标换行不撑破 */
.tasklist-body {
  margin-top: 6px;
  padding: 2px 0 2px 2px;
  border-left: 1px solid var(--rule);
}

.tasklist-body li {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  gap: 8px;
  padding: 4px 0 4px 10px;
  font-size: var(--t-xs);
  line-height: 1.6;
}

.tasklist-body .status {
  color: var(--muted);
}

.tasklist-body li[data-status='running'] .status {
  color: var(--signal);
}

.tasklist-body li[data-status='running'] .goal {
  color: var(--ink);
}

.tasklist-body li[data-status='done'] .goal {
  color: var(--muted);
}

.tasklist-body .goal {
  color: var(--ink-soft);
  overflow-wrap: anywhere;
}
</style>
