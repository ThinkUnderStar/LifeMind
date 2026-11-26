<script setup lang="ts">
/**
 * 对话栏。用户在右、AI 在左：两侧各有自己的一栏，但不套气泡——
 * 这里不是闲聊，是「你说一句，它去改你的日程」，内容本身才是重点。
 * 文本块靠右但仍左对齐（长句右对齐难读），比彩色气泡安静。
 *
 * 滚动策略：只在用户本来就贴着底部时才自动跟随。用户上滑看历史时如果被强行拽回底部，
 * 是这类界面最烦人的一处。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { loadOlder, rewrite, send, state, stop, toast, toggleWebSearch } from '../core/store'
import type { UiMessage } from '../core/store'
import ApprovalCard from '../components/ApprovalCard.vue'
import TaskList from '../components/TaskList.vue'
import brandLogo from '../assets/brand/lifemind-logo.png'
import { renderMarkdown } from '../core/markdown'

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
    stick.value = true // 用户主动重新生成，回到贴底看新结果
    await rewrite(target.id, text)
    rewriting.value = null
  } finally {
    rewritingBusy.value = false
  }
}

// 换会话时把改名、改写这些临时态一起收起来，并回到最新消息（不管上个会话滚到哪）
watch(() => state.sessionId, () => {
  rewriting.value = null
  stick.value = true
  void scrollToEnd(true)
})

/** AI 用品牌标，用户用账号首字母——和顶栏头像同一套口径 */
const userInitial = computed(() => (state.account || '我').trim().charAt(0).toUpperCase())

const EXAMPLES = [
  '明天下午三点项目评审，提前半小时提醒我',
  '每周三晚上健身，先来 12 次',
  '我有哪些逾期的待办',
]

const bodyEl = ref<HTMLElement | null>(null)
// 程序贴底也会触发 scroll 事件，用它区分「用户主动上滑」和「我们自己滚到底」
let programmatic = false
let programmaticTimer: number | null = null

function markProgrammatic() {
  programmatic = true
  if (programmaticTimer !== null) window.clearTimeout(programmaticTimer)
  // scroll 事件是异步派发的，留一帧再解除标记
  programmaticTimer = window.setTimeout(() => { programmatic = false }, 80)
}

function onScroll() {
  // 自己滚的，不改贴底意愿
  if (programmatic) return
  const el = streamEl.value
  if (!el) return
  // 距底 60px 内算「贴着底部」。留一点余量，避免中文换行、任务行出现时抖动误判
  stick.value = el.scrollHeight - el.scrollTop - el.clientHeight < 60
}

/** 把视图拉到最新一条。force 用于切会话/主动发消息：无视之前是否滚过，一律回到底部 */
function scrollToEnd(force = false) {
  const el = streamEl.value
  if (!el) return
  if (!force && !stick.value) return
  markProgrammatic()
  el.scrollTop = el.scrollHeight
}

// 内容高度一变就贴底——这是可靠的关键。
// 只盯 content.length 的 watch 在「一帧塞进大段文本」时会漏：watch 只触发一次，
// nextTick 时中文换行、任务行尚未把高度撑到最终值，之后没有第二次触发，最后一条就被留在输入框下面。
// ResizeObserver 观察内容容器的真实高度，任何一次布局增长（文本、换行、任务行）都会回调。
let ro: ResizeObserver | null = null

onMounted(() => {
  if (typeof ResizeObserver === 'undefined') return
  const body = bodyEl.value
  if (!body) return
  ro = new ResizeObserver(() => scrollToEnd())
  ro.observe(body)
  // 首屏：消息在挂载前就位时也要贴到底部
  scrollToEnd(true)
})

onBeforeUnmount(() => {
  ro?.disconnect()
  ro = null
  if (programmaticTimer !== null) window.clearTimeout(programmaticTimer)
})

// 末尾消息 key 变化（用户发出、AI 回复、流式首帧）：交给 RO + 这里双保险。
// loadOlder 往数组前面插历史、末尾那条不变，不会触发，看历史时位置保持不动。
watch(
  () => state.messages[state.messages.length - 1]?.key ?? null,
  () => nextTick(() => scrollToEnd()),
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
     <div ref="bodyEl" class="stream-body">
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
            <!-- 这一轮排了哪几步、每条走到哪：生成中自动展开，回复结束后收起，可再点开 -->
            <TaskList
              v-if="message.role === 'assistant' && message.tasks?.length"
              :tasks="message.tasks"
              :streaming="message.streaming"
            />
            <!-- AI 回复按 markdown 渲染（串行逐帧更新已修，格式随字长出来）；用户原话保持纯文本 -->
            <div
              v-if="message.content && message.role === 'assistant'"
              class="md"
              v-html="renderMarkdown(message.content)"
            ></div>
            <template v-else-if="message.content">{{ message.content }}</template>
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
    </div>

    <form class="composer" @submit.prevent="submit">
      <div class="composer-top">
        <div class="left-ops">
          <button
            class="web-toggle"
            :class="{ on: state.webSearch }"
            type="button"
            :aria-pressed="state.webSearch"
            :title="state.webSearch ? '联网已开启，本轮可用搜索查最新信息' : '联网已关闭，点一下开启'"
            @click="toggleWebSearch"
          >
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <circle cx="10" cy="10" r="6.4" />
              <path d="M3.6 10h12.8M10 3.6c3.3 3.7 3.3 9 0 12.8M10 3.6c-3.3 3.7-3.3 9 0 12.8" />
            </svg>
            <span>联网</span>
          </button>
          <button
            v-if="state.historyHasMore"
            class="btn btn-text older"
            type="button"
            @click="loadOlder"
          >
            更早的消息
          </button>
        </div>

      </div>

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

/* 内容列居中：宽屏不散开，窄屏至少留 24px。左右用同一个表达式，滚动条出现时也不歪。
   .stream 是唯一的滚动容器；.stream-body 包住全部内容，高度随消息真实增长，
   由 ResizeObserver 观察它来贴底——比盯 content.length 可靠（大帧/换行/任务行都能捕获）。
   消息从顶部往下正常排列（主流网页对话式）：不贴底、不撑杆。只有内容超过一屏时，
   scrollToEnd 才会把它滚到最新一条；消息少时 scrollTop=0，自然停在顶部。 */
.stream {
  flex: 1;
  overflow-y: auto;
  /* 操作组已在中间列顶部单独占一行，这里只留常规上间距 */
  padding: var(--s6) max(var(--s5), calc((100% - 760px) / 2)) var(--s4);
  overflow-anchor: none;
}

/* 普通块级容器：消息从顶部排列。min-height 让空态 intro 能垂直居中 */
.stream-body {
  min-height: 100%;
}

/* 空态欢迎语垂直居中；有消息时它不渲染，不影响消息的顶部对齐 */
.intro {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 100%;
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
  flex: 0 0 auto;
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

/* ============ Markdown 正文 ============
   .body 的 white-space:pre-wrap 是给纯文本用户消息留的，markdown 渲染出的 HTML
   要恢复默认空白处理，否则标签间的换行会被显示成多余空行。 */
.md {
  white-space: normal;
  overflow-wrap: anywhere;
}

.md > :first-child {
  margin-top: 0;
}

.md > :last-child {
  margin-bottom: 0;
}

.md p,
.md ul,
.md ol,
.md blockquote,
.md pre,
.md table {
  margin: 0.6em 0;
}

.md h1,
.md h2,
.md h3,
.md h4 {
  margin: 1em 0 0.5em;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: -0.01em;
}

.md h1 { font-size: 1.4em; }
.md h2 { font-size: 1.25em; }
.md h3 { font-size: 1.12em; }

.md ul,
.md ol {
  padding-left: 1.5em;
}

.md ul { list-style: disc; }
.md ol { list-style: decimal; }
.md li { margin: 0.2em 0; }
.md li > p { margin: 0; }

.md strong { font-weight: 650; color: var(--ink); }

.md em { font-style: italic; }

.md a {
  color: var(--signal);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.md a:hover { color: var(--signal-deep); }

.md code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.9em;
  padding: 0.15em 0.4em;
  border-radius: 4px;
  background: var(--paper-sunken);
  color: var(--ink);
}

.md pre {
  padding: 12px 14px;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  background: var(--paper-sunken);
  overflow-x: auto;
  line-height: 1.55;
}

.md pre code {
  padding: 0;
  background: none;
  border-radius: 0;
  font-size: 0.88em;
}

.md blockquote {
  padding-left: 0.9em;
  border-left: 3px solid var(--signal-line);
  color: var(--ink-soft);
}

.md hr {
  margin: 1em 0;
  border: 0;
  border-top: 1px solid var(--rule);
}

.md table {
  border-collapse: collapse;
  font-size: 0.92em;
}

.md th,
.md td {
  padding: 6px 10px;
  border: 1px solid var(--rule);
  text-align: left;
}

.md th { background: var(--paper-sunken); font-weight: 600; }

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
  flex: 0 0 auto;
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

/* 联网开关：关着是一枚安静的描边胶囊，开启后实心反白——一眼能看出这轮会不会上网 */
.web-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 9px 2px 7px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--muted);
  font-size: var(--t-xs);
  line-height: 1.6;
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease),
    background var(--dur) var(--ease);
}

.web-toggle svg {
  width: 13px;
  height: 13px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.4;
  stroke-linecap: round;
}

.web-toggle:hover:not(.on) {
  color: var(--ink);
  border-color: var(--signal-line);
}

.web-toggle.on {
  border-color: transparent;
  background: var(--signal);
  color: var(--on-signal);
}

.web-toggle.on:hover {
  background: var(--signal-deep);
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
