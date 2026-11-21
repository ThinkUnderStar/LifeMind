<script setup lang="ts">
/**
 * 日程插件的面板根。四个分段，对应四件不同的事：
 * 今天（按时间看）、全部（按类型翻）、重复（规则而不是某一次）、备忘。
 *
 * 「今天」和「全部」不是同一个列表的两种排序，而是两种提问方式：
 * 「接下来这几个小时要做什么」和「我上周都排了什么」。合成一个界面会两边都不好用。
 *
 * 收到 ui 帧时 store 会改 activeTab 并递增 panelEpoch，这里 watch 到就交给子组件重新取数；
 * params.date 用于「看看 11 月 25 日」这种定位，没有就是今天。
 */
import { computed, watch } from 'vue'
import { state } from '../../core/store'
import { resolvePlugin } from '../../core/plugin-registry'
import { toDateString } from '../../core/date'
import DaySpine from './DaySpine.vue'
import ItemList from './ItemList.vue'
import SeriesList from './SeriesList.vue'
import MemoList from './MemoList.vue'

const manifest = resolvePlugin('schedule')!

const date = computed(() => state.panelParams.date || toDateString(new Date()))

const active = computed(() => state.activeTab || manifest.defaultTab)

function go(tab: string) {
  state.activeTab = tab
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
  { key: 'list', label: '全部' },
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
    </nav>

    <div class="body">
      <DaySpine v-if="active === 'day'" :date="date" :epoch="epoch" />
      <ItemList v-else-if="active === 'list'" :epoch="epoch" />
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

.body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
</style>
