<script setup lang="ts">
/**
 * 事项表单：新建与编辑共用一张。
 *
 * 为什么按「类型」分支而不是做成一个万能表单：日程问的是「几点到几点」，
 * 待办问的是「什么时候之前」——同一张表单里同时放开始/结束/截止三个时间，
 * 用户每次都要先判断哪个该填。切换类型只留当前类型需要的字段。
 *
 * 编辑时先取详情（`GET /item/{id}`）而不是拿列表行回填：列表不带 remindOffsets，
 * 用列表行回填会把「提前 15 分钟」这类设置悄悄抹掉。
 */
import { computed, onMounted, ref } from 'vue'
import { cancelItem, createItem, deleteItem, getItem, updateItem } from '../../api/schedule'
import type { ItemCreatePayload } from '../../api/schedule'
import type { Item } from '../../core/types'
import { parseDateTime, toDateString } from '../../core/date'
import { toast } from '../../core/store'

const props = defineProps<{ itemId: number | null; defaultDate?: string }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved'): void }>()

const editing = computed(() => props.itemId !== null)

const kind = ref<'schedule' | 'todo'>('schedule')
const title = ref('')
const location = ref('')
const note = ref('')
const date = ref(props.defaultDate || toDateString(new Date()))
const start = ref('09:00')
const end = ref('10:00')
const dueDate = ref(props.defaultDate || toDateString(new Date()))
const dueClock = ref('18:00')
const allDay = ref(false)
const offsets = ref<number[]>([0])

const loading = ref(false)
const saving = ref(false)
const status = ref(0)
/** 取消是单向的（后端幂等但不可逆），所以做成两段：点一次只把按钮换成「确认取消」 */
const arming = ref<'' | 'cancel' | 'delete'>('')

const OFFSETS = [
  { value: 0, label: '到点' },
  { value: 5, label: '5 分钟前' },
  { value: 15, label: '15 分钟前' },
  { value: 30, label: '30 分钟前' },
  { value: 60, label: '1 小时前' },
]

function clock(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function fill(item: Item) {
  kind.value = item.itemType
  title.value = item.title
  location.value = item.location ?? ''
  note.value = item.content ?? ''
  allDay.value = item.allDay === 1
  status.value = item.status
  offsets.value = item.remindOffsets ?? []

  if (item.itemType === 'todo') {
    const due = parseDateTime(item.dueTime)
    if (due) {
      dueDate.value = toDateString(due)
      dueClock.value = clock(due)
    }
    return
  }

  const begin = parseDateTime(item.startTime)
  if (begin) {
    date.value = toDateString(begin)
    start.value = clock(begin)
  }
  const finish = parseDateTime(item.endTime)
  end.value = finish ? clock(finish) : ''
}

onMounted(async () => {
  if (props.itemId === null) return
  loading.value = true
  try {
    fill(await getItem(props.itemId))
  } catch (error) {
    toast(error instanceof Error ? error.message : '这条事项没取到', 'error')
    emit('close')
  } finally {
    loading.value = false
  }
})

function toggleOffset(value: number) {
  const set = new Set(offsets.value)
  if (set.has(value)) set.delete(value)
  else set.add(value)
  offsets.value = [...set].sort((a, b) => a - b)
}

async function save() {
  const name = title.value.trim()
  if (!name) return toast('先写个标题', 'error')

  const payload: ItemCreatePayload = {
    itemType: kind.value,
    title: name,
    content: note.value.trim() || undefined,
    location: kind.value === 'schedule' ? location.value.trim() || undefined : undefined,
    allDay: allDay.value ? 1 : 0,
    remindOffsets: offsets.value,
  }

  if (kind.value === 'schedule') {
    payload.startTime = `${date.value} ${allDay.value ? '00:00' : start.value}:00`
    if (!allDay.value && end.value) payload.endTime = `${date.value} ${end.value}:00`
  } else if (dueClock.value) {
    payload.dueTime = `${dueDate.value} ${dueClock.value}:00`
  }

  saving.value = true
  try {
    if (props.itemId === null) await createItem(payload)
    else await updateItem({ ...payload, id: props.itemId })
    toast(editing.value ? '已更新' : '已记下')
    emit('saved')
  } catch (error) {
    toast(error instanceof Error ? error.message : '没存上', 'error')
  } finally {
    saving.value = false
  }
}

async function kill() {
  if (props.itemId === null) return
  if (!arming.value) {
    arming.value = kind.value === 'schedule' ? 'cancel' : 'delete'
    return
  }
  saving.value = true
  try {
    if (kind.value === 'schedule') await cancelItem(props.itemId)
    else await deleteItem(props.itemId)
    toast(kind.value === 'schedule' ? '日程已取消' : '待办已删除')
    emit('saved')
  } catch (error) {
    toast(error instanceof Error ? error.message : '没改成', 'error')
    arming.value = ''
  } finally {
    saving.value = false
  }
}

const killLabel = computed(() => {
  if (!arming.value) return kind.value === 'schedule' ? '取消日程' : '删除待办'
  return arming.value === 'cancel' ? '确认取消？不可恢复' : '确认删除？'
})
</script>

<template>
  <div class="form">
    <header class="head">
      <h2>{{ editing ? '编辑' : kind === 'schedule' ? '新建日程' : '新建待办' }}</h2>
      <button class="shut" type="button" title="关闭" aria-label="关闭" @click="emit('close')">×</button>
    </header>

    <p v-if="loading" class="hint">正在取这一条…</p>

    <template v-else>
      <nav v-if="!editing" class="kinds" aria-label="类型">
        <button type="button" :aria-current="kind === 'schedule'" @click="kind = 'schedule'">日程</button>
        <button type="button" :aria-current="kind === 'todo'" @click="kind = 'todo'">待办</button>
      </nav>

      <label class="row">
        <span>标题</span>
        <input v-model="title" class="field" type="text" maxlength="100" placeholder="要做什么" />
      </label>

      <template v-if="kind === 'schedule'">
        <label class="row">
          <span>日期</span>
          <input v-model="date" class="field" type="date" />
        </label>
        <div class="row">
          <span>时间</span>
          <template v-if="allDay">
            <span class="pad muted">全天</span>
          </template>
          <template v-else>
            <input v-model="start" class="field narrow" type="time" />
            <span class="dash">–</span>
            <input v-model="end" class="field narrow" type="time" />
          </template>
          <button
            class="chip"
            type="button"
            :aria-pressed="allDay"
            @click="allDay = !allDay"
          >
            全天
          </button>
        </div>
        <label class="row">
          <span>地点</span>
          <input v-model="location" class="field" type="text" maxlength="100" placeholder="可空" />
        </label>
      </template>

      <template v-else>
        <div class="row">
          <span>截止</span>
          <input v-model="dueDate" class="field narrow" type="date" />
          <input v-model="dueClock" class="field narrow" type="time" />
          <button class="chip" type="button" @click="dueClock = ''">不设时段</button>
        </div>
      </template>

      <label class="row top">
        <span>备注</span>
        <textarea v-model="note" class="field area" rows="2" maxlength="500" placeholder="可空"></textarea>
      </label>

      <div class="row top">
        <span>提醒</span>
        <div class="chips">
          <button
            v-for="item in OFFSETS"
            :key="item.value"
            class="chip"
            type="button"
            :aria-pressed="offsets.includes(item.value)"
            @click="toggleOffset(item.value)"
          >
            {{ item.label }}
          </button>
        </div>
      </div>

      <footer class="foot">
        <button
          v-if="editing && status !== 2"
          class="act danger"
          type="button"
          :disabled="saving"
          @click="kill"
        >
          {{ killLabel }}
        </button>
        <span class="pad"></span>
        <button class="act" type="button" @click="emit('close')">取消</button>
        <button class="act primary" type="button" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.form {
  display: flex;
  flex-direction: column;
  gap: 0;
  height: 100%;
  overflow-y: auto;
  padding: var(--s4) var(--s4) var(--s5);
}

.head {
  display: flex;
  align-items: center;
  gap: var(--s2);
  margin-bottom: var(--s2);
}

.head h2 {
  flex: 1;
  font-size: var(--t-md);
  font-weight: 500;
}

.shut {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  font-size: var(--t-md);
  line-height: 1;
  color: var(--muted-soft);
}

.shut:hover {
  color: var(--ink);
  background: var(--paper-sunken);
}

.kinds {
  display: flex;
  gap: var(--s4);
  padding: 2px 0 var(--s2);
  border-bottom: 1px solid var(--rule);
  margin-bottom: var(--s2);
}

.kinds button {
  font-size: var(--t-sm);
  color: var(--muted);
  padding: 3px 0;
  border-bottom: 1px solid transparent;
}

.kinds button[aria-current='true'] {
  color: var(--ink);
  border-bottom-color: var(--ink);
}

.hint {
  font-size: var(--t-sm);
  color: var(--muted);
  padding: var(--s4) 0;
}

.row {
  display: flex;
  align-items: center;
  gap: var(--s2);
  min-height: 40px;
  padding: 6px 0;
  font-size: var(--t-sm);
}

.row + .row,
.kinds + .row {
  border-top: 1px solid var(--rule);
}

.row.top {
  align-items: flex-start;
}

.row > span:first-child {
  flex: 0 0 42px;
  color: var(--muted);
  padding-top: 6px;
}

.field {
  flex: 1;
  min-width: 0;
  padding: 6px 9px;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  font-size: var(--t-sm);
  color: var(--ink);
  background: var(--paper);
}

.field:focus {
  border-color: var(--rule-strong);
}

.field.narrow {
  flex: 0 1 112px;
}

.area {
  resize: vertical;
  min-height: 52px;
  line-height: 1.6;
}

.dash {
  color: var(--muted-soft);
}

.muted {
  color: var(--muted);
}

.pad {
  flex: 1;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 2px 0;
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

.chip:hover {
  color: var(--ink);
  border-color: var(--rule-strong);
}

.chip[aria-pressed='true'] {
  color: var(--signal);
  border-color: var(--signal-line);
  background: var(--signal-soft);
}

.foot {
  display: flex;
  align-items: center;
  gap: var(--s3);
  margin-top: var(--s4);
  padding-top: var(--s3);
  border-top: 1px solid var(--rule);
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

.act.danger {
  color: var(--muted-soft);
}

.act.danger:hover {
  color: var(--alert);
}

.act:disabled {
  opacity: 0.5;
}
</style>
