<script setup lang="ts">
/**
 * 对话区右上角的操作组：清空当前会话 + 工作区开合。
 *
 * 放在 AppShell 中间列的顶部：独占一行的高度，行内只有一个贴右上角（工作区
 * 左边界外）的窄条盒子，既不在视口最右压住工作区，也不占满整行挤对话正文。
 * 切到插件库/通知/个人主页等独立页时按钮组也不会消失。
 * 清空按钮恒定存在、不隐藏；没有会话、没有消息或正在流式输出时禁用（点不了）。
 */
import { ref, watch } from 'vue'
import {
  backToChat,
  clearConversation,
  openWorkspace,
  state,
  toast,
  toggleWorkspace,
} from '../core/store'

const confirmingClear = ref(false)
const clearing = ref(false)

watch(
  () => state.sessionId,
  () => {
    confirmingClear.value = false
  },
)

function askClear() {
  if (state.streaming) return toast('正在回答，稍后再清空', 'error')
  if (state.sessionId === null || !state.messages.length) {
    return toast('先选一条有记录的会话', 'error')
  }
  confirmingClear.value = true
}

async function doClear() {
  if (state.sessionId === null || clearing.value) return
  clearing.value = true
  try {
    await clearConversation(state.sessionId)
    confirmingClear.value = false
    toast('已清空这个会话的记录')
  } catch (error) {
    toast(error instanceof Error ? error.message : '清空失败', 'error')
  } finally {
    clearing.value = false
  }
}

function onWorkspace() {
  if (state.mainView === 'chat') {
    toggleWorkspace()
  } else {
    backToChat()
    openWorkspace()
  }
}
</script>

<template>
  <!-- 顶部第一行：整行占位，里面只放一个贴右上角的窄条盒子，不压正文 -->
  <div class="ops-row">
    <div class="ops-box">
      <button
        class="top-op"
        type="button"
        :disabled="state.sessionId === null || !state.messages.length || state.streaming"
        @click="askClear"
      >
        清空记录
      </button>

      <button class="top-op workspace-op" type="button" title="打开或收起工作区" @click="onWorkspace">
        <svg class="work-icon" viewBox="0 0 20 20" aria-hidden="true">
          <path d="M3 4h5v5H3zM11 4h6v5h-6zM3 12h5v4H3zM11 12h6v4h-6z" />
        </svg>
        工作区
      </button>
    </div>
  </div>

  <Teleport to="body">
    <div v-if="confirmingClear" class="modal-mask" @click.self="confirmingClear = false">
      <div class="modal" role="dialog" aria-modal="true" aria-labelledby="clear-title">
        <h2 id="clear-title">清空这个会话的记录？</h2>
        <p>这条会话的全部消息会被删除，且不可恢复。会话本身保留，还能继续对话。</p>
        <div class="modal-acts">
          <button class="top-op" type="button" @click="confirmingClear = false">取消</button>
          <button class="top-op danger" type="button" :disabled="clearing" @click="doClear">
            {{ clearing ? '清空中…' : '确认清空' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
/* 顶部第一行：整行占位，但只把窄盒子推到右上角；盒子不占满整行，也不压正文 */
.ops-row {
  display: flex;
  justify-content: flex-end;
  flex: 0 0 auto;
  padding: 12px 18px 6px;
  box-sizing: border-box;
}

/* 贴右上角的窄条盒子：工作区展开时自然停在工作区左边界外，不盖标签栏 */
.ops-box {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--rule);
  border-radius: var(--r-pill);
  background: var(--paper-raised);
}

/* 两个按钮共用一套：同高、同圆角、同字号；盒内不重复描边 */
.top-op {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 11px;
  border: 0;
  border-radius: var(--r-pill);
  background: transparent;
  font-size: var(--t-xs);
  color: var(--muted);
  white-space: nowrap;
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease);
}

.top-op:hover:not(:disabled) {
  color: var(--ink);
  background: var(--paper-sunken);
}

.top-op:disabled {
  opacity: 0.5;
  cursor: default;
}

.top-op.danger:hover:not(:disabled) {
  color: var(--alert);
}

/* 弹窗里的按钮不在窄盒子里，补回描边保持独立可见 */
.modal-acts .top-op {
  border: 1px solid var(--rule);
  background: var(--paper-raised);
}

.modal-acts .top-op.danger:hover:not(:disabled) {
  border-color: var(--alert);
}

.work-icon {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* 确认弹窗：纸白实底 + 发丝边框，不用毛玻璃和发光 */
.modal-mask {
  position: fixed;
  inset: 0;
  z-index: var(--z-pop);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgb(0 0 0 / 38%);
}

.modal {
  width: min(420px, 100%);
  padding: var(--s6);
  border: 1px solid var(--rule);
  border-radius: var(--r-lg);
  background: var(--paper-raised);
  box-shadow: 0 16px 44px rgb(0 0 0 / 18%);
}

.modal h2 {
  font-size: 17px;
  font-weight: 500;
  color: var(--ink);
}

.modal p {
  margin-top: var(--s3);
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--muted);
}

.modal-acts {
  display: flex;
  justify-content: flex-end;
  gap: var(--s3);
  margin-top: var(--s5);
}
</style>
