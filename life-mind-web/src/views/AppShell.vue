<script setup lang="ts">
/**
 * 外壳：顶栏 + 三栏。三栏的顺序是有理由的，不是布局惯性：
 * 会话在左（找过去），对话在中（现在），插件面板在右（数据长什么样）。
 * agent 推 ui 帧时动的是右边那一栏，用户视线不用离开对话。
 *
 * 窄屏不是把三栏等比缩小——那样每栏都不可用。1024 以下改成单栏 + 底部三段切换，
 * 一次只给一件事。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { logout, pollNotices, state, dismissNotice } from '../core/store'
import SessionRail from './SessionRail.vue'
import ChatColumn from './ChatColumn.vue'
import PanelHost from './PanelHost.vue'
import ToastLayer from '../components/ToastLayer.vue'
import NoticeLayer from '../components/NoticeLayer.vue'

const pane = ref<'rail' | 'chat' | 'panel'>('chat')
let timer: number | null = null

const popupCount = computed(() => state.notices.length)

function useNotice() {
  const first = state.notices[0]
  if (first) void dismissNotice(first.id)
}

onMounted(() => {
  void pollNotices()
  // 30 秒一次：提醒的精度是分钟级，更密只是多打后端
  timer = window.setInterval(() => void pollNotices(), 30_000)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
})
</script>

<template>
  <div class="shell" :data-pane="pane">
    <header class="bar">
      <div class="brand">
        <span class="mark" aria-hidden="true"></span>
        <span class="name">LifeMind</span>
      </div>

      <div class="bar-right">
        <button v-if="popupCount" class="bell" type="button" @click="useNotice">
          有 {{ popupCount }} 条提醒
        </button>
        <span class="who">{{ state.account || '已登录' }}</span>
        <button class="btn btn-text" type="button" @click="logout">退出</button>
      </div>
    </header>

    <div class="main">
      <SessionRail class="rail" />
      <ChatColumn class="chat" />
      <PanelHost class="panel" />
    </div>

    <nav class="tabs" aria-label="切换区域">
      <button type="button" :aria-current="pane === 'rail'" @click="pane = 'rail'">会话</button>
      <button type="button" :aria-current="pane === 'chat'" @click="pane = 'chat'">对话</button>
      <button type="button" :aria-current="pane === 'panel'" @click="pane = 'panel'">日程</button>
    </nav>

    <ToastLayer />
    <NoticeLayer />
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-rows: var(--h-bar) minmax(0, 1fr);
  height: 100%;
  overflow: hidden;
}

.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s4);
  padding: 0 var(--s4);
  border-bottom: 1px solid var(--rule);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--s2);
}

/* 一个小方块，里面一条竖线——和面板里的时间脊柱是同一个图形语言 */
.mark {
  position: relative;
  width: 11px;
  height: 11px;
  border: 1px solid var(--ink);
  border-radius: 1px;
}

.mark::after {
  content: '';
  position: absolute;
  left: 4px;
  top: -3px;
  bottom: -3px;
  width: 1px;
  background: var(--ink);
}

.name {
  font-size: var(--t-sm);
  font-weight: 600;
  letter-spacing: 0.01em;
}

.bar-right {
  display: flex;
  align-items: center;
  gap: var(--s3);
}

.bell {
  font-size: var(--t-xs);
  color: var(--signal);
  border: 1px solid var(--signal-line);
  background: var(--signal-soft);
  border-radius: 999px;
  padding: 3px 10px;
}

.who {
  font-size: var(--t-xs);
  color: var(--muted);
}

.main {
  display: grid;
  grid-template-columns: var(--w-rail) minmax(0, 1fr) var(--w-panel);
  min-height: 0;
  overflow: hidden;
}

.tabs {
  display: none;
}

/*
 * 1180 以下换成单栏 + 底部三段切换。为什么不是「等比缩小的三栏」：
 * 236 + 372 的固定宽度一压，对话区会剩不到 300px，三栏同时不可用。
 * 换成一次只给一件事，每件事都还有完整宽度。
 */
@media (max-width: 1180px) {
  .shell {
    grid-template-rows: var(--h-bar) minmax(0, 1fr) auto;
  }

  .main {
    grid-template-columns: minmax(0, 1fr);
  }

  .rail,
  .panel {
    display: none;
  }

  .shell[data-pane='rail'] .rail,
  .shell[data-pane='panel'] .panel {
    display: flex;
  }

  .shell[data-pane='rail'] .chat,
  .shell[data-pane='panel'] .chat {
    display: none;
  }

  .tabs {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    border-top: 1px solid var(--rule);
    background: var(--paper-raised);
    padding-bottom: env(safe-area-inset-bottom);
  }

  .tabs button {
    padding: 11px 0;
    font-size: var(--t-xs);
    color: var(--muted);
  }

  .tabs button[aria-current='true'] {
    color: var(--ink);
    box-shadow: inset 0 -2px 0 var(--signal);
  }
}
</style>
