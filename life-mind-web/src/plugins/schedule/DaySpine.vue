<script setup lang="ts">
/**
 * 一天的时间脊柱。这是整个界面唯一「用力」的地方，其余都安静。
 *
 * 为什么不是 24 小时刻度表：一天里真正有内容的往往只有五六个时刻，
 * 画满 18 个刻度会让 80% 的行是空的，信息密度反而低。
 * 所以脊柱只连接「实际发生的事」，表达的是先后顺序，不是钟面。
 *
 * 时刻列用等宽字体右对齐——这是一天里唯一需要纵向对齐的数据，
 * 字宽不一致会让 09:00 和 15:00 左右错开，扫视时很难对齐。
 */
import { computed, ref, watch } from 'vue'
import { pageItems, overview, completeItem, deleteItem } from '../../api/schedule'
import type { Item, Overview } from '../../core/types'
import { dayText, offsetText, parseDateTime, timeText, toDateString } from '../../core/date'
import { toast } from '../../core/store'

const props = defineProps<{ date: string; epoch: number }>()

const schedules = ref<Item[]>([])
const todos = ref<Item[]>([])
const summary = ref<Overview | null>(null)
const loading = ref(false)

const today = toDateString(new Date())
const isToday = computed(() => props.date === today)
const heading = computed(() => {
  const parsed = parseDateTime(props.date)
  return parsed ? dayText(parsed) : props.date
})

const allDay = computed(() => schedules.value.filter((i) => i.allDay === 1))
const timed = computed(() =>
  schedules.value
    .filter((i) => i.allDay !== 1 && i.startTime)
    // 时间串同格式，直接按字符串排序即可，不必解成 Date 再比
    .sort((a, b) => (a.startTime ?? '').localeCompare(b.startTime ?? '')),
)

const overdueCount = computed(() => {
  const now = Date.now()
  return todos.value.filter((t) => {
    const due = parseDateTime(t.dueTime)
    return due ? due.getTime() < now : false
  }).length
})

function isOverdue(todo: Item): boolean {
  const due = parseDateTime(todo.dueTime)
  return due ? due.getTime() < Date.now() : false
}

function clockOf(item: Item): string {
  return timeText(parseDateTime(item.startTime))
}

/** 副行优先显示正文摘要，其次地点，最后提醒——三者同时塞进去会挤成三行 */
function subOf(item: Item): string {
  if (item.content) return item.content
  if (item.location) return item.location
  const offsets = item.remindOffsets ?? []
  return offsets.length ? offsets.map(offsetText).join('、') : ''
}

async function load() {
  loading.value = true
  try {
    const [day, pending, stats] = await Promise.all([
      pageItems({ date: props.date, itemType: 'schedule', pageSize: 100 }),
      // 待办不按日期取：它们没有「必须安排在几点」，只按截止时间挂在今天并不合理，
      // 「今天」这一屏要看到的是「今天还能做掉哪些」
      pageItems({ itemType: 'todo', status: 0, pageSize: 100 }),
      isToday.value ? overview() : Promise.resolve(null),
    ])
    schedules.value = day.records
    todos.value = pending.records
    summary.value = stats
  } catch (error) {
    toast(error instanceof Error ? error.message : '日程没取到', 'error')
  } finally {
    loading.value = false
  }
}

async function toggle(todo: Item) {
  try {
    await completeItem(todo.id, todo.status !== 1)
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '没改成', 'error')
  }
}

async function remove(item: Item) {
  try {
    await deleteItem(item.id)
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '没删掉', 'error')
  }
}

watch(() => [props.date, props.epoch], load, { immediate: true })
</script>

<template>
  <div class="spine">
    <header class="head">
      <h2 class="day">{{ isToday ? '今天' : heading }}</h2>
      <p class="meta">
        <template v-if="isToday">{{ heading }} · </template>
        {{ schedules.length }} 项日程 · {{ todos.length }} 个待办
        <!-- 后端给的就是 0~100 的百分数，不是 0~1，别再乘 100 -->
        <template v-if="summary"> · 本周完成 {{ Math.round(summary.weekCompletionRate ?? 0) }}%</template>
      </p>
    </header>

    <p v-if="loading" class="empty">正在取这一天的安排…</p>

    <template v-else>
      <p v-if="!schedules.length && !todos.length" class="empty">
        这一天还是空的。在对话里说一句「{{ isToday ? '今天下午三点开会' : heading + '下午三点开会' }}」就会出现在这里，提醒也会一起排上。
      </p>

      <ul v-if="allDay.length" class="allday">
        <li v-for="item in allDay" :key="item.id">
          <span class="allday-tag">全天</span>
          <span class="title">{{ item.title }}</span>
        </li>
      </ul>

      <!-- 脊柱本体：一条竖线贯穿，节点落在线上 -->
      <ol v-if="timed.length" class="axis">
        <li v-for="item in timed" :key="item.id" class="slot">
          <span class="clock mono">{{ clockOf(item) }}</span>
          <span class="dot" aria-hidden="true"></span>
          <div class="content">
            <p class="title">{{ item.title }}</p>
            <p v-if="subOf(item)" class="sub">{{ subOf(item) }}</p>
          </div>
          <button class="kill" type="button" title="删除" @click="remove(item)">×</button>
        </li>
      </ol>

      <section v-if="todos.length" class="todos">
        <h3 class="sec">
          待办
          <span v-if="overdueCount" class="overdue">{{ overdueCount }} 个已逾期</span>
        </h3>
        <ul>
          <li v-for="todo in todos" :key="todo.id" class="todo" :data-overdue="isOverdue(todo)">
            <button class="check" type="button" :aria-label="`完成 ${todo.title}`" @click="toggle(todo)" />
            <div class="content">
              <p class="title">{{ todo.title }}</p>
              <p v-if="todo.dueTime" class="sub">截止 {{ todo.dueTime.slice(5, 16) }}</p>
            </div>
            <button class="kill" type="button" title="删除" @click="remove(todo)">×</button>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.spine {
  padding: var(--s4) var(--s4) var(--s6);
}

.head {
  margin-bottom: var(--s4);
}

.day {
  font-size: var(--t-lg);
  letter-spacing: -0.01em;
}

.meta {
  margin-top: 2px;
  font-size: var(--t-xs);
  color: var(--muted);
}

.allday {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s2);
  margin-bottom: var(--s3);
}

.allday li {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--t-sm);
}

.allday-tag {
  font-size: var(--t-xs);
  color: var(--muted);
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  padding: 0 4px;
}

/* 竖线画在容器上而不是每个 li 上：li 之间不会被行高撑出断口 */
.axis {
  position: relative;
  margin: var(--s2) 0 var(--s5);
}

.axis::before {
  content: '';
  position: absolute;
  /* 必须等于节点圆心横坐标：时刻列 52 + 圆点 margin-left 2 + 半径 3.5 ≈ 57.5。
     写成 63（第二列右边界）会让线偏在圆点右侧，看起来像两个不同的东西 */
  left: 57px;
  top: 16px;
  bottom: 18px;
  width: 1px;
  background: var(--spine);
}

.slot {
  position: relative;
  display: grid;
  grid-template-columns: 52px 12px minmax(0, 1fr) 18px;
  align-items: start;
  gap: 0;
  padding: 6px 0;
}

.clock {
  font-size: var(--t-sm);
  color: var(--ink-soft);
  text-align: right;
  padding-right: 10px;
  line-height: 1.5;
}

.dot {
  width: 7px;
  height: 7px;
  margin-top: 7px;
  margin-left: 2px;
  border-radius: 50%;
  background: var(--paper);
  border: 1.5px solid var(--signal);
  position: relative;
  z-index: 1;
}

.content {
  padding-left: var(--s3);
  min-width: 0;
}

.title {
  font-size: var(--t-sm);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.sub {
  margin-top: 1px;
  font-size: var(--t-xs);
  color: var(--muted);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 删除只在悬停/聚焦时出现：常驻的 × 会让每一行都像一个待处理的错误 */
.kill {
  font-size: var(--t-md);
  color: var(--muted-soft);
  line-height: 1;
  padding: 2px;
  opacity: 0;
  transition: opacity var(--dur) var(--ease), color var(--dur) var(--ease);
}

.slot:hover .kill,
.slot:focus-within .kill,
.todo:hover .kill,
.todo:focus-within .kill {
  opacity: 1;
}

.kill:hover {
  color: var(--alert);
}

.sec {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  font-size: var(--t-xs);
  font-weight: 500;
  color: var(--muted);
  padding-bottom: var(--s2);
  margin-bottom: var(--s2);
  border-bottom: 1px solid var(--rule);
}

.overdue {
  color: var(--alert);
}

.todo {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr) 18px;
  align-items: start;
  gap: 0;
  padding: 5px 0;
}

.check {
  width: 11px;
  height: 11px;
  margin-top: 5px;
  border: 1.5px solid var(--rule-strong);
  border-radius: 50%;
  transition: border-color var(--dur) var(--ease);
}

.check:hover {
  border-color: var(--signal);
}

.todo .content {
  padding-left: 11px;
}

.todo[data-overdue='true'] .sub {
  color: var(--alert);
}
</style>
