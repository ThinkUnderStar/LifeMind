<script setup lang="ts">
/**
 * 对话栏。用户在右、AI 在左：两侧各有自己的一栏，但不套气泡——
 * 这里不是闲聊，是「你说一句，它去改你的日程」，内容本身才是重点。
 * 文本块靠右但仍左对齐（长句右对齐难读），比彩色气泡安静。
 *
 * 滚动策略：只在用户本来就贴着底部时才自动跟随。用户上滑看历史时如果被强行拽回底部，
 * 是这类界面最烦人的一处。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import { loadOlder, rewrite, send, setAgentPermission, setChatMode, state, stop, toast, toggleWebSearch } from '../core/store'
import type { UiMessage } from '../core/store'
import type { ChatMode } from '../api/chat'
import ApprovalCard from '../components/ApprovalCard.vue'
import TaskList from '../components/TaskList.vue'
import UiIcon from '../components/UiIcon.vue'
import brandLogo from '../assets/brand/lifemind-logo.png'
import { renderMarkdown } from '../core/markdown'

const draft = ref('')
const streamEl = ref<HTMLElement | null>(null)
const stick = ref(true)
/**
 * 是否已经成功贴到底过。刷新/切会话时历史是异步填进来的：
 * 内容一瞬间从 0 条撑成几十条，浏览器仍停在 scrollTop=0，会先派发一次 scroll 事件。
 * 那次不是用户上滑，若按「距底太远」处理会让 stick 永久变 false，之后就再也不贴底了。
 * 所以在第一次真正滚到底之前，onScroll 一律不改贴底意愿。
 */
let pinnedOnce = false

/** 信任程度菜单：只两档，点开即选；点击外部或选中后收起 */
const permEl = ref<HTMLElement | null>(null)
const permOpen = ref(false)

function pickPermission(value: 'normal' | 'full') {
  setAgentPermission(value)
  permOpen.value = false
}

/** 运行模式菜单：工作 / 纯对话，同样是点开即选、点外部收起 */
const modeEl = ref<HTMLElement | null>(null)
const modeOpen = ref(false)

function pickMode(value: ChatMode) {
  setChatMode(value)
  modeOpen.value = false
}

function onDocPointerDown(event: PointerEvent) {
  const target = event.target as Node
  if (permOpen.value && !permEl.value?.contains(target)) permOpen.value = false
  if (modeOpen.value && !modeEl.value?.contains(target)) modeOpen.value = false
}

/**
 * 改写一条历史消息（时间回溯）。
 *
 * 只有 id 不为空的消息能改：id 来自后端，是回溯接口要的 message_id；
 * 本次刚发出去、还没从后端读回来的消息在本地是临时键，改不了。
 * 点了「改写」不直接提交，先把这条换成输入框——它会删掉这条之后的所有内容，值得再看一眼。
 */
const rewriting = ref<{ id: number; text: string } | null>(null)
const rewritingBusy = ref(false)

/**
 * 改写框默认固定两行高；内容变长时自动长到上限，再长就在框内滚动。
 * 不允许手动拉伸，否则一长条会把整屏消息顶走。
 */
const REWRITE_MIN_HEIGHT = 56
const REWRITE_MAX_HEIGHT = 240
const rewriteBoxEl = ref<HTMLTextAreaElement | null>(null)

// 改写框在 v-for 里，字符串 ref 会被 Vue 收成数组；用函数 ref 直接拿到当前那一个元素
function setRewriteBoxEl(el: Element | ComponentPublicInstance | null) {
  rewriteBoxEl.value = el instanceof HTMLTextAreaElement ? el : null
}

function fitRewriteBox() {
  const el = rewriteBoxEl.value
  if (!el) return
  el.style.height = 'auto'
  const need = el.scrollHeight
  el.style.height = `${Math.max(REWRITE_MIN_HEIGHT, Math.min(need, REWRITE_MAX_HEIGHT))}px`
  el.style.overflowY = need > REWRITE_MAX_HEIGHT ? 'auto' : 'hidden'
}

watch(() => rewriting.value?.text, () => void nextTick(fitRewriteBox))

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
  pinnedOnce = false
  beginSettle(1000)
  void scrollToEnd(true)
})

// 历史是异步拉回来的：这一刻容器还是空的，上面那次 force 滚了个空。
// 消息从「没有」变成「有」时再强制贴底一次——刷新后落回最新一条靠的就是这里。
watch(() => state.messages.length, (len, prev) => {
  if (len === 0) {
    pinnedOnce = false
    return
  }
  if (prev) return // 往上翻历史（len 变大）和流式新增都不该抢滚动位置
  stick.value = true
  pinnedOnce = false
  beginSettle(1200)
  void nextTick(() => {
    scrollToEnd(true)
    // markdown 渲染、中文换行、任务行挂载会二次撑高，隔一拍再补一次
    window.setTimeout(() => scrollToEnd(true), 60)
  })
})

/** AI 用品牌标，用户用账号首字母——和顶栏头像同一套口径 */
const userInitial = computed(() => (state.account || '我').trim().charAt(0).toUpperCase())

/**
 * 首个字出来之前的等待态。纯静态文案会让人以为卡住了，
 * 用跳点 + 轮播的中性提示告诉用户「还在干活」，但不承诺具体在做哪一步。
 */
const THINKING_HINTS = ['正在理解你的问题', '正在梳理相关信息', '正在组织回答']
const thinkingStep = ref(0)
const waitingFirstToken = computed(() =>
  state.messages.some((m) => m.role === 'assistant' && m.streaming && !m.content),
)
let thinkingTimer: number | undefined

watch(
  waitingFirstToken,
  (waiting) => {
    if (thinkingTimer !== undefined) {
      window.clearInterval(thinkingTimer)
      thinkingTimer = undefined
    }
    if (!waiting) return
    thinkingStep.value = 0
    thinkingTimer = window.setInterval(() => {
      thinkingStep.value = (thinkingStep.value + 1) % THINKING_HINTS.length
    }, 2600)
  },
  { immediate: true },
)

/**
 * 一键复制这条消息的原文。AI 回复复制的是 markdown 源，粘到别处不丢格式；
 * 用户消息就是原话。剪贴板 API 在非安全上下文可能不可用，降级到 execCommand。
 */
async function copyMessage(message: UiMessage) {
  const text = message.content
  if (!text) return
  // 先试现代剪贴板 API；它抛错（非安全上下文、无权限）时再降级到 execCommand。
  // 两条路都走不通才提示手动复制。
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      toast('已复制')
      return
    }
  } catch {
    /* 落到下面的 execCommand */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    if (!ok) throw new Error('copy failed')
    toast('已复制')
  } catch {
    toast('复制失败，请手动选择文本', 'error')
  }
}

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

/**
 * 开场锚定期。刷新恢复时浏览器会把滚动容器的 scrollTop 还原成上次的值（往往是 0），
 * 这个还原可能晚于我们的任何一次 rAF 补滚，而且不一定派发 scroll 事件——
 * 光靠「首次贴底前不改 stick」挡不住它。所以在刚挂载 / 刚切会话 / 历史刚到达的一小段时间内，
 * 定时把位置拉回底部，期间任何「跑到上面去」的滚动都当作浏览器恢复而不是用户操作。
 */
let settleUntil = 0
let settleTimers: number[] = []

function beginSettle(ms = 1400) {
  settleUntil = Date.now() + ms
  for (const t of settleTimers) window.clearTimeout(t)
  // 浏览器恢复的时机不固定（load 之后、字体就绪之后都可能），多补几拍把它压过去
  settleTimers = [0, 60, 160, 300, 520, 800, 1200].map((d) =>
    window.setTimeout(() => scrollToEnd(true), d),
  )
}

function onScroll() {
  // 自己滚的，不改贴底意愿
  if (programmatic) return
  const el = streamEl.value
  if (!el) return
  // 开场窗口内：位置突然跑到上面去是浏览器恢复造成的，直接纠正
  if (Date.now() < settleUntil) {
    stick.value = true
    scrollToEnd(true)
    return
  }
  // 首次贴底之前：历史正在填，这类 scroll 一律不当成用户上滑
  if (!pinnedOnce) {
    stick.value = true
    return
  }
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
  // 内容确实溢出时才算真的贴到了底；空会话滚不动，不能拿它当「已贴底」
  if (el.scrollHeight > el.clientHeight) pinnedOnce = true
  // 本帧之后高度还可能再变：中文换行重排、任务行挂载、输入框撑高把视口压矮。
  // 下一帧补一次即可，不做循环——避免和用户上滑抢控制权。
  requestAnimationFrame(() => {
    const box = streamEl.value
    if (!box) return
    if (!force && !stick.value) return
    markProgrammatic()
    box.scrollTop = box.scrollHeight
    if (box.scrollHeight > box.clientHeight) pinnedOnce = true
  })
}

// 内容高度一变就贴底——这是可靠的关键。
// 只盯 content.length 的 watch 在「一帧塞进大段文本」时会漏：watch 只触发一次，
// nextTick 时中文换行、任务行尚未把高度撑到最终值，之后没有第二次触发，最后一条就被留在输入框下面。
// ResizeObserver 观察内容容器的真实高度，任何一次布局增长（文本、换行、任务行）都会回调。
let ro: ResizeObserver | null = null

// 注意：刷新时 .stream 被 chatLoading 的 v-if 挡着，是不存在的——
// onMounted 里拿到的 streamEl/bodyEl 都是 null，那时建 RO 会直接落空，
// 等消息渲染出来就再没人贴底了（表现为刷新后停在最上面）。
// 所以这里盯元素本身出现：v-if 一放开就装监听并钉到底。
watch(
  [streamEl, bodyEl],
  ([stream, body]) => {
    ro?.disconnect()
    ro = null
    if (!stream || !body) return
    if (typeof ResizeObserver !== 'undefined') {
      ro = new ResizeObserver(() => scrollToEnd())
      ro.observe(body)
      // 视口本身变矮时（输入框撑高、操作行换行）也要重新贴底，
      // 否则内容没变、可视区变小，最后一条会被压在下面露不出来
      ro.observe(stream)
    }
    pinnedOnce = false
    stick.value = true
    beginSettle()
    scrollToEnd(true)
  },
  { flush: 'post' },
)

onMounted(() => {
  document.addEventListener('pointerdown', onDocPointerDown)
  // 刷新恢复时别让浏览器还原文档滚动，位置由我们自己钉到最新一条
  if ('scrollRestoration' in history) history.scrollRestoration = 'manual'
  // 开场锚定：刷新后历史异步到达 + 浏览器延迟还原滚动位置，这一小段时间由我们接管
  beginSettle()
  scrollToEnd(true)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointerDown)
  ro?.disconnect()
  ro = null
  for (const t of settleTimers) window.clearTimeout(t)
  settleTimers = []
  if (programmaticTimer !== null) window.clearTimeout(programmaticTimer)
  if (thinkingTimer !== undefined) window.clearInterval(thinkingTimer)
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

function onKeydown(event: KeyboardEvent) {
  // Enter 发送，Shift+Enter 换行。中文输入法组字时的 Enter 不能当发送
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

/** 输入框自动长高，超过上限就停住、交给框内滚动（与 CSS 的 max-height 保持一致） */
const BOX_MAX_HEIGHT = 180

function autoGrow(event: Event) {
  const el = event.target as HTMLTextAreaElement
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, BOX_MAX_HEIGHT)}px`
}
</script>

<template>
  <section class="chat">
    <!-- 刷新后后端还没回数据时，空 messages 不是「欢迎页」，先给一个明确的加载态 -->
    <div v-if="state.chatLoading" class="chat-loading" aria-live="polite">
      <span class="loading-mark" aria-hidden="true"></span>
      <span>正在载入对话…</span>
    </div>

    <template v-else>
    <div ref="streamEl" class="stream" @scroll="onScroll">
     <div ref="bodyEl" class="stream-body">
      <div v-if="!state.messages.length" class="intro">
        <h1>说一件事，它替你去记。</h1>
        <p class="intro-lede">
          日程、待办、提醒和备忘都收在工作区里。说清楚什么时候、做什么，它会把要做的事摆出来问你，确认后再落库。
        </p>
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
              <textarea
                :ref="setRewriteBoxEl"
                v-model="rewriting.text"
                class="rewrite-box"
                @input="fitRewriteBox"
              ></textarea>
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
            <span v-else-if="message.streaming" class="thinking" role="status" aria-label="正在生成回复">
              <span class="thinking-dots" aria-hidden="true"><i></i><i></i><i></i></span>
              <span :key="thinkingStep" class="thinking-text" aria-hidden="true">{{ THINKING_HINTS[thinkingStep] }}</span>
            </span>
            <span v-if="message.streaming && message.content" class="caret" aria-hidden="true"></span>
            <!-- 消息操作条：每条都有一键复制；用户消息额外有改写。悬停才显，不抢阅读 -->
            <div v-if="message.content && !message.streaming" class="msg-actions">
              <button
                class="msg-icon"
                type="button"
                title="复制这条内容"
                aria-label="复制这条内容"
                @click="copyMessage(message)"
              >
                <UiIcon name="copy" :size="14" />
              </button>
              <button
                v-if="message.role === 'user' && message.id !== null && !state.streaming"
                class="msg-icon"
                type="button"
                title="改写这句话，从这一句重新生成"
                aria-label="改写这句话"
                @click="startRewrite(message)"
              >
                <UiIcon name="edit" :size="14" />
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
      <!-- 输入框一整条圆角容器：上面留输入空间，操作组与发送钮收在框内底部，不再单独占一行 -->
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

        <div class="shell-foot">
          <div class="left-ops">
            <!-- 运行模式：纯对话下后端不建任务、不动组件，是硬开关，不靠模型自觉 -->
            <!-- 高亮类名不能用 chat：本文件根容器已有 .chat（带 flex-direction:column），会串样式 -->
            <div ref="modeEl" class="perm-picker">
              <button
                class="mode-toggle"
                :class="{ 'is-chat': state.chatMode === 'chat' }"
                type="button"
                aria-haspopup="menu"
                :aria-expanded="modeOpen"
                :title="state.chatMode === 'chat' ? '纯对话模式：只聊天不执行任务' : '工作模式：该建任务就建任务'"
                @click="modeOpen = !modeOpen"
              >
                <UiIcon :name="state.chatMode === 'chat' ? 'feedback' : 'apps'" :size="15" />
                <span>{{ state.chatMode === 'chat' ? '纯对话' : '工作' }}</span>
              </button>
              <div v-if="modeOpen" class="perm-menu" role="menu">
                <button
                  type="button"
                  role="menuitem"
                  :class="{ active: state.chatMode === 'work' }"
                  @click="pickMode('work')"
                >
                  <UiIcon name="apps" :size="13" />
                  <span>工作模式</span>
                  <UiIcon v-if="state.chatMode === 'work'" name="check" :size="13" class="perm-mark" />
                </button>
                <button
                  type="button"
                  role="menuitem"
                  :class="{ active: state.chatMode === 'chat' }"
                  @click="pickMode('chat')"
                >
                  <UiIcon name="feedback" :size="13" />
                  <span>纯对话模式</span>
                  <UiIcon v-if="state.chatMode === 'chat'" name="check" :size="13" class="perm-mark" />
                </button>
              </div>
            </div>

            <button
              class="web-toggle"
              :class="{ on: state.webSearch }"
              type="button"
              :aria-pressed="state.webSearch"
              :title="state.webSearch ? '本会话联网已开启，可用搜索查最新信息' : '本会话联网已关闭，点一下开启'"
              @click="toggleWebSearch"
            >
              <UiIcon name="globe" :size="15" />
              <span>联网</span>
            </button>

            <!-- 信任程度按会话独立；full 下敏感工具不弹确认卡直接执行 -->
            <div ref="permEl" class="perm-picker">
              <button
                class="perm-toggle"
                :class="{ trust: state.agentPermission === 'full' }"
                type="button"
                aria-haspopup="menu"
                :aria-expanded="permOpen"
                :title="state.agentPermission === 'full' ? '本会话完全信任，敏感操作直接执行' : '本会话每次敏感操作需确认'"
                @click="permOpen = !permOpen"
              >
                <UiIcon :name="state.agentPermission === 'full' ? 'check' : 'list'" :size="14" />
                <span>{{ state.agentPermission === 'full' ? '完全信任' : '需确认' }}</span>
              </button>
              <div v-if="permOpen" class="perm-menu" role="menu">
                <button
                  type="button"
                  role="menuitem"
                  :class="{ active: state.agentPermission === 'normal' }"
                  @click="pickPermission('normal')"
                >
                  <UiIcon name="list" :size="13" />
                  <span>每次确认</span>
                  <UiIcon v-if="state.agentPermission === 'normal'" name="check" :size="13" class="perm-mark" />
                </button>
                <button
                  type="button"
                  role="menuitem"
                  :class="{ active: state.agentPermission === 'full' }"
                  @click="pickPermission('full')"
                >
                  <UiIcon name="check" :size="13" />
                  <span>完全信任</span>
                  <UiIcon v-if="state.agentPermission === 'full'" name="check" :size="13" class="perm-mark" />
                </button>
              </div>
            </div>

            <button
              v-if="state.historyHasMore"
              class="btn btn-text older"
              type="button"
              @click="loadOlder"
            >
              更早的消息
            </button>
          </div>

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
            <UiIcon name="send" :size="16" />
          </button>
        </div>
      </div>
    </form>
    </template>
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
  /* 操作组已在中间列顶部单独占一行，这里只留常规上间距；底部留出一段空白，
     流式输出时最后一行文字和输入框之间始终有呼吸，不会贴着框上沿 */
  padding: var(--s6) max(var(--s5), calc((100% - 760px) / 2)) calc(var(--s6) + var(--s3));
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

/* 刷新后数据未就绪时的加载态：居中一小段，不闪欢迎页 */
.chat-loading {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--muted);
  font-size: var(--t-sm);
}

.loading-mark {
  width: 14px;
  height: 14px;
  border: 2px solid var(--rule-strong);
  border-top-color: var(--signal);
  border-radius: 50%;
  animation: chat-loading-spin 0.8s linear infinite;
}

@keyframes chat-loading-spin {
  to { transform: rotate(360deg); }
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
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: var(--t-sm);
}

.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 1em;
}

.thinking-dots i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--signal);
  animation: think-bounce 1.2s var(--ease) infinite;
}

.thinking-dots i:nth-child(2) { animation-delay: 0.15s; }
.thinking-dots i:nth-child(3) { animation-delay: 0.3s; }

@keyframes think-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.32; }
  30% { transform: translateY(-4px); opacity: 1; }
}

.thinking-text {
  animation: think-in 0.45s var(--ease);
}

@keyframes think-in {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: none; }
}

@media (prefers-reduced-motion: reduce) {
  .thinking-dots i,
  .thinking-text { animation: none; }
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
  /* 操作组已经收进输入框内部，这里只需要和消息区之间的边距 */
  padding: var(--s4) max(var(--s5), calc((100% - 760px) / 2)) var(--s4);
  border-top: 1px solid var(--rule);
  background: var(--paper);
}

.left-ops {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s2) var(--s3);
  min-width: 0;
}

.older {
  padding: 0;
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
  gap: 6px;
  padding: 3px 10px 3px 8px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--muted);
  font-size: var(--t-sm);
  line-height: 1.6;
  white-space: nowrap;
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease),
    background var(--dur) var(--ease);
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

/* 信任程度与运行模式：联网旁边两枚胶囊，都是按会话存的偏好。菜单只两档，点开即选，不写解释小字 */
.perm-picker {
  position: relative;
}

.perm-toggle,
.mode-toggle {
  display: inline-flex;
  flex-direction: row;
  align-items: center;
  gap: 6px;
  padding: 3px 10px 3px 8px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--muted);
  font-size: var(--t-sm);
  line-height: 1.6;
  white-space: nowrap;
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease),
    background var(--dur) var(--ease);
}

.perm-toggle:hover,
.mode-toggle:hover {
  color: var(--ink);
  border-color: var(--rule-strong);
}

.perm-toggle.trust {
  color: var(--signal);
  border-color: var(--signal-line);
  background: var(--signal-soft);
}

/* 模式只换文字与图标（工作 / 纯对话），高亮只染字色和描边，不铺底色——
   免得它变成三枚胶囊里唯一一块实心块，抢了输入框的注意力 */
.mode-toggle.is-chat {
  color: var(--signal);
  border-color: var(--signal-line);
}

.perm-menu {
  position: absolute;
  left: 0;
  bottom: calc(100% + 8px);
  z-index: 20;
  min-width: 148px;
  padding: 4px;
  border: 1px solid var(--rule);
  border-radius: var(--r-lg);
  background: var(--paper);
  box-shadow: 0 8px 24px rgb(0 0 0 / 10%);
}

.perm-menu button {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 9px;
  border: 0;
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--ink-soft);
  font-size: var(--t-xs);
  text-align: left;
  cursor: pointer;
}

.perm-menu button:hover {
  background: var(--signal-soft);
}

.perm-menu button.active {
  color: var(--signal);
  background: var(--signal-soft);
}

.perm-menu .perm-mark {
  margin-left: auto;
}

/* 消息操作条：悬停才显的小圆钮，收在消息下方各自一侧（AI 靠左、用户靠右），不抢阅读 */
.msg-actions {
  display: flex;
  justify-content: flex-start;
  gap: var(--s2);
  height: 26px;
  margin-top: 2px;
  opacity: 0;
  transition: opacity var(--dur) var(--ease);
}

.turn[data-role='user'] .msg-actions {
  justify-content: flex-end;
}

.turn:hover .msg-actions,
.turn:focus-within .msg-actions {
  opacity: 1;
}

.msg-icon {
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

.msg-icon:hover {
  color: var(--signal);
  border-color: var(--signal-line);
  background: var(--signal-soft);
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
  height: 56px; /* 默认固定两行；实际高度由 fitRewriteBox 按内容算，上限 240px */
  max-height: 240px;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--r-md);
  background: transparent;
  font: inherit;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--ink);
  resize: none; /* 高度只跟内容走，不让手动拖成无限长 */
  overflow-y: hidden; /* 到上限后才由 fitRewriteBox 切成 auto */
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

/* 输入框是一整条圆角容器：文字在上，操作组与发送钮收在框内底部一行 */
.input-shell {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  border: 1px solid var(--rule);
  /* 框体比常规卡片再圆一档，配合更大的内边距，别显得方硬 */
  border-radius: calc(var(--r-lg) + 4px);
  background: var(--paper-raised);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}

.input-shell:focus-within {
  border-color: var(--signal);
  box-shadow: inset 0 0 0 1px var(--signal-line);
}

.box {
  flex: none;
  width: 100%;
  min-width: 0;
  min-height: 64px;
  resize: none;
  /* 多行超出 max-height 后就在框内滚动，不再继续长高 */
  overflow-y: auto;
  padding: 20px 22px 10px;
  border: 0;
  background: transparent;
  font-size: var(--t-md);
  line-height: var(--lh-body);
  max-height: 180px;
}

/* 框内操作行：左边胶囊组、右边发送钮，和输入文字同一个容器里 */
.shell-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  min-height: 36px;
  padding: 0 12px 12px 14px;
}

.box:focus {
  outline: none;
}

.box:disabled {
  color: var(--muted);
}

/* 发送/停止共用一个圆形图标钮，放在操作行最右端 */
.send-icon {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
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
  /* 空输入时不做半透明幽灵，改成一块安静的浅底——存在感在，但不催人 */
  background: var(--signal-soft);
  color: var(--muted-soft);
  cursor: default;
}

.send-icon.stop {
  background: var(--alert-soft);
  color: var(--alert);
}

/* 发送图标是 UiIcon（fill 风格），停止方块是原生 svg（需显式 fill） */
.send-icon svg {
  width: 18px;
  height: 18px;
}

.send-icon.stop svg {
  fill: currentColor;
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
}
</style>
