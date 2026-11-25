<script setup lang="ts">
/**
 * 通知页。与「对话 + 工作区」「插件服务库」并列的第三个顶层界面，由左栏功能区块进入；
 * 和右下角弹窗是两回事：弹窗只管「到点打断一下」，这里是主动回看全部通知的地方。
 * 版式按整页设计：宽屏内容居中、左右留白充足，窄屏收成单栏。
 *
 * 未读筛选在前端做——后端 page 接口支持 isRead 参数，但列表只有 30 条，
 * 切筛选时重新请求反而会丢掉刚标已读的本地状态；一页数据量小，本地筛更稳。
 */
import { computed, ref } from 'vue'
import { backToChat, loadNoticeList, readAllNotices, readNotice, state } from '../core/store'
import ChevronIcon from '../components/ChevronIcon.vue'

const filter = ref<'all' | 'unread'>('all')

const items = computed(() =>
  filter.value === 'unread'
    ? state.noticeList.filter((n) => n.isRead === 0)
    : state.noticeList,
)

function typeText(type: string): string {
  const map: Record<string, string> = {
    reminder: '日程提醒',
    system: '系统通知',
    feedback: '反馈回复',
    like: '点赞',
    comment: '评论',
    reply: '回复',
    follow: '关注',
  }
  return map[type] ?? '通知'
}

function timeText(raw: string | null): string {
  if (!raw) return ''
  const d = new Date(raw.replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
</script>

<template>
  <section class="notice-page">
    <div class="inner">
      <header class="head">
        <button class="back" type="button" @click="backToChat">
          <ChevronIcon dir="left" :size="13" />回到对话
        </button>
        <div>
          <h1>通知</h1>
          <p class="lede">日程提醒、反馈回复和系统消息都在这里。</p>
        </div>
      </header>

      <div class="subhead">
        <p v-if="state.noticeUnread" class="lede">{{ state.noticeUnread }} 条未读</p>
        <p v-else class="lede">都读完了</p>
        <button
          v-if="state.noticeUnread"
          class="btn btn-quiet"
          type="button"
          @click="readAllNotices"
        >
          全部标为已读
        </button>
      </div>

      <nav class="filter" aria-label="通知筛选">
        <button type="button" :data-on="filter === 'all'" @click="filter = 'all'">全部</button>
        <button type="button" :data-on="filter === 'unread'" @click="filter = 'unread'">
          未读
          <span v-if="state.noticeUnread" class="count">{{ state.noticeUnread }}</span>
        </button>
        <button class="reload" type="button" :disabled="state.noticeListLoading" @click="loadNoticeList">
          {{ state.noticeListLoading ? '刷新中…' : '刷新' }}
        </button>
      </nav>

      <p v-if="state.noticeListLoading && !state.noticeList.length" class="empty">加载中…</p>
      <p v-else-if="!items.length" class="empty">
        {{ filter === 'unread' ? '没有未读通知。' : '还没有通知。' }}
      </p>

      <ul v-else class="list">
        <li v-for="item in items" :key="item.id" :data-unread="item.isRead === 0">
          <button type="button" @click="readNotice(item.id)">
            <span class="row-top">
              <span class="meta">
                <span class="type">{{ typeText(item.type) }}</span>
                <span class="dot" aria-hidden="true">·</span>
                <span class="time">{{ timeText(item.createTime) }}</span>
              </span>
              <span v-if="item.isRead === 0" class="flag">未读</span>
            </span>
            <span class="title">{{ item.title }}</span>
            <span v-if="item.content" class="content">{{ item.content }}</span>
          </button>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.notice-page {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--s6) var(--s6) var(--s7);
  background: var(--paper);
}

.inner {
  width: 100%;
  max-width: 980px;
  margin: 0 auto;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  flex-wrap: wrap;
  gap: var(--s5);
}

.head h1 {
  font-size: 21px;
  font-weight: 500;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.lede {
  margin-top: 7px;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--muted);
}

.back {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex: 0 0 auto;
  padding: 7px 14px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  font-size: var(--t-xs);
  color: var(--muted);
  white-space: nowrap;
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease);
}

.back:hover {
  color: var(--ink);
  border-color: var(--rule-strong);
}

.subhead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--s3);
  margin-top: var(--s5);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--rule);
}

.subhead .lede {
  margin-top: 0;
}

.subhead .btn-quiet {
  padding: 6px 12px;
  font-size: var(--t-xs);
}

.filter {
  display: flex;
  align-items: center;
  gap: var(--s4);
  padding: var(--s3) 0;
  border-bottom: 1px solid var(--rule);
}

.filter button {
  padding: 2px 0;
  font-size: var(--t-sm);
  color: var(--muted);
}

.filter button[data-on='true'] {
  color: var(--ink);
  font-weight: 500;
}

.filter .count {
  margin-left: 3px;
  color: var(--signal);
}

.filter .reload {
  margin-left: auto;
  font-size: var(--t-xs);
}

.filter button:disabled {
  opacity: 0.5;
  cursor: default;
}

.empty {
  padding: var(--s7) 0;
  font-size: var(--t-sm);
  color: var(--muted-soft);
  text-align: center;
}

.list li {
  border-bottom: 1px solid var(--rule);
}

.list li[data-unread='true'] {
  background: var(--signal-soft);
}

.list button {
  display: block;
  width: 100%;
  padding: 14px 12px;
  text-align: left;
  transition: background var(--dur) var(--ease);
}

.list button:hover {
  background: var(--paper-sunken);
}

.row-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s2);
}

.meta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

.type {
  color: var(--signal);
}

.flag {
  font-size: var(--t-xs);
  color: var(--signal);
}

.title {
  display: block;
  margin-top: 5px;
  font-size: var(--t-sm);
  font-weight: 500;
  color: var(--ink);
}

.content {
  display: block;
  margin-top: 4px;
  font-size: var(--t-xs);
  line-height: 1.6;
  color: var(--muted);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 720px) {
  .notice-page {
    padding: var(--s5) var(--s4);
  }

  .head {
    flex-direction: column;
    gap: var(--s3);
  }
}
</style>
