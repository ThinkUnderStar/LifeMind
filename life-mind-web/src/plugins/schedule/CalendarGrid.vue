<script setup lang="ts">
/**
 * 月历视图。整月事项只查一次（超过 100 条按后端上限续页），
 * 再把事项按日期归到格子里——格子上的短条和下方明细用的是同一份数据，
 * 不会出现「格子上标了 3 条、点开只有 2 条」。
 *
 * 口径与后端 /item/page 对齐：日程挂在 startTime 那天，待办挂在 dueTime 那天。
 * 没有截止时间、或跨天日程的后续日子都不落格子；跨天的结束时间仍在明细里能看到。
 *
 * 这里不调 /statistics/daily：那个接口排除已取消、口径与列表不同，混用会让
 * 格子上的数和点开后的条数对不上。
 *
 * 颜色语义（用户口径）：待办黄、已完成的绿、普通日程灰墨；有未完成的逾期待办
 * 那天整格标黄。今天用实心圆 + 浅底高亮，选中仍用更深的底，二者不抢同一档。
 */
import { computed, ref, watch } from 'vue'
import { completeItem, pageItems } from '../../api/schedule'
import type { Item } from '../../core/types'
import { addDays, dayText, parseDateTime, startOfWeek, timeText, toDateString } from '../../core/date'
import { toast } from '../../core/store'
import ChevronIcon from '../../components/ChevronIcon.vue'

const props = defineProps<{ date: string; epoch: number }>()
const emit = defineEmits<{
  (e: 'edit', id: number): void
  (e: 'create', date: string): void
}>()

const today = toDateString(new Date())
/** 当前显示的月份，YYYY-MM。与选中的日期分开：翻月不等于选中某天 */
const cursor = ref(props.date.slice(0, 7))
const selected = ref<string | null>(props.date)
const items = ref<Item[]>([])
const loading = ref(false)
const busy = ref<number[]>([])

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

function dateOf(key: string): Date {
  const [y, m, d] = key.split('-').map(Number)
  return new Date(y, m - 1, d)
}

/** 后端排日期的口径：日程看开始时间，待办看截止时间 */
function keyOf(item: Item): string | null {
  const raw = item.itemType === 'schedule' ? item.startTime : item.dueTime
  const date = parseDateTime(raw)
  return date ? toDateString(date) : null
}

function rawTime(item: Item): string | null {
  return item.itemType === 'schedule' ? item.startTime : item.dueTime
}

const byDay = computed(() => {
  const map = new Map<string, Item[]>()
  for (const item of items.value) {
    const key = keyOf(item)
    if (!key) continue
    const list = map.get(key) ?? []
    list.push(item)
    map.set(key, list)
  }
  return map
})

/** 周一起始，补成整周；多数月份 5 行，少数 6 行 */
const cells = computed(() => {
  const [y, m] = cursor.value.split('-').map(Number)
  const first = new Date(y, m - 1, 1)
  const last = new Date(y, m, 0)
  const gridStart = startOfWeek(first)
  const gridEnd = addDays(startOfWeek(last), 6)
  const out: Array<{ key: string; day: number; inMonth: boolean }> = []
  for (let d = gridStart; d <= gridEnd; d = addDays(d, 1)) {
    out.push({ key: toDateString(d), day: d.getDate(), inMonth: d.getMonth() === m - 1 })
  }
  return out
})

const title = computed(() => {
  const [y, m] = cursor.value.split('-').map(Number)
  return `${y} 年 ${m} 月`
})

function isOverdue(item: Item): boolean {
  const due = parseDateTime(item.dueTime)
  return item.itemType === 'todo' && item.status === 0 && due ? due.getTime() < Date.now() : false
}

/** 这一天有没有未完成的逾期待办：整格标黄，比单看短条更容易扫到 */
function overdueOf(key: string): boolean {
  return (byDay.value.get(key) ?? []).some(isOverdue)
}

/** 格子里最多三条短条，多了用 +n 收口——面板只有 372px，塞满会变成马赛克 */
function marksOf(key: string) {
  const list = byDay.value.get(key) ?? []
  return list.slice(0, 3).map((item) => ({
    id: item.id,
    todo: item.itemType === 'todo',
    done: item.status === 1,
    cancelled: item.status === 2,
    overdue: isOverdue(item),
    title: item.title,
  }))
}

function extraOf(key: string): number {
  return Math.max((byDay.value.get(key)?.length ?? 0) - 3, 0)
}

function pick(key: string) {
  const month = key.slice(0, 7)
  if (month !== cursor.value) cursor.value = month
  selected.value = key
}

function shiftMonth(delta: number) {
  const [y, m] = cursor.value.split('-').map(Number)
  cursor.value = toDateString(new Date(y, m - 1 + delta, 1)).slice(0, 7)
  // 翻月后不预选：直接把别月的某天摊开在下方明细里反而让人以为点错了
  selected.value = null
  void load()
}

function goToday() {
  cursor.value = today.slice(0, 7)
  selected.value = today
  void load()
}

const detail = computed(() => {
  const key = selected.value
  if (!key) return null
  const list = byDay.value.get(key) ?? []
  const byTime = (a: Item, b: Item) =>
    (parseDateTime(rawTime(a))?.getTime() ?? 0) - (parseDateTime(rawTime(b))?.getTime() ?? 0)
  const ordered = [
    ...list.filter((i) => i.itemType === 'schedule').sort(byTime),
    ...list.filter((i) => i.itemType === 'todo').sort(byTime),
  ]
  return { text: dayText(dateOf(key)), list: ordered, total: list.length, key }
})

function clockOf(item: Item): string {
  if (item.itemType === 'schedule' && item.allDay === 1) return '全天'
  return timeText(parseDateTime(rawTime(item)))
}

/** 完成 / 取消完成。只改本地那一条的状态，不整月重拉：勾选不该让日历闪一下 */
async function toggle(item: Item) {
  if (busy.value.includes(item.id)) return
  busy.value.push(item.id)
  try {
    await completeItem(item.id, item.status !== 1)
    item.status = item.status === 1 ? 0 : 1
  } catch (error) {
    toast(error instanceof Error ? error.message : '没改成', 'error')
  } finally {
    busy.value = busy.value.filter((id) => id !== item.id)
  }
}

async function load() {
  loading.value = true
  try {
    const [y, m] = cursor.value.split('-').map(Number)
    const startDate = toDateString(new Date(y, m - 1, 1))
    const endDate = toDateString(new Date(y, m, 0))
    const first = await pageItems({ startDate, endDate, pageSize: 100, pageNum: 1 })
    const all = [...first.records]
    // 后端的 pageSize 封顶 100；一个月超过 100 条时按 total/pages 续页，
    // 否则日历后半个月会整片空白且没有任何提示
    const pages = first.pages ?? Math.ceil(first.total / 100)
    for (let page = 2; page <= pages; page += 1) {
      const more = await pageItems({ startDate, endDate, pageSize: 100, pageNum: page })
      all.push(...more.records)
    }
    items.value = all
  } catch (error) {
    toast(error instanceof Error ? error.message : '日历没取到', 'error')
  } finally {
    loading.value = false
  }
}

// 面板参数（agent 的 ui 帧）改了才重置选中；单纯刷新数据不动用户正在看的那天
watch(
  () => props.date,
  (date) => {
    cursor.value = date.slice(0, 7)
    selected.value = date
  },
)
watch(() => props.epoch, load, { immediate: true })
</script>

<template>
  <div class="calendar">
    <header class="month">
      <button class="arrow" type="button" title="上个月" @click="shiftMonth(-1)">
        <ChevronIcon dir="left" :size="15" />
      </button>
      <h2 class="label">{{ title }}</h2>
      <button class="arrow" type="button" title="下个月" @click="shiftMonth(1)">
        <ChevronIcon :size="15" />
      </button>
      <span class="pad"></span>
      <button class="today-btn" type="button" @click="goToday">今天</button>
    </header>

    <div class="weekdays" aria-hidden="true">
      <span v-for="w in WEEKDAYS" :key="w">{{ w }}</span>
    </div>

    <div class="grid" :data-loading="loading">
      <button
        v-for="cell in cells"
        :key="cell.key"
        class="cell"
        type="button"
        :data-out="!cell.inMonth"
        :data-today="cell.key === today"
        :data-overdue="overdueOf(cell.key)"
        :data-on="cell.key === selected"
        :title="`${cell.key} · ${(byDay.get(cell.key) ?? []).length} 项`"
        @click="pick(cell.key)"
      >
        <span class="num">{{ cell.day }}</span>
        <span class="marks">
          <i
            v-for="mark in marksOf(cell.key)"
            :key="mark.id"
            class="mark"
            :data-todo="mark.todo"
            :data-done="mark.done"
            :data-cancelled="mark.cancelled"
            :data-overdue="mark.overdue"
            :title="mark.title"
          ></i>
          <em v-if="extraOf(cell.key)" class="extra">+{{ extraOf(cell.key) }}</em>
        </span>
      </button>
    </div>

    <section v-if="detail" class="detail">
      <header class="detail-head">
        <h3>{{ detail.text }}</h3>
        <span>{{ detail.total }} 项</span>
      </header>

      <p v-if="!detail.list.length" class="empty">
        这天还没有安排。点右下角在这天新建，或者回对话里说一句。
      </p>

      <ul v-else class="entries">
        <li
          v-for="item in detail.list"
          :key="item.id"
          class="entry"
          :data-todo="item.itemType === 'todo'"
          :data-done="item.status === 1"
          :data-overdue="isOverdue(item)"
          :data-cancelled="item.status === 2"
        >
          <span class="clock mono">{{ clockOf(item) }}</span>
          <span class="kind" :data-todo="item.itemType === 'todo'" aria-hidden="true"></span>
          <button class="open" type="button" @click="emit('edit', item.id)">
            <span class="title">{{ item.title }}</span>
            <span v-if="item.content || item.location" class="sub">{{ item.content || item.location }}</span>
            <span v-if="item.endTime && item.itemType === 'schedule'" class="sub">至 {{ item.endTime.slice(11, 16) }}</span>
          </button>
          <button
            v-if="item.itemType === 'todo' && item.status !== 2"
            class="check"
            type="button"
            :data-done="item.status === 1"
            :aria-label="`${item.status === 1 ? '取消完成' : '完成'} ${item.title}`"
            :title="item.status === 1 ? '取消完成' : '完成'"
            @click="toggle(item)"
          >{{ item.status === 1 ? '✓' : '' }}</button>
          <span v-else-if="item.status === 2" class="cancelled">已取消</span>
        </li>
      </ul>

      <button class="add-here" type="button" @click="emit('create', detail.key)">＋ 在这天新建</button>
    </section>
    <p v-else class="hint">点日历上的某一天，看那天的具体安排。</p>
  </div>
</template>

<style scoped>
.calendar {
  padding: var(--s4) var(--s4) var(--s6);
}

.month {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin-bottom: var(--s3);
}

.label {
  flex: 0 0 auto;
  min-width: 92px;
  font-size: var(--t-md);
  font-weight: 500;
  text-align: center;
}

.arrow {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: var(--r-sm);
  color: var(--muted);
}

.arrow:hover {
  background: var(--paper-sunken);
  color: var(--ink);
}

.pad {
  flex: 1;
}

.today-btn,
.add-here {
  font-size: var(--t-xs);
  color: var(--signal);
}

.today-btn:hover,
.add-here:hover {
  color: var(--ink);
}

.weekdays,
.grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
}

.weekdays {
  margin-bottom: var(--s1);
}

.weekdays span {
  padding-bottom: var(--s1);
  font-size: var(--t-xs);
  color: var(--muted-soft);
  text-align: center;
}

.grid {
  gap: 2px;
  transition: opacity var(--dur) var(--ease);
}

.grid[data-loading='true'] {
  opacity: 0.55;
}

/* 普通日期不铺底：整月 35 个色块会变成棋盘。今天、逾期日、选中日各有自己的一档 */
.cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  gap: 3px;
  min-width: 0;
  height: 50px;
  padding: 5px 1px 3px;
  border-radius: var(--r-lg);
  background: transparent;
  transition: background var(--dur) var(--ease);
}

.cell:hover:not([data-on='true']):not([data-today='true']):not([data-overdue='true']) {
  background: var(--paper-sunken);
}

.cell[data-today='true'] {
  background: var(--signal-soft);
}

.cell[data-overdue='true'] {
  background: var(--warn-soft);
}

/* 选中是交互焦点，压过今天和逾期；那两种身份由数字和短条继续表达 */
.cell[data-on='true'] {
  background: var(--row-active);
}

.cell[data-out='true'] {
  opacity: 0.34;
}

.num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 1px solid transparent;
  border-radius: 50%;
  font-size: var(--t-xs);
  line-height: 1;
  color: var(--ink-soft);
}

.cell[data-overdue='true'] .num {
  color: var(--warn-strong);
}

.cell[data-on='true'] .num {
  color: var(--ink);
  font-weight: 500;
}

/* 今天最高优先：实心墨绿圆 + 反白字，选中或标黄都盖不掉 */
.cell[data-today='true'] .num {
  background: var(--signal);
  border-color: var(--signal);
  color: var(--on-signal);
  font-weight: 500;
}

.marks {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 2px;
  width: 100%;
  min-height: 4px;
}

/* 普通日程灰墨、待办黄、完成绿、取消淡化；逾期待办用更深的黄 */
.mark {
  display: block;
  width: 10px;
  height: 3px;
  border-radius: var(--r-pill);
  background: var(--ink-soft);
}

.mark[data-todo='true'] {
  background: var(--warn);
}

.mark[data-overdue='true'] {
  background: var(--warn-strong);
}

.mark[data-done='true'] {
  background: var(--done);
  opacity: 1;
}

.mark[data-cancelled='true'] {
  background: var(--muted-soft);
  opacity: 0.22;
}

.extra {
  font-size: 9px;
  font-style: normal;
  line-height: 1;
  color: var(--muted);
}

.detail {
  margin-top: var(--s4);
  padding-top: var(--s3);
  border-top: 1px solid var(--rule);
}

.detail-head {
  display: flex;
  align-items: baseline;
  gap: var(--s2);
  margin-bottom: var(--s2);
}

.detail-head h3 {
  font-size: var(--t-sm);
  font-weight: 500;
}

.detail-head span,
.hint,
.empty {
  font-size: var(--t-xs);
  color: var(--muted);
}

.hint,
.empty {
  margin-top: var(--s2);
}

.entry {
  display: grid;
  grid-template-columns: 44px 8px minmax(0, 1fr) 14px;
  align-items: start;
  gap: 4px;
  padding: 6px 0;
  border-bottom: 1px solid var(--rule);
}

.entry:last-child {
  border-bottom: none;
}

/* 明细沿用格子的三色：待办黄、完成绿，未完成的逾期待办再深一档 */
.entry[data-todo='true'] .title {
  color: var(--warn);
}

.entry[data-done='true'] .title {
  color: var(--done);
}

.entry[data-overdue='true'] .title {
  color: var(--warn-strong);
}

.entry[data-cancelled='true'] .title {
  color: var(--muted-soft);
  text-decoration: line-through;
}

.clock {
  font-size: var(--t-xs);
  color: var(--muted);
  text-align: right;
  line-height: 1.6;
}

.kind {
  width: 6px;
  height: 6px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--ink-soft);
}

.kind[data-todo='true'] {
  border: 1px solid var(--warn);
  border-radius: var(--r-sm);
  background: transparent;
}

.entry[data-done='true'] .kind {
  background: var(--done);
  border-color: var(--done);
  border-radius: 50%;
}

.entry[data-overdue='true'] .kind {
  border-color: var(--warn-strong);
}

button.open {
  min-width: 0;
  border: 0;
  background: none;
  font: inherit;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

button.open:hover .title {
  color: var(--ink-soft);
}

.title {
  display: block;
  font-size: var(--t-sm);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.sub {
  display: block;
  margin-top: 1px;
  font-size: var(--t-xs);
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.check {
  width: 12px;
  height: 12px;
  margin-top: 5px;
  border: 1.5px solid var(--rule-strong);
  border-radius: 50%;
  font-size: 10px;
  line-height: 1;
  color: var(--on-signal);
}

.check:hover {
  border-color: var(--signal);
}

.check[data-done='true'] {
  background: var(--done);
  border-color: var(--done);
}

.cancelled {
  font-size: var(--t-xs);
  color: var(--muted-soft);
  line-height: 1.6;
}

.add-here {
  margin-top: var(--s3);
  padding: 0;
}
</style>
