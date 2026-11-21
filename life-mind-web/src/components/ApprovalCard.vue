<script setup lang="ts">
/**
 * 确认卡。它是整个界面里唯一「用力」的地方，所以形态是插入对话流的一段，
 * 不是浮在屏幕中央的模态框：用户的视线刚读完上一句，卡片就出现在同一列里。
 *
 * 竖线与右侧面板的时间脊柱共用同一图形语言——「事情在这里发生」。
 * 没有圆角、没有阴影、没有遮罩，只有一条 3px 的线。
 */
import { computed, ref, watch } from 'vue'
import { respond, state } from '../core/store'

const editMode = ref(false)
const edited = ref('')
const busy = ref(false)

const question = computed(() => state.pending?.question ?? '')
const canEdit = computed(() => state.pending?.options?.includes('edit') ?? false)

// 换了一张新的确认卡就退出编辑态，否则上一张的内容会漏到下一张
watch(
  () => state.pending,
  () => {
    editMode.value = false
    edited.value = ''
  },
)

async function decide(choice: 'approve' | 'reject') {
  if (busy.value) return
  busy.value = true
  try {
    await respond(choice)
  } finally {
    busy.value = false
  }
}

async function submitEdit() {
  if (busy.value || !edited.value.trim()) return
  busy.value = true
  try {
    await respond('approve', edited.value.trim())
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="ask" aria-live="assertive">
    <p class="kicker">需要你确认</p>
    <p class="question">{{ question }}</p>

    <template v-if="editMode">
      <textarea
        v-model="edited"
        class="field edit"
        rows="3"
        placeholder="改成你要它记住的样子"
      ></textarea>
      <div class="actions">
        <button class="btn btn-primary" type="button" :disabled="busy || !edited.trim()" @click="submitEdit">
          {{ busy ? '提交中…' : '按这个存' }}
        </button>
        <button class="btn btn-quiet" type="button" :disabled="busy" @click="editMode = false">
          返回
        </button>
      </div>
    </template>

    <template v-else>
      <div class="actions">
        <button class="btn btn-primary" type="button" :disabled="busy" @click="decide('approve')">
          {{ busy ? '执行中…' : '同意' }}
        </button>
        <button class="btn btn-quiet" type="button" :disabled="busy" @click="decide('reject')">
          不同意
        </button>
        <button v-if="canEdit" class="btn btn-text" type="button" :disabled="busy" @click="editMode = true">
          改一下内容
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ask {
  margin: var(--s4) 0 var(--s5);
  padding-left: var(--s3);
  border-left: 3px solid var(--signal);
  max-width: 40em;
}

.kicker {
  font-size: var(--t-xs);
  color: var(--signal);
  margin-bottom: var(--s2);
}

.question {
  font-size: var(--t-md);
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.edit {
  margin-top: var(--s3);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s2);
  margin-top: var(--s4);
}

.btn-text {
  padding: 6px 4px;
}
</style>
