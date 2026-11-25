<script setup lang="ts">
/**
 * 插件服务库。与「对话 + 工作区」并列的另一个顶层界面。
 *
 * 这里只做一件事：管理用户与插件服务的绑定关系。后端 /plugin/list 返回全部上架插件
 * 和「我是否启用」，/plugin/enable 写绑定；这里不碰任何插件的业务面板，
 * 也不把这些服务当成工作区的标签——解绑后工作区就看不见它。
 *
 * 日程是默认绑定的插件（后端 default_enabled=1），和普通插件一样可解绑。
 */
import { computed } from 'vue'
import { backToChat, openServicePage, state, togglePlugin } from '../core/store'
import { resolvePlugin } from '../core/plugin-registry'
import ChevronIcon from '../components/ChevronIcon.vue'

const DEFAULT_ICON = 'M4 4h5v5H4zM11 4h5v5h-5zM4 11h5v5H4zM11 11h5v5h-5z'

/** 图标用前端 manifest 里的路径；后端还没做出面板的插件给个占位方块 */
function iconOf(key: string): string {
  return resolvePlugin(key)?.icon ?? DEFAULT_ICON
}

/** 已实现面板的插件才能点进去；后端列出来但前端还没做的，只显示绑定状态 */
const readyKeys = computed(() => new Set(state.plugins.filter((p) => resolvePlugin(p.pluginKey)).map((p) => p.pluginKey)))
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
    <header class="head">
      <button class="back" type="button" @click="backToChat">
        <ChevronIcon dir="left" :size="13" />回到对话
      </button>
      <div class="head-text">
        <h1>插件服务库</h1>
        <p>绑定服务后，对话里可以调用它，工作区也能打开它的面板。解绑后工作区不再显示。</p>
      </div>
    </header>

    <div class="summary">
      上架 {{ state.plugins.length }} 个服务 · 已绑定 {{ boundCount }} 个
    </div>

    <p v-if="busy" class="hint">读取中…</p>

    <div v-else-if="!state.plugins.length" class="empty-note">
      还没有上架的服务。
    </div>

    <ul v-else class="cards">
      <li v-for="item in state.plugins" :key="item.pluginKey" class="card" :data-bound="item.enabled">
        <span class="icon">
          <svg viewBox="0 0 20 20" aria-hidden="true"><path :d="iconOf(item.pluginKey)" /></svg>
        </span>

        <div class="info">
          <div class="line">
            <strong>{{ item.name }}</strong>
            <span v-if="!readyKeys.has(item.pluginKey)" class="tag">即将开放</span>
          </div>
          <p class="desc">{{ item.description }}</p>
          <p class="meta">{{ item.pluginKey }} · v{{ item.version }}</p>
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
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: var(--s6) var(--s6) var(--s7);
  background: var(--paper);
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  gap: var(--s5);
}

.head h1 {
  font-size: 21px;
  font-weight: 500;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.head p {
  margin-top: 7px;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--muted);
}

.back {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex: 0 0 auto;
  padding: 7px 14px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  font-size: var(--t-xs);
  color: var(--muted);
  white-space: nowrap;
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease);
}

.back:hover {
  color: var(--ink);
  border-color: var(--rule-strong);
}

.summary {
  margin-top: var(--s5);
  padding-bottom: var(--s3);
  border-bottom: 1px solid var(--rule);
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

.hint,
.empty-note {
  margin-top: var(--s5);
  font-size: var(--t-sm);
  color: var(--muted);
}

.cards {
  display: grid;
  gap: var(--s2);
  margin-top: var(--s4);
}

/* 和服务入口一致：整行横条，不是方块 */
.card {
  display: flex;
  align-items: center;
  gap: var(--s4);
  padding: 14px var(--s4);
  border: 1px solid var(--rule);
  border-radius: var(--r-lg);
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease);
}

.card:hover {
  border-color: var(--rule-strong);
}

/* 解绑的条目淡下去，但绑定入口始终看得见 */
.card:not([data-bound='true']) {
  opacity: 0.66;
}

.card:not([data-bound='true']):hover {
  opacity: 1;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  border-radius: var(--r-md);
  background: var(--signal-soft);
  color: var(--signal);
}

.card:not([data-bound='true']) .icon {
  background: var(--paper-sunken);
  color: var(--muted);
}

.icon svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.info {
  flex: 1;
  min-width: 0;
}

.line {
  display: flex;
  align-items: center;
  gap: var(--s2);
}

.line strong {
  font-size: var(--t-sm);
  font-weight: 500;
  color: var(--ink);
}

.tag {
  padding: 1px 7px;
  border-radius: var(--r-pill);
  font-size: 10px;
  color: var(--muted-soft);
  background: var(--paper-sunken);
}

.desc {
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--t-xs);
  color: var(--muted);
}

.meta {
  margin-top: 3px;
  font-size: 10px;
  color: var(--muted-soft);
}

.ops {
  display: flex;
  align-items: center;
  gap: var(--s3);
  flex: 0 0 auto;
}

.enter {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: var(--t-xs);
  color: var(--signal);
}

.enter:hover {
  text-decoration: underline;
}

.toggle {
  min-width: 52px;
  padding: 6px 12px;
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

/* 已绑定：解绑是低调动作；未绑定：绑定是这张卡唯一该做的事，给信号色 */
.toggle[data-on='false'] {
  color: var(--signal);
  border-color: var(--signal-line);
}

@media (max-width: 720px) {
  .library {
    padding: var(--s5) var(--s4);
  }

  .head {
    flex-direction: column;
  }

  .card {
    flex-wrap: wrap;
    gap: var(--s3);
  }

  .ops {
    margin-left: auto;
  }
}
</style>
