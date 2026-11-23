<script setup lang="ts">
/**
 * 右侧工作区：一组浏览器式的页面。
 *
 * 空白页是入口，列出已经做好的插件，点一张就在**当前页**打开；
 * 「＋」新建空白页，再去开别的插件，同一个插件也能开多张页。
 * 通知与个人主页也是这里的一页：和插件页共用标签，中间对话不被顶掉。
 */
import { computed } from 'vue'
import {
  addWorkspacePage,
  closeWorkspacePage,
  openPluginHere,
  selectWorkspacePage,
  state,
  toggleWorkspace,
} from '../core/store'
import type { WorkspacePage } from '../core/types'
import { PLUGIN_MANIFESTS, resolvePlugin } from '../core/plugin-registry'
import NoticePage from './NoticePage.vue'
import ProfilePage from './ProfilePage.vue'

const current = computed(() => state.workspacePages.find((p) => p.key === state.workspacePage))
const manifest = computed(() =>
  current.value?.kind === 'plugin' && current.value.pluginKey
    ? resolvePlugin(current.value.pluginKey)
    : undefined,
)

const BLANK_ICON = 'M4 4h5v5H4zM11 4h5v5h-5zM4 11h5v5H4zM11 11h5v5h-5z'
const NOTICE_ICON =
  'M10 2.5a4.5 4.5 0 0 0-4.5 4.5v3L4 12.5h12l-1.5-2.5V7A4.5 4.5 0 0 0 10 2.5ZM8.2 15a2 2 0 0 0 3.6 0'
const PROFILE_ICON = 'M10 4.6a2.9 2.9 0 1 1 0 5.8 2.9 2.9 0 0 1 0-5.8ZM4.6 16.4c0-2.6 2.4-4.2 5.4-4.2s5.4 1.6 5.4 4.2'

function iconOf(page: WorkspacePage): string {
  if (page.kind === 'notice') return NOTICE_ICON
  if (page.kind === 'profile') return PROFILE_ICON
  return page.pluginKey ? (resolvePlugin(page.pluginKey)?.icon ?? BLANK_ICON) : BLANK_ICON
}

/** 已做好的插件始终可点；后端的 enabled 只决定能不能真的调它的服务 */
const entries = computed(() =>
  PLUGIN_MANIFESTS.map((m) => {
    const info = state.plugins.find((p) => p.pluginKey === m.key)
    return {
      key: m.key,
      name: info?.name || m.name,
      desc: info?.description || m.hint,
      icon: m.icon,
      enabled: info ? info.enabled : true,
    }
  }),
)

const soon = computed(() =>
  state.plugins
    .filter((p) => !PLUGIN_MANIFESTS.some((m) => m.key === p.pluginKey))
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
      <button class="fold" type="button" title="收起工作区" aria-label="收起工作区" @click="toggleWorkspace">
        <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5 10h10" /></svg>
      </button>
    </nav>

    <div class="content">
      <component :is="manifest.panel" v-if="manifest" :key="state.workspacePage" />

      <div v-else-if="current?.kind === 'notice'" class="page-host">
        <NoticePage />
      </div>

      <div v-else-if="current?.kind === 'profile'" class="page-host">
        <ProfilePage />
      </div>

      <div v-else class="blank">
        <div class="intro">
          <h2>工作区</h2>
          <p>挑一个服务，在这里直接打开。也可以先新建空白页，再打开另一个服务。</p>
        </div>

        <div class="cards">
          <button
            v-for="item in entries"
            :key="item.key"
            class="card"
            type="button"
            :disabled="!item.enabled"
            :title="item.enabled ? `打开${item.name}` : '这个插件当前未启用'"
            @click="openPluginHere(item.key)"
          >
            <span class="card-icon">
              <svg viewBox="0 0 20 20" aria-hidden="true"><path :d="item.icon" /></svg>
            </span>
            <span class="card-text">
              <strong>{{ item.name }}</strong>
              <span class="desc">{{ item.desc }}</span>
            </span>
            <span class="state">{{ item.enabled ? '打开 →' : '未启用' }}</span>
          </button>
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

.tab-new,
.fold {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  border-radius: 50%;
  color: var(--muted);
}

.tab-new {
  margin: 0 0 4px 6px;
  font-size: 17px;
}

.tab-new:hover,
.fold:hover {
  color: var(--ink);
  background: var(--paper-sunken);
}

.fold {
  margin: 0 0 5px 6px;
}

.fold svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
}

.content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  min-width: 0;
}

/* 通知页 / 个人主页是工作区里的一页：自己管滚动，撑满标签下方的剩余高度 */
.page-host {
  display: flex;
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

/* 插件入口是横着的长条，不是方块：图标 | 名称+说明 | 打开 */
.cards {
  display: grid;
  gap: var(--s2);
  margin-top: var(--s5);
}

.card {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 72px;
  padding: 12px 14px;
  border: 1px solid var(--rule);
  border-radius: var(--r-lg);
  text-align: left;
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease);
}

.card:hover:not(:disabled) {
  border-color: var(--signal-line);
  background: var(--signal-soft);
}

.card:disabled {
  opacity: 0.5;
  cursor: default;
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
  flex: 0 0 auto;
  font-size: var(--t-xs);
  color: var(--signal);
}

.soon {
  margin-top: var(--s6);
  padding-top: var(--s4);
  border-top: 1px solid var(--rule);
  font-size: var(--t-xs);
  color: var(--muted-soft);
}
</style>
