<script setup lang="ts">
/**
 * 右侧工作区：一组浏览器式的页面。
 *
 * 空白页是入口，只列出**已绑定**且前端已做好的插件，点一张就在**当前页**打开；
 * 「＋」新建空白页，再去开别的插件，同一个插件也能开多张页。
 * 这里只放插件面板；左栏里的四个入口都是独立界面，不占这里的标签。绑定/解绑统一在插件服务库做。
 */
import { computed } from 'vue'
import {
  addWorkspacePage,
  closeWorkspacePage,
  openPluginHere,
  selectWorkspacePage,
  state,
} from '../core/store'
import type { WorkspacePage } from '../core/types'
import { PLUGIN_MANIFESTS, resolvePlugin } from '../core/plugin-registry'
import ChevronIcon from '../components/ChevronIcon.vue'

const current = computed(() => state.workspacePages.find((p) => p.key === state.workspacePage))
const manifest = computed(() =>
  current.value?.pluginKey ? resolvePlugin(current.value.pluginKey) : undefined,
)

const BLANK_ICON = 'M4 4h5v5H4zM11 4h5v5h-5zM4 11h5v5H4zM11 11h5v5h-5z'

function iconOf(page: WorkspacePage): string {
  return page.pluginKey ? (resolvePlugin(page.pluginKey)?.icon ?? BLANK_ICON) : BLANK_ICON
}

/**
 * 工作区只列**已绑定**的插件：解绑后这里看不见，是插件服务库统一管理绑定的直接结果。
 * 启停开关不放在这里——要去插件服务库解绑，避免两处入口各说各话。
 */
const entries = computed(() =>
  PLUGIN_MANIFESTS.map((m) => {
    const info = state.plugins.find((p) => p.pluginKey === m.key)
    return {
      key: m.key,
      name: info?.name || m.name,
      desc: info?.description || m.hint,
      icon: m.icon,
    }
  }).filter((e) => {
    const info = state.plugins.find((p) => p.pluginKey === e.key)
    // 没配过（info 为空）时按 manifest 默认可点；明确解绑的才排除
    return !info || info.enabled
  }),
)

const soon = computed(() =>
  state.plugins
    .filter((p) => p.enabled && !PLUGIN_MANIFESTS.some((m) => m.key === p.pluginKey))
    .map((p) => p.name),
)

</script>

<template>
  <aside class="workspace">
    <!-- 浏览器式标签栏：活动页抬起、与下方内容连成一体 -->
    <nav class="tabbar" aria-label="工作区页面">
      <div class="tabs">
        <button
          v-for="page in state.workspacePages"
          :key="page.key"
          class="tab"
          type="button"
          :data-active="state.workspacePage === page.key"
          :title="page.label"
          @click="selectWorkspacePage(page.key)"
        >
          <svg class="favicon" viewBox="0 0 20 20" aria-hidden="true">
            <path :d="iconOf(page)" />
          </svg>
          <span class="tab-label">{{ page.label }}</span>
          <span
            class="tab-close"
            role="button"
            tabindex="0"
            title="关闭这一页"
            @click.stop="closeWorkspacePage(page.key)"
            @keydown.enter.stop="closeWorkspacePage(page.key)"
          >×</span>
        </button>
        <button class="tab-new" type="button" title="新建空白页" aria-label="新建空白页" @click="addWorkspacePage()">＋</button>
      </div>
    </nav>

    <div class="content">
      <component :is="manifest.panel" v-if="manifest" :key="state.workspacePage" />

      <div v-else class="blank">
        <div class="intro">
          <h2>工作区</h2>
          <p>这里只列出已绑定的服务。要绑定或解绑，去左侧的插件服务库。</p>
        </div>

        <div v-if="!entries.length" class="none">
          还没有绑定任何服务。去插件服务库绑定一个，它就会出现在这里。
        </div>

        <div class="cards">
          <div v-for="item in entries" :key="item.key" class="card">
            <button class="open" type="button" :title="`打开${item.name}`" @click="openPluginHere(item.key)">
              <span class="card-icon">
                <svg viewBox="0 0 20 20" aria-hidden="true"><path :d="item.icon" /></svg>
              </span>
              <span class="card-text">
                <strong>{{ item.name }}</strong>
                <span class="desc">{{ item.desc }}</span>
              </span>
              <span class="state">打开<ChevronIcon :size="13" /></span>
            </button>
          </div>
        </div>

        <p v-if="soon.length" class="soon">即将开放：{{ soon.join('、') }}</p>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.workspace {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--paper);
}

/* 浏览器顶部：深一档的 chrome 底 + 抬起的纸白活动标签 */
.tabbar {
  display: flex;
  align-items: flex-end;
  flex: 0 0 auto;
  height: 44px;
  padding: 0 8px;
  background: var(--chrome);
}

.tabs {
  display: flex;
  align-items: flex-end;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.tabs::-webkit-scrollbar {
  display: none;
}

.tab {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  max-width: 176px;
  flex: 0 0 auto;
  padding: 0 8px 0 12px;
  border: 1px solid transparent;
  border-bottom: none;
  /* 左边沿保持直边，只把右侧做成弧线 */
  border-radius: 0 var(--r-lg) 0 0;
  color: var(--muted);
  font-size: var(--t-xs);
  white-space: nowrap;
}

.tab + .tab {
  margin-left: 2px;
}

.tab:hover {
  color: var(--ink);
  background: var(--paper-sunken);
}

.tab[data-active='true'] {
  position: relative;
  z-index: 1;
  height: 38px;
  border-color: var(--rule);
  background: var(--paper);
  color: var(--ink);
}

.tab-label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.favicon {
  width: 14px;
  height: 14px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.tab-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  flex: 0 0 auto;
  border-radius: 50%;
  font-size: 13px;
  line-height: 1;
  color: var(--muted-soft);
}

.tab:not([data-active='true']) .tab-close {
  visibility: hidden;
}

.tab:hover .tab-close,
.tab:focus-within .tab-close {
  visibility: visible;
}

.tab-close:hover {
  color: var(--alert);
  background: var(--alert-soft);
}

.tab-new {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  margin: 0 0 4px 6px;
  border-radius: 50%;
  font-size: 17px;
  color: var(--muted);
}

.tab-new:hover {
  color: var(--ink);
  background: var(--paper-sunken);
}

.content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  min-width: 0;
}

.blank {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--s6) var(--s5);
}

.intro h2 {
  font-size: 19px;
  font-weight: 500;
  letter-spacing: -0.01em;
}

.intro p {
  margin-top: 7px;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--muted);
}

/* 插件入口是横着的长条，不是方块：图标 | 名称+说明 | 打开，右侧另挂启停开关 */
.cards {
  display: grid;
  gap: var(--s2);
  margin-top: var(--s5);
}

.card {
  display: flex;
  align-items: center;
  gap: 2px;
  padding-right: 10px;
  border: 1px solid var(--rule);
  border-radius: var(--r-lg);
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease),
    opacity var(--dur) var(--ease);
}

.card:hover {
  border-color: var(--signal-line);
  background: var(--signal-soft);
}

.open {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
  padding: 12px 6px 12px 14px;
  text-align: left;
}

.card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: var(--r-md);
  background: var(--signal-soft);
  color: var(--signal);
}

.card-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.card-icon svg {
  width: 17px;
  height: 17px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.card strong {
  font-size: var(--t-sm);
  font-weight: 500;
  color: var(--ink);
}

.desc {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--t-xs);
  color: var(--muted);
}

.state {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex: 0 0 auto;
  font-size: var(--t-xs);
  color: var(--signal);
}

.none {
  margin-top: var(--s4);
  font-size: var(--t-xs);
  color: var(--muted);
}

.soon {
  margin-top: var(--s6);
  padding-top: var(--s4);
  border-top: 1px solid var(--rule);
  font-size: var(--t-xs);
  color: var(--muted-soft);
}
</style>
