<script setup lang="ts">
/**
 * 意见反馈。住在工作区，从个人主页底部进来。
 *
 * 两件事：写一条新的，看自己以前提过的处理到哪一步了。
 * `/feedback/page`、`/feedback/process` 是管理员后台，不进用户界面。
 *
 * 类型四个值是后端定死的 1~4，文案照 FeedbackSubmitRequest 的注释抄；
 * 状态 0~3 照 FeedbackResponse 的注释抄。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { myFeedback, submitFeedback } from '../api/feedback'
import type { FeedbackRecord, FeedbackType } from '../core/types'
import { toast } from '../core/store'

const TYPES: Array<{ value: FeedbackType; label: string }> = [
  { value: 1, label: '功能建议' },
  { value: 2, label: 'Bug 反馈' },
  { value: 3, label: '体验问题' },
  { value: 4, label: '其他' },
]

const TYPE_TEXT: Record<number, string> = { 1: '功能建议', 2: 'Bug 反馈', 3: '体验问题', 4: '其他' }
const STATUS_TEXT: Record<number, string> = { 0: '待处理', 1: '处理中', 2: '已回复', 3: '已关闭' }

/** 与后端 validateFeedbackImage 的允许格式和体积上限保持一致 */
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp']
const MAX_SHOT = 5 * 1024 * 1024

const type = ref<FeedbackType>(1)
const content = ref('')
const contact = ref('')
const shot = ref<File | null>(null)
const shotUrl = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const sending = ref(false)
const sent = ref(false)

const rows = ref<FeedbackRecord[]>([])
const total = ref(0)
const loadingList = ref(false)
const listError = ref('')

async function loadMine() {
  loadingList.value = true
  listError.value = ''
  try {
    const page = await myFeedback(1, 20)
    rows.value = page.records
    total.value = page.total
  } catch (error) {
    // 列表是次要信息，取不到就安静降级，不能挡住提交
    listError.value = error instanceof Error ? error.message : '历史记录没取到'
  } finally {
    loadingList.value = false
  }
}

function pickFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 清空 value：同一个文件第二次选也要能触发 change
  input.value = ''
  if (!file) return

  if (!ALLOWED_TYPES.includes(file.type)) {
    toast('截图只支持 jpg、png、gif、webp、bmp', 'error')
    return
  }
  if (file.size > MAX_SHOT) {
    toast('截图不要超过 5MB', 'error')
    return
  }

  dropShot()
  shot.value = file
  shotUrl.value = URL.createObjectURL(file)
}

function dropShot() {
  if (shotUrl.value) URL.revokeObjectURL(shotUrl.value)
  shotUrl.value = ''
  shot.value = null
}

async function submit() {
  const text = content.value.trim()
  if (!text) return toast('先写点内容', 'error')
  if (sending.value) return

  sending.value = true
  try {
    await submitFeedback({
      type: type.value,
      content: text,
      contact: contact.value.trim() || undefined,
      file: shot.value ?? undefined,
    })
    sent.value = true
    content.value = ''
    contact.value = ''
    dropShot()
    // 提交完马上把新记录带出来，用户不用刷新就知道进去了
    await loadMine()
  } catch (error) {
    toast(error instanceof Error ? error.message : '没提交上，请稍后再试', 'error')
  } finally {
    sending.value = false
  }
}

/** 提交后给一张空表，而不是关掉页面：用户常常一次想到两件事 */
function again() {
  sent.value = false
}

/** createTime 形如 2026-11-24T19:03:18，掐掉年份和秒够用了 */
function whenText(raw: string): string {
  if (!raw) return ''
  return raw.slice(5, 16).replace('T', ' ')
}

onMounted(loadMine)

// 预览图的 objectURL 必须手动回收，否则每换一次图就漏一份内存
onBeforeUnmount(dropShot)
</script>

<template>
  <section class="feedback">
    <header class="head">
      <h1>意见反馈</h1>
      <p class="lede">哪里不好用、哪里出错了，直接写。处理完会发一条通知给你。</p>
    </header>

    <template v-if="sent">
      <p class="done">已经收到了。谢谢你说这些。</p>
      <button class="act" type="button" @click="again">再写一条</button>
    </template>

    <template v-else>
      <div class="field-row">
        <span class="tag">类型</span>
        <div class="chips">
          <button
            v-for="item in TYPES"
            :key="item.value"
            class="chip"
            type="button"
            :aria-pressed="type === item.value"
            @click="type = item.value"
          >
            {{ item.label }}
          </button>
        </div>
      </div>

      <div class="field-row top">
        <span class="tag">内容</span>
        <div class="grow">
          <textarea
            v-model="content"
            class="box"
            rows="5"
            maxlength="1000"
            placeholder="发生了什么、你本来想做什么、现在变成了什么样"
          ></textarea>
          <p v-if="content.length > 800" class="count">{{ content.length }} / 1000</p>
        </div>
      </div>

      <div class="field-row">
        <span class="tag">截图</span>
        <div class="shot">
          <template v-if="shotUrl">
            <img class="thumb" :src="shotUrl" alt="待提交的截图" />
            <span class="shot-name">{{ shot?.name }}</span>
            <button class="act" type="button" @click="dropShot">移除</button>
          </template>
          <template v-else>
            <button class="act" type="button" @click="fileInput?.click()">选一张图</button>
            <span class="hint">可空，5MB 内</span>
          </template>
          <input
            ref="fileInput"
            type="file"
            accept="image/jpeg,image/png,image/gif,image/webp,image/bmp"
            hidden
            @change="pickFile"
          />
        </div>
      </div>

      <div class="field-row">
        <span class="tag">联系</span>
        <input
          v-model="contact"
          class="line"
          type="text"
          maxlength="60"
          placeholder="手机号或邮箱，可空"
        />
      </div>

      <p class="caution">
        内容和截图工作人员都能看到，截图还存在公开地址上。密码、身份证、银行卡这些别放进来。
      </p>

      <div class="foot">
        <span class="hint">还想补一句？提交后可以接着再写一条。</span>
        <button class="act primary" type="button" :disabled="sending" @click="submit">
          {{ sending ? '提交中…' : '提交' }}
        </button>
      </div>
    </template>

    <section v-if="rows.length || loadingList || listError" class="mine">
      <h2 class="sec">我提交过的</h2>

      <p v-if="loadingList && !rows.length" class="empty">正在取…</p>
      <p v-else-if="listError && !rows.length" class="empty">{{ listError }}</p>

      <ul v-else>
        <li v-for="row in rows" :key="row.id" class="rec">
          <div class="rec-top">
            <span class="kind">{{ TYPE_TEXT[row.type] ?? '反馈' }}</span>
            <span class="state" :data-s="row.status">{{ STATUS_TEXT[row.status] ?? '' }}</span>
            <span class="time">{{ whenText(row.createTime) }}</span>
          </div>

          <p class="rec-body">{{ row.content }}</p>

          <img v-if="row.images" class="rec-shot" :src="row.images" alt="反馈截图" />

          <p v-if="row.reply" class="rec-reply">
            <span class="who">回复</span>{{ row.reply }}
          </p>
        </li>
      </ul>

      <p v-if="total > rows.length" class="more">还有 {{ total - rows.length }} 条没显示</p>
    </section>
  </section>
</template>

<style scoped>
.feedback {
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow-y: auto;
  padding: var(--s5) var(--s4) var(--s6);
}

.head h1 {
  font-size: 20px;
  font-weight: 500;
  letter-spacing: -0.01em;
}

.lede {
  margin-top: 6px;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--muted);
}

.field-row {
  display: flex;
  align-items: center;
  gap: var(--s2);
  min-height: 40px;
  padding: 7px 0;
  font-size: var(--t-sm);
  border-bottom: 1px solid var(--rule);
}

.field-row:first-of-type {
  margin-top: var(--s4);
}

.field-row.top {
  align-items: flex-start;
}

.tag {
  flex: 0 0 42px;
  color: var(--muted);
  padding-top: 6px;
}

.grow {
  flex: 1;
  min-width: 0;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  padding: 4px 10px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  font-size: var(--t-xs);
  color: var(--muted);
  background: var(--paper);
}

.chip:hover {
  color: var(--ink);
  border-color: var(--rule-strong);
}

.chip[aria-pressed='true'] {
  color: var(--signal);
  border-color: var(--signal-line);
  background: var(--signal-soft);
}

.box {
  display: block;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  background: var(--paper);
  font: inherit;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--ink);
  resize: vertical;
}

.box:focus,
.line:focus {
  border-color: var(--rule-strong);
}

.count {
  margin-top: 3px;
  font-size: var(--t-xs);
  color: var(--muted-soft);
  text-align: right;
}

.line {
  flex: 1;
  min-width: 0;
  padding: 6px 9px;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  background: var(--paper);
  font-size: var(--t-sm);
  color: var(--ink);
}

.shot {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.thumb {
  width: 36px;
  height: 36px;
  object-fit: cover;
  border-radius: var(--r-sm);
  border: 1px solid var(--rule);
}

.shot-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--t-xs);
  color: var(--muted);
}

.caution {
  margin-top: var(--s3);
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--muted-soft);
}

.foot {
  display: flex;
  align-items: center;
  gap: var(--s3);
  margin-top: var(--s4);
}

.hint {
  flex: 1;
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

.act {
  padding: 5px 0;
  font-size: var(--t-sm);
  color: var(--muted);
}

.act:hover {
  color: var(--ink);
}

.act.primary {
  color: var(--signal);
  font-weight: 500;
}

.act:disabled {
  opacity: 0.5;
}

.done {
  margin-top: var(--s5);
  font-size: var(--t-sm);
  color: var(--ink-soft);
}

.done + .act {
  margin-top: 10px;
}

/* 「我提交过的」是回看区，不是主流程：标题小、分隔线轻，不与上面的表单抢注意力 */
.mine {
  margin-top: var(--s6);
  padding-top: var(--s4);
  border-top: 1px solid var(--rule);
}

.sec {
  font-size: var(--t-xs);
  font-weight: 500;
  letter-spacing: 0.04em;
  color: var(--muted);
}

.empty {
  margin-top: 10px;
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

.rec {
  padding: 10px 0;
  border-bottom: 1px solid var(--rule);
}

.rec:last-child {
  border-bottom: none;
}

.rec-top {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.kind {
  font-size: var(--t-xs);
  color: var(--ink-soft);
}

.state {
  font-size: var(--t-xs);
  color: var(--muted);
}

.state[data-s='1'] {
  color: var(--signal);
}

.state[data-s='2'] {
  color: var(--ink);
}

.state[data-s='3'] {
  color: var(--muted-soft);
}

.time {
  margin-left: auto;
  font-size: var(--t-xs);
  color: var(--muted-soft);
  font-variant-numeric: tabular-nums;
}

.rec-body {
  margin-top: 4px;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--ink-soft);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.rec-shot {
  display: block;
  max-width: 100%;
  max-height: 160px;
  margin-top: 6px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  object-fit: cover;
}

.rec-reply {
  margin-top: 6px;
  padding-left: 9px;
  border-left: 1px solid var(--rule-strong);
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--ink-soft);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.who {
  margin-right: 6px;
  color: var(--muted);
}

.more {
  margin-top: 10px;
  font-size: var(--t-xs);
  color: var(--muted-soft);
}
</style>
