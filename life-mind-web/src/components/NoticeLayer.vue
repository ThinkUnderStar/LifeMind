<script setup lang="ts">
/**
 * 到点提醒。这是「网页内弹窗」的落地形态：不做全屏遮罩，
 * 用户正在读一段回复时，全屏遮罩会把他按在原地；右下角一块不遮挡正文的卡片更合适。
 *
 * 只显示最新一条。多条堆叠会把屏幕右下角糊满，而提醒的价值在「知道有这件事」，
 * 不在「一次看完」——剩下的数量用一行小字说明，点开去通知箱看全量。
 */
import { computed } from 'vue'
import { dismissNotice, state } from '../core/store'

const current = computed(() => state.notices[0])
const rest = computed(() => Math.max(0, state.notices.length - 1))
</script>

<template>
  <Transition name="rise">
    <aside v-if="current" class="notice" role="alert">
      <p class="type">{{ current.type === 'reminder' ? '日程提醒' : '通知' }}</p>
      <h3 class="title">{{ current.title }}</h3>
      <p v-if="current.content" class="content">{{ current.content }}</p>

      <div class="foot">
        <button class="btn btn-quiet" type="button" @click="dismissNotice(current.id)">
          知道了
        </button>
        <span v-if="rest" class="rest">还有 {{ rest }} 条</span>
      </div>
    </aside>
  </Transition>
</template>

<style scoped>
.notice {
  position: fixed;
  right: var(--s4);
  bottom: var(--s4);
  width: 304px;
  padding: var(--s3) var(--s4) var(--s3) var(--s3);
  border-left: 3px solid var(--signal);
  border-top: 1px solid var(--rule);
  border-right: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);
  background: var(--paper-raised);
  z-index: var(--z-pop);
  box-shadow: 0 2px 8px rgb(23 24 26 / 8%);
}

.type {
  font-size: var(--t-xs);
  color: var(--signal);
}

.title {
  margin-top: 2px;
  font-size: var(--t-sm);
  font-weight: 600;
}

.content {
  margin-top: var(--s2);
  font-size: var(--t-sm);
  line-height: 1.6;
  color: var(--ink-soft);
  max-height: 4.8em;
  overflow-y: auto;
}

.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s2);
  margin-top: var(--s3);
}

.foot .btn-quiet {
  padding: 5px 12px;
}

.rest {
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

.rise-enter-active,
.rise-leave-active {
  transition: opacity var(--dur) var(--ease), transform var(--dur) var(--ease);
}

.rise-enter-from,
.rise-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
