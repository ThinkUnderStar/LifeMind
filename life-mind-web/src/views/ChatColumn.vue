<script setup lang="ts">
/**
 * 对话栏。这一栏的设计选择和主流聊天界面不一样：不用左右气泡。
 *
 * 理由：这里的对话不是两个人在闲聊，是「你说一句，它去改你的日程」。
 * 气泡会把注意力引到「谁说的」，而用户真正要读的是内容和它做了什么。
 * 所以改用「小标签 + 正文」的段落式排布，整体左对齐，读起来像记录而非聊天。
 *
 * 滚动策略：只在用户本来就贴着底部时才自动跟随。用户上滑看历史时如果被强行拽回底部，
 * 是这类界面最烦人的一处。
 */
import { nextTick, ref, watch } from 'vue'
import { loadOlder, send, state, stop } from '../core/store'
import ApprovalCard from '../components/ApprovalCard.vue'

const draft = ref('')
const streamEl = ref<HTMLElement | null>(null)
const stick = ref(true)

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
          日程、待办、提醒、备忘都在右侧面板里。你只需要说清楚什么时候、做什么，
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
        <p class="who">{{ message.role === 'user' ? '你' : 'LifeMind' }}</p>
        <div class="body">
          <template v-if="message.content">{{ message.content }}</template>
          <span v-else-if="message.streaming" class="thinking">正在想办法…</span>
          <span v-if="message.streaming && message.content" class="caret" aria-hidden="true"></span>
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
      <button
        v-if="state.historyHasMore"
        class="btn btn-text older"
        type="button"
        @click="loadOlder"
      >
        更早的消息
      </button>

      <div class="input-row">
        <textarea
          v-model="draft"
          class="box"
          rows="1"
          placeholder="说点什么，比如「明天下午三点项目评审，提前半小时提醒我」"
          :disabled="state.streaming"
          @keydown="onKeydown"
          @input="autoGrow"
        ></textarea>

        <button v-if="state.streaming" class="btn btn-quiet stop" type="button" @click="stop">
          停止
        </button>
        <button v-else class="btn btn-primary" type="submit" :disabled="!draft.trim()">
          发送
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}

.stream {
  flex: 1;
  overflow-y: auto;
  padding: var(--s5) var(--s5) var(--s3);
}

.intro {
  max-width: 34em;
  padding-top: var(--s5);
}

.intro h1 {
  font-size: var(--t-lg);
  letter-spacing: -0.01em;
}

.intro-lede {
  margin-top: var(--s3);
  color: var(--ink-soft);
  font-size: var(--t-sm);
  line-height: 1.75;
}

.examples {
  margin-top: var(--s5);
  border-top: 1px solid var(--rule);
}

.examples li {
  border-bottom: 1px solid var(--rule);
}

.examples button {
  display: block;
  width: 100%;
  text-align: left;
  padding: 10px 0;
  font-size: var(--t-sm);
  color: var(--muted);
  transition: color var(--dur) var(--ease);
}

.examples button:hover {
  color: var(--signal);
}

.turn {
  max-width: 40em;
  padding: var(--s3) 0;
}

.who {
  font-size: var(--t-xs);
  color: var(--muted-soft);
  margin-bottom: 2px;
}

.turn[data-role='assistant'] .who {
  color: var(--signal);
}

.body {
  font-size: var(--t-md);
  line-height: 1.7;
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
  padding: var(--s2) var(--s5) var(--s4);
  border-top: 1px solid var(--rule);
}

.older {
  margin-bottom: var(--s2);
}

.input-row {
  display: flex;
  align-items: flex-end;
  gap: var(--s2);
}

.box {
  flex: 1;
  min-width: 0;
  resize: none;
  padding: 9px 12px;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  background: var(--paper-raised);
  font-size: var(--t-sm);
  line-height: 1.6;
  max-height: 160px;
  transition: border-color var(--dur) var(--ease);
}

.box:focus {
  outline: none;
  border-color: var(--signal);
}

.box:disabled {
  background: var(--paper-sunken);
  color: var(--muted);
}

.stop {
  flex: 0 0 auto;
  padding: 9px 16px;
}
</style>
