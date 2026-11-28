<script setup lang="ts">
/**
 * 意见反馈。左栏功能区块（或个人主页底部）进入的独立整页，不占工作区标签。
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
import { backToChat, toast } from '../core/store'
import ChevronIcon from '../components/ChevronIcon.vue'

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
    <button class="back" type="button" @click="backToChat">
      <ChevronIcon dir="left" :size="13" />返回对话
    </button>

    <div class="page">
    <header class="hero">
      <h1>意见反馈</h1>
    </header>

    <template v-if="sent">
      <div class="done-panel">
        <h2>提交成功</h2>
        <button class="subtle" type="button" @click="again">再写一条</button>
      </div>
    </template>

    <template v-else>
      <div class="compose">
        <div class="form-row">
          <span class="label">反馈类型</span>
          <div class="types">
            <button
              v-for="item in TYPES"
              :key="item.value"
              class="type"
              type="button"
              :aria-pressed="type === item.value"
              @click="type = item.value"
            >
              {{ item.label }}
            </button>
          </div>
        </div>

        <div class="form-row">
          <label class="label" for="feedback-content">说说情况</label>
          <textarea
            id="feedback-content"
            v-model="content"
            rows="5"
            maxlength="1000"
            placeholder="发生了什么、你本来想做什么、现在变成了什么样"
          ></textarea>
          <p v-if="content.length > 800" class="count">{{ content.length }} / 1000</p>
        </div>

        <div class="form-row">
          <span class="label">截图</span>
          <div class="shot-row">
            <template v-if="shotUrl">
              <img class="thumb" :src="shotUrl" alt="待提交的截图" />
              <span class="shot-name">{{ shot?.name }}</span>
              <button class="subtle" type="button" @click="dropShot">移除</button>
            </template>
            <template v-else>
              <button class="subtle" type="button" @click="fileInput?.click()">选择一张图片</button>
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

        <div class="form-row">
          <label class="label" for="feedback-contact">联系方式</label>
          <input
            id="feedback-contact"
            v-model="contact"
            class="contact"
            type="text"
            maxlength="60"
            placeholder="手机号或邮箱，可空"
          />
        </div>

        <p class="caution">
          内容和截图工作人员都能看到，截图会存放在公开地址上。密码、身份证、银行卡这些别放进来。
        </p>

        <div class="actions">
          <button class="submit" type="button" :disabled="sending" @click="submit">
            {{ sending ? '提交中…' : '提交反馈' }}
          </button>
        </div>
      </div>
    </template>

    <section v-if="rows.length || loadingList || listError" class="mine">
      <div class="mine-head">
        <h2>我提交过的</h2>
        <span v-if="total" class="mine-count">{{ total }} 条</span>
      </div>

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
    </div>
  </section>
</template>

<style scoped>
.feedback {
  display: block;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 40px clamp(24px, 5vw, 72px) 72px;
  background: var(--paper);
}

/* 整页内容统一收在中间一栏，左右留白相等；外层 .feedback 只负责滚动和边缘 padding */
.page {
  width: 100%;
  max-width: 760px;
  margin: 0 auto;
}

.back {
  display: inline-flex;
  align-self: flex-start;
  align-items: center;
  gap: 4px;
  padding: 4px 0;
  font-size: var(--t-xs);
  color: var(--muted);
  transition: color var(--dur) var(--ease);
}

.back:hover {
  color: var(--ink);
}

.hero {
  margin-top: 40px;
}

.hero h1 {
  font-size: 30px;
  font-weight: 400;
  letter-spacing: -0.02em;
  color: var(--ink);
}

.compose {
  margin-top: 44px;
}

.form-row {
  padding: 22px 0;
  border-top: 1px solid var(--rule);
}

.form-row:last-of-type {
  border-bottom: 1px solid var(--rule);
}

.label {
  display: block;
  margin-bottom: 14px;
  font-size: var(--t-xs);
  color: var(--muted);
}

.types {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2);
}

.type {
  padding: 8px 18px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  font-size: var(--t-sm);
  color: var(--muted);
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease),
    background var(--dur) var(--ease);
}

.type:hover {
  color: var(--ink);
  border-color: var(--rule-strong);
}

.type[aria-pressed='true'] {
  color: var(--signal);
  border-color: var(--signal-line);
  background: var(--signal-soft);
}

textarea {
  display: block;
  width: 100%;
  min-height: 148px;
  padding: 0;
  border: 0;
  outline: 0;
  background: transparent;
  font: inherit;
  font-size: var(--t-md);
  line-height: 1.8;
  color: var(--ink);
  resize: vertical;
}

textarea::placeholder,
.contact::placeholder {
  color: var(--muted-soft);
}

.count {
  margin-top: var(--s2);
  font-size: var(--t-xs);
  color: var(--muted-soft);
  text-align: right;
}

.shot-row {
  display: flex;
  align-items: center;
  gap: var(--s4);
  min-height: 56px;
}

.thumb {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
}

.shot-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--t-sm);
  color: var(--ink-soft);
}

.hint {
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

.contact {
  width: 100%;
  padding: 0 0 4px;
  border: 0;
  outline: 0;
  background: transparent;
  font: inherit;
  font-size: var(--t-md);
  color: var(--ink);
}

.caution {
  max-width: 620px;
  margin-top: var(--s4);
  font-size: var(--t-xs);
  line-height: var(--lh-body);
  color: var(--muted-soft);
}

.actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s4);
  margin-top: var(--s5);
}

.subtle {
  padding: 4px 0;
  font-size: var(--t-sm);
  color: var(--signal);
}

.subtle:hover {
  color: var(--signal-deep);
}

.submit {
  padding: 10px 28px;
  border-radius: var(--r-pill);
  background: var(--signal);
  font-size: var(--t-sm);
  color: var(--on-signal);
  white-space: nowrap;
  transition: opacity var(--dur) var(--ease);
}

.submit:hover:not(:disabled) {
  opacity: 0.88;
}

.submit:disabled {
  opacity: 0.5;
}

.done-panel {
  margin-top: 44px;
  padding: var(--s6) 0;
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);
}

.done-panel h2 {
  font-size: var(--t-xl);
  font-weight: 400;
  color: var(--ink);
}

.done-panel .subtle {
  margin-top: var(--s5);
}

/* 历史是回看区：标题克制，正文不抢表单 */
.mine {
  margin-top: var(--s7);
}

.mine-head {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--rule);
}

.mine-head h2 {
  font-size: var(--t-lg);
  font-weight: 500;
  color: var(--ink);
}

.mine-count {
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

.empty,
.more {
  margin-top: var(--s4);
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

.rec {
  padding: var(--s5) 0;
  border-bottom: 1px solid var(--rule);
}

.rec-top {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
}

.kind {
  font-size: var(--t-sm);
  color: var(--ink);
}

.state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--t-xs);
  color: var(--muted);
}

.state::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}

.state[data-s='1'] {
  color: var(--signal);
}

.state[data-s='2'] {
  color: var(--done);
}

.state[data-s='3'] {
  color: var(--muted-soft);
}

.time {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--muted-soft);
}

.rec-body {
  margin-top: var(--s3);
  font-size: var(--t-sm);
  line-height: var(--lh-body);
  color: var(--ink-soft);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.rec-shot {
  display: block;
  max-width: 240px;
  max-height: 160px;
  margin-top: var(--s3);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  object-fit: cover;
}

.rec-reply {
  margin-top: var(--s4);
  padding: var(--s3) var(--s4);
  border-left: 2px solid var(--signal-line);
  background: var(--signal-soft);
  font-size: var(--t-xs);
  line-height: var(--lh-body);
  color: var(--ink-soft);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.who {
  margin-right: var(--s2);
  color: var(--muted);
}

@media (max-width: 720px) {
  .feedback {
    padding: var(--s5) var(--s4) var(--s6);
  }

  .hero {
    margin-top: var(--s5);
  }

  .hero h1 {
    font-size: 24px;
  }

  .actions {
    flex-direction: column;
    align-items: stretch;
  }

  .submit {
    align-self: flex-end;
  }
}
</style>
