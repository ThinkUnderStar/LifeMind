<script setup lang="ts">
/**
 * 对话栏。用户在右、AI 在左：两侧各有自己的一栏，但不套气泡——
 * 这里不是闲聊，是「你说一句，它去改你的日程」，内容本身才是重点。
 * 文本块靠右但仍左对齐（长句右对齐难读），比彩色气泡安静。
 *
 * 滚动策略：只在用户本来就贴着底部时才自动跟随。用户上滑看历史时如果被强行拽回底部，
 * 是这类界面最烦人的一处。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { loadOlder, rewrite, send, state, stop, toast } from '../core/store'
import type { UiMessage } from '../core/store'
import { listTasks } from '../api/chat'
import type { AgentTask } from '../core/types'
import ApprovalCard from '../components/ApprovalCard.vue'
import brandLogo from '../assets/brand/lifemind-logo.png'

const draft = ref('')
const streamEl = ref<HTMLElement | null>(null)
const stick = ref(true)

/**
 * 改写一条历史消息（时间回溯）。
 *
 * 只有 id 不为空的消息能改：id 来自后端，是回溯接口要的 message_id；
 * 本次刚发出去、还没从后端读回来的消息在本地是临时键，改不了。
 * 点了「改写」不直接提交，先把这条换成输入框——它会删掉这条之后的所有内容，值得再看一眼。
 */
const rewriting = ref<{ id: number; text: string } | null>(null)
const rewritingBusy = ref(false)

function startRewrite(message: UiMessage) {
  if (state.streaming || message.id === null) return
  rewriting.value = { id: message.id, text: message.content }
}

function cancelRewrite() {
  rewriting.value = null
}

async function submitRewrite() {
  const target = rewriting.value
  if (!target || rewritingBusy.value) return
  const text = target.text.trim()
  if (!text) return toast('内容不能为空', 'error')
  rewritingBusy.value = true
  try {
    await rewrite(target.id, text)
    rewriting.value = null
  } finally {
    rewritingBusy.value = false
  }
}

/**
 * 当前会话的任务清单。任务的中间流水不进对话，但「它到底排了哪几步、走到哪了」
 * 用户有权看到，所以放在输入框上方按需展开，不占正文。
 */
const tasks = ref<AgentTask[]>([])
const showTasks = ref(false)

const TASK_TEXT: Record<AgentTask['status'], string> = {
  pending: '待执行',
  running: '进行中',
  done: '已结束',
}

async function loadTasks() {
  if (!state.sessionId) {
    tasks.value = []
    return
  }
  try {
    tasks.value = await listTasks(state.sessionId)
  } catch {
    // 任务清单是辅助信息，取不到就不显示，不打断对话
    tasks.value = []
  }
}

watch(
  () => [state.sessionId, state.streaming] as const,
  () => {
    if (!state.streaming) void loadTasks()
  },
  { immediate: true },
)

// 换会话时把改名、改写这些临时态一起收起来
watch(() => state.sessionId, () => {
  rewriting.value = null
  showTasks.value = false
})


/** AI 用品牌标，用户用账号首字母——和顶栏头像同一套口径 */
const userInitial = computed(() => (state.account || '我').trim().charAt(0).toUpperCase())

const EXAMPLES = [
  '明天下午三点项目评审，提前半小时提醒我',
  '每周三晚上健身，先来 12 次',
  '我有哪些逾期的待办',
]

function onScroll() {
  const el = streamEl.value
  if (!el) return
  // 距底 80px 内算「贴着底部」，留一点余量，避免元素高度抖动时误判
  stick.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

async function scrollToEnd(force = false) {
  await nextTick()
  const el = streamEl.value
  if (!el) return
  if (force || stick.value) el.scrollTop = el.scrollHeight
}

watch(
  () => state.messages.length,
  () => void scrollToEnd(true),
)

// 流式追加时逐帧跟随；不能 watch 整个数组，那样每个 token 都会触发一次深比较
watch(
  () => state.messages[state.messages.length - 1]?.content.length ?? 0,
  () => void scrollToEnd(),
)

function submit() {
  const text = draft.value.trim()
  if (!text || state.streaming) return
  draft.value = ''
  stick.value = true
  void send(text)
}

function useExample(text: string) {
  draft.value = text
}

function onKeydown(event: KeyboardEvent) {
  // Enter 发送，Shift+Enter 换行。中文输入法组字时的 Enter 不能当发送
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

function autoGrow(event: Event) {
  const el = event.target as HTMLTextAreaElement
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 160)}px`
}
</script>

<template>
  <section class="chat">
    <div ref="streamEl" class="stream" @scroll="onScroll">
      <div v-if="!state.messages.length" class="intro">
        <h1>说一件事，它替你去记。</h1>
        <p class="intro-lede">
          日程、待办、提醒、备忘都收在工作区里，需要时从右上角展开。你只要说清楚什么时候、做什么，
          它会先把要做的操作摆出来问你是否同意，再落库。
        </p>
        <ul class="examples">
          <li v-for="text in EXAMPLES" :key="text">
            <button type="button" @click="useExample(text)">{{ text }}</button>
          </li>
        </ul>
      </div>

      <article
        v-for="message in state.messages"
        :key="message.key"
        class="turn"
        :data-role="message.role"
      >
        <span class="avatar" :title="message.role === 'user' ? '你' : 'LifeMind'">
          <img v-if="message.role === 'assistant'" :src="brandLogo" alt="LifeMind" />
          <template v-else>{{ userInitial }}</template>
        </span>
        <div class="body">
          <!-- 改写态：行内编辑面板，两个动作收在面板右下角 -->
          <template v-if="rewriting && rewriting.id === message.id">
            <div class="rewrite-panel">
              <textarea v-model="rewriting.text" class="rewrite-box" rows="3"></textarea>
              <div class="rewrite-acts">
                <span class="warn">会删掉这条之后的内容，重新生成</span>
                <button class="btn btn-text" type="button" :disabled="rewritingBusy" @click="cancelRewrite">
                  取消
                </button>
                <button class="btn btn-primary" type="button" :disabled="rewritingBusy" @click="submitRewrite">
                  {{ rewritingBusy ? '重新生成中…' : '重新生成' }}
                </button>
              </div>
            </div>
          </template>

          <template v-else>
            <template v-if="message.content">{{ message.content }}</template>
            <span v-else-if="message.streaming" class="thinking">正在想办法…</span>
            <span v-if="message.streaming && message.content" class="caret" aria-hidden="true"></span>
            <!-- 改写入口：悬停出现的细线铅笔图标，收在消息下方右侧 -->
            <div v-if="message.role === 'user' && message.id !== null && !state.streaming" class="rewrite-bar">
              <button
                class="rewrite-icon"
                type="button"
                title="改写这句话，从这一句重新生成"
                aria-label="改写这句话"
                @click="startRewrite(message)"
              >
                <svg viewBox="0 0 20 20" aria-hidden="true">
                  <path d="M4 16h4l8-8-4-4-8 8v4ZM11.5 4.5l4 4" />
                </svg>
              </button>
            </div>
          </template>
        </div>
      </article>

      <!-- 工具进度：成对出现，跑完就消失。不写「第几步」，因为任务是动态生成的，编号会跳 -->
      <ul v-if="state.liveTools.length" class="tools" aria-live="polite">
        <li v-for="tool in state.liveTools" :key="tool.id" class="tool">
          <span class="pulse" aria-hidden="true"></span>
          {{ tool.action }}
        </li>
      </ul>

      <ApprovalCard v-if="state.pending" />
    </div>

    <form class="composer" @submit.prevent="submit">
      <div class="composer-top">
        <div class="left-ops">
          <button
            v-if="state.historyHasMore"
            class="btn btn-text older"
            type="button"
            @click="loadOlder"
          >
            更早的消息
          </button>
          <button
            v-if="tasks.length"
            class="btn btn-text"
            type="button"
            @click="showTasks = !showTasks"
          >
            {{ showTasks ? '收起任务' : `任务 ${tasks.length}` }}
          </button>
        </div>

      </div>

      <!-- 任务清单：它排了哪几步、走到哪了。done 只表示这条结束，不等于成功 -->
      <ul v-if="showTasks && tasks.length" class="tasks">
        <li v-for="task in tasks" :key="task.id" :data-status="task.status">
          <span class="task-status">{{ TASK_TEXT[task.status] }}</span>
          <span class="task-goal">{{ task.goal }}</span>
        </li>
      </ul>

      <!-- 输入框是一整条圆角容器，发送/停止图标收在框内右下角，不再像表单+按钮 -->
      <div class="input-shell" :data-streaming="state.streaming">
        <textarea
          v-model="draft"
          class="box"
          rows="1"
          placeholder="说点什么，比如「明天下午三点项目评审，提前半小时提醒我」"
          :disabled="state.streaming"
          @keydown="onKeydown"
          @input="autoGrow"
        ></textarea>

        <button
          v-if="state.streaming"
          class="send-icon stop"
          type="button"
          title="停止生成"
          aria-label="停止生成"
          @click="stop"
        >
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M6.5 6.5h7v7h-7z" /></svg>
        </button>
        <button
          v-else
          class="send-icon"
          type="submit"
          title="发送（Enter）"
          aria-label="发送"
          :disabled="!draft.trim()"
        >
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 15V5M5.5 9.5 10 5l4.5 4.5" /></svg>
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.chat {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}

/* 内容列居中：宽屏不散开，窄屏至少留 24px。左右用同一个表达式，滚动条出现时也不歪 */
.stream {
  flex: 1;
  overflow-y: auto;
  /* 操作组已在中间列顶部单独占一行，这里只留常规上间距 */
  padding: var(--s6) max(var(--s5), calc((100% - 760px) / 2)) var(--s4);
}

.intro {
  padding-top: var(--s5);
}

.intro h1 {
  font-size: 28px;
  font-weight: 500;
  line-height: 1.35;
  letter-spacing: -0.02em;
}

.intro h1::before {
  content: '';
  display: block;
  width: 28px;
  height: 2px;
  margin-bottom: var(--s5);
  background: var(--signal);
}

.intro-lede {
  margin-top: var(--s4);
  color: var(--ink-soft);
  font-size: var(--t-md);
  line-height: 1.8;
}

.examples {
  margin-top: var(--s7);
  border-top: 1px solid var(--rule);
}

.examples li {
  border-bottom: 1px solid var(--rule);
}

.examples button {
  display: block;
  width: 100%;
  text-align: left;
  padding: 14px 0;
  font-size: var(--t-sm);
  color: var(--muted);
  transition: color var(--dur) var(--ease), padding-left var(--dur) var(--ease);
}

.examples button:hover {
  color: var(--signal);
  padding-left: var(--s2);
}

/* 头像在正文侧边，不在上方——消息谁说的靠位置和头像就够，不必再占一行标签 */
.turn {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: var(--s4) 0;
}

/* AI 头像在左、用户头像在右；正文跟随各自一侧 */
.turn[data-role='user'] {
  flex-direction: row-reverse;
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  margin-top: 3px;
  border-radius: 50%;
  font-size: var(--t-xs);
  font-weight: 500;
  line-height: 1;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.turn[data-role='assistant'] .avatar {
  background: var(--signal-soft);
}

.turn[data-role='user'] .avatar {
  background: var(--signal);
  color: var(--on-signal);
}

.body {
  /* 限制宽度，长消息不铺满整栏；块靠右，但内部文字仍左对齐 */
  max-width: min(88%, 620px);
  font-size: 16px;
  line-height: 1.75;
  color: var(--ink);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.turn[data-role='user'] .body {
  color: var(--ink-soft);
}

.thinking {
  color: var(--muted);
  font-size: var(--t-sm);
}

/* 光标只在一个地方出现：正在长出来的那句末尾 */
.caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -0.15em;
  background: var(--signal);
  animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}

.tools {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2);
  padding: var(--s2) 0 var(--s4);
}

.tool {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 9px;
  border: 1px solid var(--rule);
  border-radius: 999px;
  font-size: var(--t-xs);
  color: var(--muted);
}

.pulse {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--signal);
  animation: breathe 1.4s var(--ease) infinite;
}

@keyframes breathe {
  0%,
  100% {
    opacity: 0.25;
  }
  50% {
    opacity: 1;
  }
}

.composer {
  padding: var(--s4) max(var(--s5), calc((100% - 760px) / 2)) var(--s5);
  border-top: 1px solid var(--rule);
  background: var(--paper);
}

.composer-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  min-height: 22px;
  margin-bottom: var(--s2);
}

.older {
  padding: 0;
}

.left-ops {
  display: flex;
  align-items: center;
  gap: var(--s3);
}

.left-ops .btn-text {
  padding: 0;
  font-size: var(--t-xs);
  color: var(--muted);
}

.left-ops .btn-text:hover:not(:disabled) {
  color: var(--ink);
}

/* 改写入口：悬停才显的小铅笔圆钮，右对齐收在消息下方，不抢正常阅读 */
.rewrite-bar {
  display: flex;
  justify-content: flex-end;
  height: 26px;
  margin-top: 2px;
  opacity: 0;
  transition: opacity var(--dur) var(--ease);
}

.turn:hover .rewrite-bar,
.turn:focus-within .rewrite-bar {
  opacity: 1;
}

.rewrite-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 1px solid var(--rule);
  border-radius: 50%;
  color: var(--muted);
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease),
    background var(--dur) var(--ease);
}

.rewrite-icon:hover {
  color: var(--signal);
  border-color: var(--signal-line);
  background: var(--signal-soft);
}

.rewrite-icon svg {
  width: 13px;
  height: 13px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* 改写面板：圆角纸面行内展开，两个动作收在右下角，和输入框同一套语言 */
.rewrite-panel {
  margin-top: var(--s2);
  padding: var(--s3);
  border: 1px solid var(--rule);
  border-radius: var(--r-lg);
  background: var(--paper-raised);
}

.rewrite-box {
  display: block;
  width: 100%;
  min-width: 260px;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--r-md);
  background: transparent;
  font: inherit;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--ink);
  resize: vertical;
  white-space: pre-wrap;
}

.rewrite-box:focus {
  outline: none;
}

.rewrite-acts {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s2);
  margin-top: var(--s2);
  padding-top: var(--s2);
  border-top: 1px solid var(--rule);
}

.rewrite-acts .warn {
  flex: 1;
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

.rewrite-acts .btn-text {
  padding: 6px 12px;
  border-radius: var(--r-pill);
  font-size: var(--t-xs);
  color: var(--muted);
}

.rewrite-acts .btn-text:hover:not(:disabled) {
  color: var(--ink);
  background: var(--paper-sunken);
}

.rewrite-acts .btn-primary {
  padding: 7px 16px;
  border-radius: var(--r-pill);
  font-size: var(--t-xs);
}

/* 任务清单：一行一步，状态在左、目标在右，长目标换行不撑破 */
.tasks {
  margin-bottom: var(--s2);
  border-top: 1px solid var(--rule);
}

.tasks li {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  gap: 8px;
  padding: 5px 0;
  border-bottom: 1px solid var(--rule);
  font-size: var(--t-xs);
}

.task-status {
  color: var(--muted);
}

.tasks li[data-status='running'] .task-status {
  color: var(--signal);
}

.tasks li[data-status='done'] .task-goal {
  color: var(--muted);
}

.task-goal {
  color: var(--ink-soft);
  overflow-wrap: anywhere;
}

/* 输入框是一整条圆角容器，图标按钮贴在框内右下角 */
.input-shell {
  position: relative;
  display: flex;
  align-items: flex-end;
  border: 1px solid var(--rule);
  border-radius: var(--r-lg);
  background: var(--paper-raised);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}

.input-shell:focus-within {
  border-color: var(--signal);
  box-shadow: inset 0 0 0 1px var(--signal-line);
}

.box {
  flex: 1;
  min-width: 0;
  resize: none;
  /* 右侧留出图标按钮的位置，多行时长文本也不会压到按钮上 */
  padding: 14px 60px 14px 18px;
  border: 0;
  background: transparent;
  font-size: var(--t-md);
  line-height: 1.6;
  max-height: 160px;
}

.box:focus {
  outline: none;
}

.box:disabled {
  color: var(--muted);
}

/* 发送/停止共用一个圆形图标钮，贴在输入条右下角 */
.send-icon {
  position: absolute;
  right: 9px;
  bottom: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  background: var(--signal);
  color: var(--on-signal);
  transition: opacity var(--dur) var(--ease), background var(--dur) var(--ease);
}

.send-icon:hover:not(:disabled) {
  background: var(--signal-deep);
}

.send-icon:disabled {
  opacity: 0.45;
  cursor: default;
}

.send-icon.stop {
  background: var(--alert-soft);
  color: var(--alert);
}

.send-icon svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.send-icon.stop svg {
  fill: currentColor;
  stroke: none;
}

/* 窄屏靠底部三段开工作区，右上角开关不显示，免得点了看不到面板 */
@media (max-width: 1180px) {
  .workspace-op {
    display: none;
  }
}

/* 窄屏 placeholder 会折成两行，给输入框留够高度，别把提示文字裁掉 */
@media (max-width: 720px) {
  .stream {
    padding-top: calc(var(--s6) + 16px);
  }

  .box {
    min-height: 64px;
  }

  /* 窄屏图标按钮仍贴在输入条右下角，右侧留白加大，别压到折行的文字 */
  .box {
    padding-right: 56px;
  }
}
</style>
