<script setup lang="ts">
/** 提示条。只承载「操作结果」，不承载解释；解释该在出事的地方就地说明。 */
import { state } from '../core/store'
</script>

<template>
  <div class="stack" aria-live="polite">
    <TransitionGroup name="toast">
      <p v-for="item in state.toasts" :key="item.id" class="toast" :data-kind="item.kind">
        {{ item.text }}
      </p>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.stack {
  position: fixed;
  /* 顶栏下方、右上角：右下角留给到点提醒，两者不能抢同一块地方 */
  top: calc(var(--h-bar) + var(--s3));
  right: var(--s4);
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  z-index: var(--z-toast);
  pointer-events: none;
}

.toast {
  max-width: 320px;
  padding: 9px 12px;
  border-radius: var(--r-md);
  border: 1px solid var(--rule);
  background: var(--paper-raised);
  font-size: var(--t-sm);
  line-height: 1.5;
  color: var(--ink-soft);
  box-shadow: 0 1px 2px rgb(23 24 26 / 6%);
}

.toast[data-kind='error'] {
  border-color: #e2c7c0;
  color: var(--alert);
  background: var(--alert-soft);
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity var(--dur) var(--ease), transform var(--dur) var(--ease);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
</style>
