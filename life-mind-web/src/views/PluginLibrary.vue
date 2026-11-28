<script setup lang="ts">
/**
 * 插件服务库。与「对话 + 工作区」并列的另一个顶层界面。
 *
 * 这里只做一件事：管理用户与插件服务的绑定关系。后端 /plugin/list 返回全部上架插件
 * 和「我是否启用」，/plugin/enable 写绑定；这里不碰任何插件的业务面板，
 * 也不把这些服务当成工作区的标签——解绑后工作区就看不见它。
 *
 * 日程是默认绑定的插件（后端 default_enabled=1），和普通插件一样可解绑。
 * 页面版式走「细线 + 留白」：不用阴影、不用彩色图标块，靠文字层级区分信息。
 */
import { computed, ref } from 'vue'
import { backToChat, openServicePage, state, togglePlugin } from '../core/store'
import { resolvePlugin } from '../core/plugin-registry'
import ChevronIcon from '../components/ChevronIcon.vue'
import UiIcon from '../components/UiIcon.vue'

const query = ref('')
const keyword = computed(() => query.value.trim().toLowerCase())

/** 名称、说明、插件标识都能搜；匹配不区分大小写 */
const visiblePlugins = computed(() => {
  if (!keyword.value) return state.plugins
  return state.plugins.filter((item) =>
    [item.name, item.description, item.pluginKey].some((field) =>
      field.toLowerCase().includes(keyword.value),
    ),
  )
})

/** 已实现面板的插件才能点进去；后端列出来但前端还没做的，只显示绑定状态 */
const readyKeys = computed(
  () => new Set(state.plugins.filter((p) => resolvePlugin(p.pluginKey)).map((p) => p.pluginKey)),
)
const boundCount = computed(() => state.plugins.filter((p) => p.enabled).length)
const busy = computed(() => state.plugins.length === 0 && !state.booted)

function enter(key: string) {
  if (!readyKeys.value.has(key)) return
  backToChat()
  openServicePage(key)
}
</script>

<template>
  <section class="library">
    <button class="back" type="button" @click="backToChat">
      <ChevronIcon dir="left" :size="13" />返回对话
    </button>

    <header class="hero">
      <h1>插件服务库</h1>
    </header>

    <div class="toolbar">
      <label class="search">
        <UiIcon name="search" :size="17" />
        <input
          v-model="query"
          type="search"
          aria-label="搜索服务"
          placeholder="搜索服务名称或功能"
          autocomplete="off"
        />
        <button v-if="query" class="clear-search" type="button" aria-label="清除搜索" @click="query = ''">
          <UiIcon name="close" :size="14" />
        </button>
      </label>
      <span class="count">
        {{ keyword ? `找到 ${visiblePlugins.length} 个` : `共 ${state.plugins.length} 个 · 已启用 ${boundCount} 个` }}
      </span>
    </div>

    <p v-if="busy" class="hint">读取中…</p>

    <div v-else-if="!state.plugins.length" class="empty">
      <p>还没有上架的服务。</p>
    </div>

    <div v-else-if="!visiblePlugins.length" class="empty">
      <p>没有找到与「{{ query.trim() }}」相关的服务。</p>
      <button type="button" @click="query = ''">清除搜索</button>
    </div>

    <ul v-else class="cards">
      <li v-for="item in visiblePlugins" :key="item.pluginKey" class="card">
        <div>
          <div class="title-line">
            <h2>{{ item.name }}</h2>
            <span v-if="!readyKeys.has(item.pluginKey)" class="badge">即将开放</span>
          </div>
          <p class="desc">{{ item.description }}</p>
          <div class="foot">
            <span class="meta">{{ item.pluginKey }} · v{{ item.version }}</span>
            <span v-if="item.enabled" class="bound">已启用</span>
          </div>
        </div>

        <div class="ops">
          <button
            v-if="readyKeys.has(item.pluginKey) && item.enabled"
            class="enter"
            type="button"
            @click="enter(item.pluginKey)"
          >打开<ChevronIcon :size="13" /></button>
          <button
            class="toggle"
            type="button"
            :data-on="item.enabled"
            @click="togglePlugin(item.pluginKey, !item.enabled)"
          >
            {{ item.enabled ? '解绑' : '绑定' }}
          </button>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.library {
  display: block;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 40px clamp(24px, 5vw, 72px) 64px;
  background: var(--paper);
}

.back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 0;
  font-size: var(--t-xs);
  color: var(--muted);
  transition: color var(--dur) var(--ease);
}

.back:hover {
  color: var(--ink);
}

.hero {
  margin-top: 40px;
}

.hero h1 {
  font-size: 30px;
  font-weight: 400;
  letter-spacing: -0.02em;
  color: var(--ink);
}

.toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s5);
  margin-top: 44px;
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--rule);
}

.search {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex: 1;
  max-width: 400px;
  color: var(--muted);
}

.search input {
  flex: 1;
  min-width: 0;
  padding: 6px 0;
  border: 0;
  outline: 0;
  background: transparent;
  font: inherit;
  font-size: var(--t-md);
  color: var(--ink);
}

.search input::placeholder {
  color: var(--muted-soft);
}

.search input::-webkit-search-cancel-button {
  display: none;
}

.clear-search {
  display: inline-flex;
  padding: 0 2px;
  border: 0;
  background: transparent;
  color: var(--muted);
}

.clear-search:hover {
  color: var(--ink);
}

.count {
  flex: 0 0 auto;
  padding-bottom: 7px;
  font-size: var(--t-xs);
  color: var(--muted-soft);
  white-space: nowrap;
}

.hint,
.empty {
  margin-top: var(--s6);
  font-size: var(--t-sm);
  color: var(--muted);
}

.empty button {
  margin-top: var(--s4);
  padding: 7px 14px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  font-size: var(--t-xs);
  color: var(--signal);
}

.empty button:hover {
  border-color: var(--signal-line);
}

/* 编辑式网格：只有一条发丝分隔线，不开卡片盒子 */
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  column-gap: clamp(24px, 4vw, 64px);
}

.card {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 176px;
  padding: var(--s5) 0 var(--s4);
  border-bottom: 1px solid var(--rule);
}

.title-line {
  display: flex;
  align-items: baseline;
  gap: var(--s3);
}

.title-line h2 {
  font-size: var(--t-lg);
  font-weight: 500;
  color: var(--ink);
}

.badge {
  padding: 2px 8px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  font-size: 10px;
  color: var(--muted-soft);
  white-space: nowrap;
}

.desc {
  margin-top: var(--s3);
  font-size: var(--t-sm);
  line-height: var(--lh-body);
  color: var(--muted);
}

.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s3);
  margin-top: var(--s4);
}

.meta {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--muted-soft);
}

.bound {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--t-xs);
  color: var(--signal);
  white-space: nowrap;
}

.bound::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}

.ops {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s5);
  margin-top: var(--s4);
}

.enter {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: var(--t-xs);
  color: var(--ink);
}

.enter:hover {
  color: var(--signal);
}

.toggle {
  min-width: 64px;
  padding: 7px 14px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  font-size: var(--t-xs);
  color: var(--muted);
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease);
}

.toggle:hover {
  color: var(--ink);
  border-color: var(--rule-strong);
}

/* 未绑定时，绑定是这张卡唯一该做的事，给信号色 */
.toggle[data-on='false'] {
  color: var(--signal);
  border-color: var(--signal-line);
}

@media (max-width: 720px) {
  .library {
    padding: var(--s5) var(--s4) var(--s6);
  }

  .hero {
    margin-top: var(--s5);
  }

  .hero h1 {
    font-size: 24px;
  }

  .toolbar {
    margin-top: var(--s5);
  }

  .cards {
    grid-template-columns: 1fr;
  }
}
</style>
