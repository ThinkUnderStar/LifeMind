<script setup lang="ts">
/**
 * 右侧栏的宿主：插件切换 + 面板装载。
 *
 * 三份事实合并成一个入口，顺序不能反：
 * 1. account 的 `/plugin/list` —— 用户能不能用（enabled），这是唯一可信的开关状态；
 * 2. 前端注册表 —— 界面做没做出来；
 * 3. 两者取交集才可点开。
 *
 * 账单、健康在服务端还没有实现，所以在这里显示为「即将开放」而不是隐藏：
 * 用户需要知道还有别的东西，但点进去必须是说明，不能是空白或 404。
 */
import { computed } from 'vue'
import { openPlugin, state, togglePlugin } from '../core/store'
import { PLUGIN_MANIFESTS, resolvePlugin } from '../core/plugin-registry'
import type { PluginInfo } from '../core/types'

interface Entry {
  key: string
  name: string
  /** ready = 前端已实现且后端已启用 */
  ready: boolean
  enabled: boolean
  hint: string
}

const entries = computed<Entry[]>(() => {
  const backend: PluginInfo[] = state.plugins
  const known = new Map(backend.map((p) => [p.pluginKey, p]))

  return PLUGIN_MANIFESTS.map((manifest) => {
    const info = known.get(manifest.key)
    // 后端没返回这个插件（已下架）时按未启用处理，不假装可用
    const enabled = info?.enabled ?? false
    return {
      key: manifest.key,
      name: manifest.name,
      enabled,
      ready: enabled,
      hint: manifest.hint,
    }
  }).concat(
    // 后端有、前端没有实现的插件
    backend
      .filter((p) => !resolvePlugin(p.pluginKey))
      .map((p) => ({
        key: p.pluginKey,
        name: p.name,
        enabled: p.enabled,
        ready: false,
        hint: '服务端还在做，界面上暂时打不开',
      })),
  )
})

const active = computed(() => entries.value.find((e) => e.key === state.activePlugin) ?? entries.value[0])
const manifest = computed(() => resolvePlugin(active.value?.key ?? ''))

function pick(entry: Entry) {
  if (!entry.ready) return
  openPlugin(entry.key)
}
</script>

<template>
  <aside class="panel">
    <nav class="tabs" aria-label="插件">
      <button
        v-for="entry in entries"
        :key="entry.key"
        type="button"
        class="tab"
        :data-active="entry.key === active?.key"
        :data-ready="entry.ready"
        :disabled="!entry.ready"
        :title="entry.hint"
        @click="pick(entry)"
      >
        {{ entry.name }}
      </button>
    </nav>

    <component :is="manifest.panel" v-if="manifest && active?.ready" />

    <div v-else class="off">
      <!-- 标题不重复：上面那个 tab 已经写着插件名了 -->
      <p v-if="state.plugins.length === 0" class="why">
        没取到插件列表。网关或 account 服务可能还没启动，起来之后刷新这一页。
      </p>

      <template v-else>
        <p v-if="active && !active.enabled" class="why">
          这个插件现在是停用状态。启用之后，对话里就能用它，这里也会开始显示数据。
        </p>
        <p v-else class="why">{{ active?.hint }}</p>

        <button
          v-if="active && !active.enabled"
          class="btn btn-primary"
          type="button"
          @click="togglePlugin(active.key, true)"
        >
          启用{{ active.name }}
        </button>
      </template>
    </div>
  </aside>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-left: 1px solid var(--rule);
  background: var(--paper);
}

.tabs {
  display: flex;
  gap: var(--s4);
  padding: 0 var(--s4);
  border-bottom: 1px solid var(--rule);
  overflow-x: auto;
}

.tab {
  padding: 13px 0 11px;
  font-size: var(--t-sm);
  color: var(--muted);
  white-space: nowrap;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}

.tab[data-active='true'] {
  color: var(--ink);
  border-bottom-color: var(--signal);
}

/* :disabled 而不是 [data-ready='false']：Vue 对值为 false 的 data 属性会直接不渲染，
   选择器永远匹配不到，这个坑很隐蔽 */
.tab:disabled {
  color: var(--muted-soft);
  cursor: not-allowed;
}

.off {
  padding: var(--s5) var(--s4);
  max-width: 30em;
}

.off .why {
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--muted);
}

.off .why + .why {
  margin-top: var(--s3);
}

.off .btn {
  margin-top: var(--s4);
}
</style>
