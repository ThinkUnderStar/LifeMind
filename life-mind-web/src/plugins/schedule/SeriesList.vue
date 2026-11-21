<script setup lang="ts">
/**
 * 重复计划。这里列的不是「某一次健身」，而是「每周三健身」这条规则本身。
 * 所以显示的是规则、进度、以及终点——不是时间。
 *
 * 停用是不可逆的批量动作（会把之后每一次都删掉），所以做成两段式内联确认，
 * 不用浏览器 confirm：那个框会遮住整页、样式也不受控，而且用户点了才知道发生了什么。
 */
import { ref, watch } from 'vue'
import { listSeries, deleteSeries } from '../../api/schedule'
import type { Series } from '../../core/types'
import { parseDateTime, timeText } from '../../core/date'
import { toast } from '../../core/store'

const props = defineProps<{ epoch: number }>()

const rows = ref<Series[]>([])
const loading = ref(false)
const confirming = ref<number | null>(null)
const busy = ref(false)

const DAY_TEXT: Record<string, string> = {
  MO: '周一',
  TU: '周二',
  WE: '周三',
  TH: '周四',
  FR: '周五',
  SA: '周六',
  SU: '周日',
}

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

watch(() => props.epoch, load, { immediate: true })
</script>

<template>
  <div class="series">
    <p v-if="loading && !rows.length" class="empty">正在取…</p>

    <p v-else-if="!rows.length" class="empty">
      还没有重复计划。跟它说「每周三晚上健身，先来 12 次」，这里就会出现一条规则，
      每天的事项由它自动排出来。
    </p>

    <ul v-else>
      <li v-for="series in rows" :key="series.id" class="row" :data-off="series.status !== 0">
        <div class="content">
          <p class="title">
            {{ series.title }}
            <span v-if="statusText(series)" class="flag">{{ statusText(series) }}</span>
          </p>
          <p class="rule">{{ ruleText(series) }}</p>
          <p class="progress">
            已排 {{ series.itemCount }} 条
            <template v-if="series.itemType === 'todo'"> · 待办</template>
          </p>
        </div>

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
  margin-top: 2px;
  font-size: var(--t-sm);
  color: var(--ink-soft);
  line-height: 1.6;
}

.progress {
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
