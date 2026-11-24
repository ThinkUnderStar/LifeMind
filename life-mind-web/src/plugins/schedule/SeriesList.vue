<script setup lang="ts">
/**
 * 重复计划。这里列的不是「某一次健身」，而是「每周三健身」这条规则本身。
 * 所以显示的是规则、进度、以及终点——不是时间。
 *
 * 停用是不可逆的批量动作（会把之后每一次都删掉），所以做成两段式内联确认，
 * 不用浏览器 confirm：那个框会遮住整页、样式也不受控，而且用户点了才知道发生了什么。
 */
import { computed, ref, watch } from 'vue'
import { createSeries, deleteSeries, getSeries, listSeries, updateSeries } from '../../api/schedule'
import type { Series } from '../../core/types'
import { parseDateTime, timeText, toDateString } from '../../core/date'
import { toast } from '../../core/store'

const props = defineProps<{ epoch: number }>()

const rows = ref<Series[]>([])
const loading = ref(false)
const confirming = ref<number | null>(null)
const busy = ref(false)

/**
 * 编辑表单：undefined 收起、null 新建、数字编辑那一条。
 *
 * 只走 scope='all'：`future` 要 anchorItemId（从「哪一次」开始改），
 * 而列表是规则的视角，界面上没有「某一次」可选。按次修改留给对话里说
 * 「从下周三起改成八点」，那边有具体实例可锚定。
 */
const editingId = ref<number | null | undefined>(undefined)
const form = ref({
  itemType: 'schedule' as 'schedule' | 'todo',
  title: '',
  freq: 'WEEKLY' as 'DAILY' | 'WEEKLY' | 'MONTHLY',
  interval: 1,
  byDay: ['WE'] as string[],
  date: toDateString(new Date()),
  clock: '20:00',
  duration: '',
  countLimit: '',
  until: '',
  location: '',
})
const saving = ref(false)
const anchorText = ref('')

const DAY_TEXT: Record<string, string> = {
  MO: '周一',
  TU: '周二',
  WE: '周三',
  TH: '周四',
  FR: '周五',
  SA: '周六',
  SU: '周日',
}

const WEEKDAYS = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'] as const

const FREQS: Array<{ key: 'DAILY' | 'WEEKLY' | 'MONTHLY'; label: string }> = [
  { key: 'DAILY', label: '每天' },
  { key: 'WEEKLY', label: '每周' },
  { key: 'MONTHLY', label: '每月' },
]

/** 规则翻译成人话。字段名（freq / byDay / countLimit）不直接给用户看 */
function ruleText(series: Series): string {
  const step = series.interval ?? 1
  let base: string

  if (series.freq === 'DAILY') {
    base = step > 1 ? `每 ${step} 天` : '每天'
  } else if (series.freq === 'WEEKLY') {
    const days = (series.byDay ?? []).map((d) => DAY_TEXT[d] ?? d).join('、')
    base = step > 1 ? `每 ${step} 周的 ${days}` : `每${days || '周'}`
  } else {
    const day = parseDateTime(series.baseTime)
    base = step > 1 ? `每 ${step} 个月 ${day?.getDate() ?? ''} 号` : `每月 ${day?.getDate() ?? ''} 号`
  }

  const clock = timeText(parseDateTime(series.baseTime))
  const end = series.countLimit
    ? `共 ${series.countLimit} 次`
    : series.untilDate
      ? `到 ${series.untilDate}`
      : ''

  return [base, clock, end].filter(Boolean).join(' · ')
}

function statusText(series: Series): string {
  if (series.status === 1) return '已结束'
  if (series.status === 2) return '已暂停'
  return ''
}

async function load() {
  loading.value = true
  try {
    rows.value = await listSeries()
  } catch (error) {
    toast(error instanceof Error ? error.message : '重复计划没取到', 'error')
  } finally {
    loading.value = false
  }
}

async function stop(series: Series) {
  if (busy.value) return
  busy.value = true
  try {
    await deleteSeries({ id: series.id, scope: 'all' })
    confirming.value = null
    toast('已停用，之后不再生成新的日程')
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '没停掉', 'error')
  } finally {
    busy.value = false
  }
}

function startCreate() {
  editingId.value = null
  anchorText.value = ''
  form.value = {
    itemType: 'schedule',
    title: '',
    freq: 'WEEKLY',
    interval: 1,
    byDay: ['WE'],
    date: toDateString(new Date()),
    clock: '20:00',
    duration: '',
    countLimit: '',
    until: '',
    location: '',
  }
}

async function startEdit(series: Series) {
  editingId.value = series.id
  const start = parseDateTime(series.baseTime)
  form.value = {
    itemType: series.itemType,
    title: series.title,
    freq: series.freq,
    interval: series.interval ?? 1,
    byDay: series.byDay ?? [],
    date: start ? toDateString(start) : toDateString(new Date()),
    clock: start ? timeText(start) : '20:00',
    duration: series.durationMinutes ? String(series.durationMinutes) : '',
    countLimit: series.countLimit ? String(series.countLimit) : '',
    until: series.untilDate ?? '',
    location: series.location ?? '',
  }
  anchorText.value = ruleText(series)
  try {
    const fresh = await getSeries(series.id)
    if (editingId.value !== series.id) return
    form.value.title = fresh.title
    form.value.location = fresh.location ?? ''
  } catch {
    // 详情取不到就按列表里的值编辑
  }
}

function close() {
  editingId.value = undefined
  anchorText.value = ''
}

function toggleDay(day: string) {
  const set = new Set(form.value.byDay)
  if (set.has(day)) set.delete(day)
  else set.add(day)
  form.value.byDay = WEEKDAYS.filter((d) => set.has(d))
}

const canSave = computed(() => {
  if (!form.value.title.trim()) return false
  if (form.value.freq === 'WEEKLY' && form.value.byDay.length === 0) return false
  return true
})

async function save() {
  if (editingId.value === undefined || saving.value) return
  const value = form.value
  const title = value.title.trim()
  if (!title) return toast('先写个标题', 'error')
  if (value.freq === 'WEEKLY' && !value.byDay.length) return toast('每周重复要选至少一天', 'error')

  saving.value = true
  try {
    if (editingId.value === null) {
      await createSeries({
        itemType: value.itemType,
        title,
        location: value.itemType === 'schedule' ? value.location.trim() || undefined : undefined,
        freq: value.freq,
        interval: value.interval || undefined,
        byDay: value.freq === 'WEEKLY' ? value.byDay : undefined,
        // 锚点：日程是开始时刻，待办是截止时刻
        baseTime: `${value.date} ${value.clock}:00`,
        durationMinutes: Number(value.duration) || undefined,
        countLimit: Number(value.countLimit) || undefined,
        untilDate: value.until || undefined,
      })
      toast('已建好，之后按这条规则自动排')
    } else {
      await updateSeries({
        id: editingId.value,
        scope: 'all',
        title,
        location: value.itemType === 'schedule' ? value.location.trim() || undefined : undefined,
        freq: value.freq,
        interval: value.interval || undefined,
        byDay: value.freq === 'WEEKLY' ? value.byDay : undefined,
        countLimit: Number(value.countLimit) || undefined,
        untilDate: value.until || undefined,
      })
      toast('已更新这条规则')
    }
    close()
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '没存上', 'error')
  } finally {
    saving.value = false
  }
}

watch(() => props.epoch, load, { immediate: true })

</script>

<template>
  <div class="series">
    <div class="bar">
      <span class="count">共 {{ rows.length }} 条规则</span>
      <span class="pad"></span>
      <button class="pickmode" type="button" @click="editingId === undefined ? startCreate() : close()">
        {{ editingId === undefined ? '新建重复' : '收起' }}
      </button>
    </div>

    <!-- 新建 / 编辑规则。编辑时锚点只读：改「从哪天开始」等于换一条规则，用新建更清楚 -->
    <form v-if="editingId !== undefined" class="sheet" @submit.prevent="save">
      <div v-if="editingId === null" class="seg">
        <button type="button" :aria-current="form.itemType === 'schedule'" @click="form.itemType = 'schedule'">
          日程
        </button>
        <button type="button" :aria-current="form.itemType === 'todo'" @click="form.itemType = 'todo'">
          待办
        </button>
      </div>

      <label class="row2">
        <span>标题</span>
        <input v-model="form.title" class="field" type="text" maxlength="100" placeholder="例如 健身" />
      </label>

      <div class="row2">
        <span>频率</span>
        <div class="chips">
          <button
            v-for="f in FREQS"
            :key="f.key"
            class="chip"
            type="button"
            :aria-pressed="form.freq === f.key"
            @click="form.freq = f.key"
          >
            {{ f.label }}
          </button>
          <span class="every">每</span>
          <input v-model.number="form.interval" class="field tiny" type="number" min="1" max="12" />
          <span class="every">{{ form.freq === 'DAILY' ? '天' : form.freq === 'WEEKLY' ? '周' : '月' }}</span>
        </div>
      </div>

      <div v-if="form.freq === 'WEEKLY'" class="row2">
        <span>星期</span>
        <div class="chips">
          <button
            v-for="day in WEEKDAYS"
            :key="day"
            class="chip"
            type="button"
            :aria-pressed="form.byDay.includes(day)"
            @click="toggleDay(day)"
          >
            {{ DAY_TEXT[day] }}
          </button>
        </div>
      </div>

      <div class="row2">
        <span>{{ editingId === null ? '开始' : '锚点' }}</span>
        <template v-if="editingId === null">
          <input v-model="form.date" class="field narrow" type="date" />
          <input v-model="form.clock" class="field narrow" type="time" />
        </template>
        <span v-else class="ro">{{ anchorText }}</span>
      </div>

      <div v-if="editingId === null && form.itemType === 'schedule'" class="row2">
        <span>时长</span>
        <input v-model="form.duration" class="field tiny" type="number" min="0" max="1440" placeholder="分钟" />
        <span class="every">分钟，可空</span>
      </div>

      <div class="row2">
        <span>结束</span>
        <input v-model="form.countLimit" class="field tiny" type="number" min="1" placeholder="次数" />
        <span class="every">次，或到</span>
        <input v-model="form.until" class="field narrow" type="date" />
      </div>

      <div v-if="form.itemType === 'schedule'" class="row2">
        <span>地点</span>
        <input v-model="form.location" class="field" type="text" maxlength="100" placeholder="可空" />
      </div>

      <div class="foot">
        <span class="pad"></span>
        <button class="act" type="button" @click="close">取消</button>
        <button class="act primary" type="submit" :disabled="saving || !canSave">
          {{ saving ? '保存中…' : editingId === null ? '建好' : '保存' }}
        </button>
      </div>
    </form>

    <p v-if="loading && !rows.length" class="empty">正在取…</p>

    <p v-else-if="!rows.length" class="empty">
      还没有重复计划。跟它说「每周三晚上健身，先来 12 次」，这里就会出现一条规则，
      每天的事项由它自动排出来。
    </p>

    <ul v-else>
      <li v-for="series in rows" :key="series.id" class="row" :data-off="series.status !== 0">
        <button class="content open" type="button" @click="startEdit(series)">
          <span class="title">
            {{ series.title }}
            <span v-if="statusText(series)" class="flag">{{ statusText(series) }}</span>
          </span>
          <span class="rule">{{ ruleText(series) }}</span>
          <span class="progress">
            已排 {{ series.itemCount }} 条
            <template v-if="series.itemType === 'todo'"> · 待办</template>
          </span>
        </button>

        <div v-if="series.status === 0" class="ops">
          <template v-if="confirming === series.id">
            <button class="btn btn-danger" type="button" :disabled="busy" @click="stop(series)">
              {{ busy ? '停用中…' : '确认停用' }}
            </button>
            <button class="btn btn-text" type="button" :disabled="busy" @click="confirming = null">
              算了
            </button>
          </template>
          <button v-else class="btn btn-text" type="button" @click="confirming = series.id">
            停用
          </button>
        </div>
      </li>
    </ul>

    <p v-if="rows.length" class="note">
      停用只影响以后生成的日程，已经排好的那些还在「全部」里，可以逐条改。
    </p>
  </div>
</template>

<style scoped>
.series {
  padding: var(--s3) var(--s4) var(--s6);
}

.bar {
  display: flex;
  align-items: center;
  gap: var(--s2);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--rule);
  font-size: var(--t-xs);
  color: var(--muted);
}

.pad {
  flex: 1;
}

.pickmode {
  font-size: var(--t-xs);
  color: var(--signal);
  padding: 2px 0;
}

.pickmode:hover {
  color: var(--ink);
}

/* 规则表单：一行一字段，与个人主页的资料行同一套骨架 */
.sheet {
  padding: var(--s2) 0 var(--s3);
  border-bottom: 1px solid var(--rule);
}

.seg {
  display: flex;
  gap: var(--s4);
  padding: 2px 0 var(--s2);
}

.seg button {
  font-size: var(--t-sm);
  color: var(--muted);
  padding: 3px 0;
  border-bottom: 1px solid transparent;
}

.seg button[aria-current='true'] {
  color: var(--ink);
  border-bottom-color: var(--ink);
}

.row2 {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 38px;
  font-size: var(--t-sm);
}

.row2 + .row2 {
  border-top: 1px solid var(--rule);
}

.row2 > span:first-child {
  flex: 0 0 44px;
  color: var(--muted);
}

.field {
  flex: 1;
  min-width: 0;
}

.field.narrow {
  flex: 0 1 118px;
}

.field.tiny {
  flex: 0 0 58px;
}

.ro {
  flex: 1;
  color: var(--ink-soft);
}

.chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  padding: 4px 9px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  font-size: var(--t-xs);
  color: var(--muted);
  background: var(--paper);
  white-space: nowrap;
}

.chip[aria-pressed='true'] {
  color: var(--signal);
  border-color: var(--signal-line);
  background: var(--signal-soft);
}

.every {
  font-size: var(--t-xs);
  color: var(--muted);
}

.foot {
  display: flex;
  align-items: center;
  gap: var(--s3);
  padding-top: var(--s3);
}

.act {
  padding: 4px 0;
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
  opacity: 0.45;
}

button.open {
  display: block;
  width: 100%;
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

.row {
  padding: var(--s3) 0;
  border-bottom: 1px solid var(--rule);
}

.row[data-off='true'] {
  opacity: 0.55;
}

.content {
  min-width: 0;
}

.title {
  display: block;
  font-size: var(--t-sm);
  font-weight: 500;
}

.flag {
  margin-left: 6px;
  font-size: var(--t-xs);
  font-weight: 400;
  color: var(--muted);
}

.rule {
  display: block;
  margin-top: 2px;
  font-size: var(--t-sm);
  color: var(--ink-soft);
  line-height: 1.6;
}

.progress {
  display: block;
  margin-top: 1px;
  font-size: var(--t-xs);
  color: var(--muted);
}

.ops {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin-top: var(--s2);
}

.ops .btn {
  padding: 4px 10px;
  font-size: var(--t-xs);
}

.note {
  margin-top: var(--s4);
  font-size: var(--t-xs);
  color: var(--muted-soft);
  line-height: 1.6;
}
</style>
