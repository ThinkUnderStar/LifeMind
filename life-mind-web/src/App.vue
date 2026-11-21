<script setup lang="ts">
/**
 * 应用根。只有一个判断：有没有登录态。
 * 启动时先问一次后端（比如令牌早就在浏览器里但已过期），再决定渲染哪一屏，
 * 避免先闪一下主界面再跳回登录。
 */
import { onMounted } from 'vue'
import { boot, state } from './core/store'
import LoginView from './views/LoginView.vue'
import AppShell from './views/AppShell.vue'

onMounted(() => {
  void boot()
})
</script>

<template>
  <LoginView v-if="state.booted && !state.authed" />
  <AppShell v-else-if="state.authed" />
  <div v-else class="booting" aria-live="polite">正在打开…</div>
</template>

<style scoped>
.booting {
  display: grid;
  place-items: center;
  height: 100%;
  color: var(--muted);
  font-size: var(--t-sm);
}
</style>
