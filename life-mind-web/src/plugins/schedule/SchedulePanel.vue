<script setup lang="ts">
/**
 * 日程插件的面板根。四个分段：
 * 今天（按时间看）、日程（按日期看，原来的「全部」并在这里）、重复（规则而不是某一次）、备忘。
 *
 * 「今天」和「日程」不是同一个列表的两种排序，而是两种提问方式：
 * 「接下来这几个小时要做什么」和「25 号那天排了什么、我之前排过什么」。
 * 日历按月翻，所有事项都在日期上，不再单开一个「全部」列表。
 *
 * 收到 ui 帧时 store 会改 activeTab 并递增 panelEpoch，这里 watch 到就交给子组件重新取数；
 * params.date 用于「看看 11 月 25 日」这种定位，没有就是今天。
 */
import { computed, ref, watch } from 'vue'
import { state } from '../../core/store'
import { resolvePlugin } from '../../core/plugin-registry'
import { toDateString } from '../../core/date'
import DaySpine from './DaySpine.vue'
import CalendarGrid from './CalendarGrid.vue'
import SeriesList from './SeriesList.vue'
import MemoList from './MemoList.vue'
import ItemForm from './ItemForm.vue'

const manifest = resolvePlugin('schedule')!

const date = computed(() => state.panelParams.date || toDateString(new Date()))

// agent 的旧 ui 帧可能还发 view=list；「全部」已并入日程，归一到日历而不是落进备忘
const active = computed(() => {
  const tab = state.activeTab || manifest.defaultTab
  return tab === 'list' ? 'calendar' : tab
})

/**
 * 表单开在面板内，不新开工作区页：新建/编辑是「在日程里做一件事」，
 * 另开一页会让标签栏变成一堆「新建日程」，关页时还会连未保存的输入一起丢掉。
 *
 * undefined = 没开；null = 新建；数字 = 编辑这一条。
 */
const editing = ref<number | null | undefined>(undefined)
/** 新建表单默认落在哪天。点了日历里的某天再「在这天新建」，不能跳回今天 */
const formDate = ref(date.value)

function add(day?: string) {
  formDate.value = day || date.value
  editing.value = null
}

function edit(id: number) {
  editing.value = id
}

function done() {
  editing.value = undefined
  // 保存/取消后让当前分段重新取数：改完还显示旧标题是最容易漏的一处
  state.panelEpoch += 1
}

function go(tab: string) {
  state.activeTab = tab
  editing.value = undefined
  // 手动切换也算一次「面板变化」，让子组件按需重取，避免看到上次的旧数据
  state.panelEpoch += 1
}

// 面板参数由 agent 的 ui 帧写入，切走再切回来时不必重复累加 epoch，
// 但日期变了必须触发重取，否则用户会看到「说的是 25 号，显示的是今天」
watch(date, () => {
  state.panelEpoch += 1
})

const TABS = [
  { key: 'day', label: '今天' },
  { key: 'calendar', label: '日程' },
  { key: 'series', label: '重复' },
  { key: 'memo', label: '备忘' },
]

// 子组件用的刷新信号：父层 epoch，隐藏的 tab 不渲染就不取数
const epoch = computed(() => state.panelEpoch)
</script>

<template>
  <div class="wrap">
    <nav class="sub" aria-label="日程视图">
      <button
        v-for="tab in TABS"
        :key="tab.key"
        type="button"
        :aria-current="active === tab.key"
        @click="go(tab.key)"
      >
        {{ tab.label }}
      </button>
      <span class="pad"></span>
      <button v-if="active !== 'series' && active !== 'memo'" class="add" type="button" @click="add()">
        新建
      </button>
    </nav>

    <div class="body">
      <ItemForm
        v-if="editing !== undefined"
        :item-id="editing"
        :default-date="formDate"
        @close="editing = undefined"
        @saved="done"
      />
      <DaySpine v-else-if="active === 'day'" :date="date" :epoch="epoch" @edit="edit" />
      <CalendarGrid
        v-else-if="active === 'calendar'"
        :date="date"
        :epoch="epoch"
        @edit="edit"
        @create="add"
      />
      <SeriesList v-else-if="active === 'series'" :epoch="epoch" />
      <MemoList v-else :epoch="epoch" />
    </div>
  </div>
</template>

<style scoped>
.wrap {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
}

.sub {
  display: flex;
  gap: var(--s4);
  padding: 0 var(--s4);
  border-bottom: 1px solid var(--rule);
}

.sub button {
  padding: 9px 0;
  font-size: var(--t-xs);
  color: var(--muted);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}

.sub button[aria-current='true'] {
  color: var(--ink);
  border-bottom-color: var(--signal);
}

.pad {
  flex: 1;
}

/* 「新建」不抢视线：分段里已经有内容时它只是一个小动作，做成信号色的字 */
.add {
  align-self: center;
  padding: 2px 0;
  font-size: var(--t-xs);
  color: var(--signal);
  border: none;
  margin: 0;
}

.add:hover {
  color: var(--ink);
}

.body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
</style>
