<script setup lang="ts">
/**
 * 全部事项，按日期分组。与「今天」的区别：这里回答「我排过什么」，
 * 所以按时间倒序（最近的在上），并且允许分页往前翻。
 *
 * 分组的日期取哪一列取决于类型：日程看 startTime，待办看 dueTime。
 * 两者都没值的归到「没排时间」，放在最后——它们不是历史，是待整理的。
 */
import { computed, ref, watch } from 'vue'
import {
  batchCompleteItems,
  batchDeleteItems,
  completeItem,
  deleteItem,
  pageItems,
} from '../../api/schedule'
import type { Item } from '../../core/types'
import { parseDateTime, relativeDayText, timeText, toDateString } from '../../core/date'
import { toast } from '../../core/store'

const props = defineProps<{ epoch: number }>()
const emit = defineEmits<{ (e: 'edit', id: number): void }>()

type Filter = 'all' | 'schedule' | 'todo' | 'overdue'

const filter = ref<Filter>('all')
const items = ref<Item[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)

/** 批量模式：只在需要「一次处理好几条」时才出现，平时不占一列、也不改行的点击语义 */
const picking = ref(false)
const chosen = ref<number[]>([])
const applying = ref(false)

const size = 50
const canMore = computed(() => items.value.length < total.value)

const FILTERS: Array<{ key: Filter; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'schedule', label: '日程' },
  { key: 'todo', label: '待办' },
  { key: 'overdue', label: '逾期' },
]

async function load(reset = true) {
  if (loading.value) return
  loading.value = true
  if (reset) page.value = 1

  try {
    const query =
      filter.value === 'overdue'
        ? { overdue: true as const, pageNum: page.value, pageSize: size }
        : {
            itemType: filter.value === 'all' ? undefined : filter.value,
            pageNum: page.value,
            pageSize: size,
          }

    const result = await pageItems(query)
    items.value = reset ? result.records : [...items.value, ...result.records]
    total.value = result.total
  } catch (error) {
    toast(error instanceof Error ? error.message : '列表没取到', 'error')
  } finally {
    loading.value = false
  }
}

function pick(key: Filter) {
  if (filter.value === key) return
  filter.value = key
  void load(true)
}

function more() {
  page.value += 1
  void load(false)
}

function whenOf(item: Item): Date | null {
  return parseDateTime(item.itemType === 'todo' ? item.dueTime : item.startTime)
}

interface Group {
  key: string
  label: string
  rows: Item[]
}

const groups = computed<Group[]>(() => {
  const map = new Map<string, Group>()

  for (const item of items.value) {
    const date = whenOf(item)
    const key = date ? toDateString(date) : 'none'
    const label = date ? relativeDayText(date) : '没排时间'
    const bucket = map.get(key) ?? { key, label, rows: [] }
    bucket.rows.push(item)
    map.set(key, bucket)
  }

  // 没排时间的永远排最后：它不是「更早」，而是「还没决定」
  return [...map.values()].sort((a, b) => {
    if (a.key === 'none') return 1
    if (b.key === 'none') return -1
    return b.key.localeCompare(a.key)
  })
})

function timeOf(item: Item): string {
  const date = whenOf(item)
  if (!date) return '—'
  if (item.allDay === 1) return '全天'
  return item.itemType === 'todo' ? `截 ${timeText(date)}` : timeText(date)
}

function isOverdue(item: Item): boolean {
  if (item.itemType !== 'todo' || item.status !== 0) return false
  const date = parseDateTime(item.dueTime)
  return date ? date.getTime() < Date.now() : false
}

async function toggle(item: Item) {
  try {
    await completeItem(item.id, item.status !== 1)
    await load(true)
  } catch (error) {
    toast(error instanceof Error ? error.message : '没改成', 'error')
  }
}

async function remove(item: Item) {
  try {
    await deleteItem(item.id)
    await load(true)
  } catch (error) {
    toast(error instanceof Error ? error.message : '没删掉', 'error')
  }
}

function startPick() {
  picking.value = true
  chosen.value = []
}

function stopPick() {
  picking.value = false
  chosen.value = []
}

function togglePick(id: number) {
  const set = new Set(chosen.value)
  if (set.has(id)) set.delete(id)
  else set.add(id)
  chosen.value = [...set]
}

const allPicked = computed(
  () => items.value.length > 0 && items.value.every((i) => chosen.value.includes(i.id)),
)

function toggleAll() {
  chosen.value = allPicked.value ? [] : items.value.map((i) => i.id)
}

/**
 * 批量完成 / 批量删除。
 *
 * 后端逐条回报，所以界面不能说「成功」了事：失败的那几条要报出条数，
 * 成功与否都要重取列表——逐条成功也可能只成功了一部分。
 */
async function applyBatch(kind: 'complete' | 'delete') {
  if (!chosen.value.length || applying.value) return
  applying.value = true
  try {
    const result =
      kind === 'complete'
        ? await batchCompleteItems(chosen.value, true)
        : await batchDeleteItems(chosen.value)

    if (result.failed.length) {
      toast(`${result.successCount} 条已处理，${result.failed.length} 条没成`, 'error')
    } else {
      toast(kind === 'complete' ? `已完成 ${result.successCount} 条` : `已删除 ${result.successCount} 条`)
    }
    stopPick()
    await load(true)
  } catch (error) {
    toast(error instanceof Error ? error.message : '批量操作失败', 'error')
  } finally {
    applying.value = false
  }
}

watch(() => props.epoch, () => void load(true), { immediate: true })
</script>

<template>
  <div class="list" :data-pick="picking">
    <nav class="filters" aria-label="筛选">
      <button
        v-for="item in FILTERS"
        :key="item.key"
        type="button"
        :aria-current="filter === item.key"
        @click="pick(item.key)"
      >
        {{ item.label }}
      </button>
      <span class="pad"></span>
      <button class="pickmode" type="button" @click="picking ? stopPick() : startPick()">
        {{ picking ? '退出批量' : '批量' }}
      </button>
    </nav>

    <p v-if="loading && !items.length" class="empty">正在取…</p>

    <p v-else-if="!items.length" class="empty">
      <template v-if="filter === 'overdue'">没有逾期的事，这一栏可以不用管。</template>
      <template v-else-if="filter === 'todo'">还没有待办。跟它说「提醒我交房租」，就会出现在这里。</template>
      <template v-else-if="filter === 'schedule'">还没有日程。跟它说「明天下午三点项目评审」试试。</template>
      <template v-else>还没有任何事项。</template>
    </p>

    <template v-else>
      <section v-for="group in groups" :key="group.key" class="group">
        <h3 class="label">{{ group.label }}</h3>
        <ul>
          <li
            v-for="item in group.rows"
            :key="item.id"
            class="row"
            :data-done="item.status === 1"
            :data-picked="chosen.includes(item.id)"
            @click="picking ? togglePick(item.id) : emit('edit', item.id)"
          >
            <span v-if="picking" class="pick" :data-on="chosen.includes(item.id)" aria-hidden="true"></span>
            <span class="when mono" :data-overdue="isOverdue(item)">{{ timeOf(item) }}</span>

            <button
              v-if="item.itemType === 'todo'"
              class="check"
              type="button"
              :aria-label="`切换「${item.title}」的完成状态`"
              :data-on="item.status === 1"
              @click.stop="toggle(item)"
            ></button>
            <span v-else class="kind" aria-hidden="true"></span>

            <div class="content">
              <p class="title">{{ item.title }}</p>
              <p v-if="item.seriesId" class="sub">第 {{ item.seriesIndex }} 次</p>
              <p v-else-if="item.location" class="sub">{{ item.location }}</p>
            </div>

            <button v-if="!picking" class="kill" type="button" title="删除" @click.stop="remove(item)">×</button>
            <span v-else class="kill-space" aria-hidden="true"></span>
          </li>
        </ul>
      </section>

      <button v-if="canMore" class="btn btn-quiet more" type="button" :disabled="loading" @click="more">
        {{ loading ? '正在取…' : `再看 ${Math.min(size, total - items.length)} 条` }}
      </button>
    </template>

    <!-- 批量条贴在列表底部：滚动时始终够得着，不随列表长度跑掉 -->
    <div v-if="picking" class="batchbar">
      <button class="all" type="button" @click="toggleAll">{{ allPicked ? '取消全选' : '全选' }}</button>
      <span class="count">已选 {{ chosen.length }}</span>
      <span class="pad"></span>
      <button type="button" :disabled="!chosen.length || applying" @click="applyBatch('complete')">
        {{ applying ? '处理中…' : '完成' }}
      </button>
      <button class="danger" type="button" :disabled="!chosen.length || applying" @click="applyBatch('delete')">
        删除
      </button>
    </div>
  </div>
</template>

<style scoped>
.list {
  padding: var(--s3) var(--s4) var(--s6);
}

.filters {
  display: flex;
  gap: var(--s3);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--rule);
  margin-bottom: var(--s3);
}

.filters button {
  font-size: var(--t-xs);
  color: var(--muted);
  padding: 2px 0;
  border-bottom: 1px solid transparent;
}

.filters button[aria-current='true'] {
  color: var(--ink);
  border-bottom-color: var(--ink);
}

.pad {
  flex: 1;
}

.pickmode {
  color: var(--muted);
}

.pickmode:hover {
  color: var(--signal);
}

/* 批量模式多一列勾选：只在 data-pick 时占位，平时行宽不变 */
.list[data-pick='true'] .row {
  grid-template-columns: 16px 46px 12px minmax(0, 1fr) 18px;
}

.pick {
  width: 11px;
  height: 11px;
  margin-top: 4px;
  border: 1.5px solid var(--rule-strong);
  border-radius: 3px;
}

.pick[data-on='true'] {
  background: var(--signal);
  border-color: var(--signal);
}

.row {
  cursor: pointer;
}

.row[data-picked='true'] {
  background: var(--signal-soft);
}

.kill-space {
  width: 18px;
}

.batchbar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: var(--s3);
  margin-top: var(--s4);
  padding: 9px 2px;
  background: var(--paper);
  border-top: 1px solid var(--rule);
  font-size: var(--t-xs);
}

.batchbar button {
  padding: 4px 10px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  color: var(--muted);
  background: var(--paper);
}

.batchbar button:hover:not(:disabled) {
  color: var(--ink);
  border-color: var(--rule-strong);
}

.batchbar .all {
  border-color: transparent;
  padding-left: 0;
}

.batchbar .danger:hover:not(:disabled) {
  color: var(--alert);
  border-color: var(--alert);
}

.batchbar button:disabled {
  opacity: 0.45;
}

.batchbar .count {
  color: var(--muted);
}

.group + .group {
  margin-top: var(--s4);
}

.label {
  font-size: var(--t-xs);
  font-weight: 500;
  color: var(--muted);
  padding-bottom: 4px;
}

.row {
  display: grid;
  grid-template-columns: 46px 12px minmax(0, 1fr) 18px;
  align-items: start;
  padding: 6px 0;
  border-bottom: 1px solid var(--rule);
}

.row:last-child {
  border-bottom: none;
}

.when {
  font-size: var(--t-xs);
  color: var(--muted);
  padding-top: 1px;
}

.when[data-overdue='true'] {
  color: var(--alert);
}

.check {
  width: 11px;
  height: 11px;
  margin-top: 4px;
  border: 1.5px solid var(--rule-strong);
  border-radius: 50%;
}

.check[data-on='true'] {
  background: var(--signal);
  border-color: var(--signal);
}

.kind {
  width: 1px;
  height: 12px;
  margin-top: 4px;
  margin-left: 5px;
  background: var(--rule-strong);
}

.content {
  padding-left: 9px;
  min-width: 0;
}

.title {
  font-size: var(--t-sm);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.row[data-done='true'] .title {
  color: var(--muted);
  text-decoration: line-through;
}

.sub {
  font-size: var(--t-xs);
  color: var(--muted);
}

.kill {
  font-size: var(--t-md);
  color: var(--muted-soft);
  line-height: 1;
  opacity: 0;
}

.row:hover .kill,
.row:focus-within .kill {
  opacity: 1;
}

.kill:hover {
  color: var(--alert);
}

.more {
  margin-top: var(--s4);
  width: 100%;
  justify-content: center;
  border-color: var(--rule);
}
</style>
