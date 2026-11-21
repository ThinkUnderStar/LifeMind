<script setup lang="ts">
/**
 * 备忘。它和日程的区别是「没有时间」——所以这一栏刻意不显示任何时刻，
 * 只按置顶顺序排列，让用户一眼看出「这是我的速记本」，而不是「没排期的日程」。
 *
 * 新建用一行输入 + 回车：备忘的价值在「想到就记」，弹一个表单填四栏会让人放弃记。
 */
import { ref, watch } from 'vue'
import { pageMemos, createMemo, deleteMemo, pinMemo } from '../../api/schedule'
import type { Memo } from '../../core/types'
import { toast } from '../../core/store'

const props = defineProps<{ epoch: number }>()

const rows = ref<Memo[]>([])
const draft = ref('')
const loading = ref(false)
const busy = ref(false)

async function load() {
  loading.value = true
  try {
    const page = await pageMemos(1, 100)
    // 置顶在前，其余按后端给的更新时间顺序
    rows.value = [...page.records].sort((a, b) => (b.pinned ?? 0) - (a.pinned ?? 0))
  } catch (error) {
    toast(error instanceof Error ? error.message : '备忘没取到', 'error')
  } finally {
    loading.value = false
  }
}

async function add() {
  const title = draft.value.trim()
  if (!title || busy.value) return
  busy.value = true
  try {
    await createMemo({ title })
    draft.value = ''
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '没记上', 'error')
  } finally {
    busy.value = false
  }
}

async function togglePin(memo: Memo) {
  try {
    await pinMemo(memo.id, memo.pinned !== 1)
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '没改成', 'error')
  }
}

async function remove(memo: Memo) {
  try {
    await deleteMemo(memo.id)
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '没删掉', 'error')
  }
}

watch(() => props.epoch, load, { immediate: true })
</script>

<template>
  <div class="memo">
    <form class="new" @submit.prevent="add">
      <input
        v-model="draft"
        class="field"
        type="text"
        placeholder="记一句，回车就存"
        :disabled="busy"
      />
    </form>

    <p v-if="loading && !rows.length" class="empty">正在取…</p>

    <p v-else-if="!rows.length" class="empty">
      还没有备忘。随手记的东西放这里，它们不会变成日程，也不会提醒你。
    </p>

    <ul v-else>
      <li v-for="memo in rows" :key="memo.id" class="row">
        <button
          class="pin"
          type="button"
          :data-on="memo.pinned === 1"
          :aria-pressed="memo.pinned === 1"
          :title="memo.pinned === 1 ? '取消置顶' : '置顶'"
          @click="togglePin(memo)"
        >
          置顶
        </button>

        <div class="content">
          <p class="title">{{ memo.title }}</p>
          <p v-if="memo.content" class="body">{{ memo.content }}</p>
        </div>

        <button class="kill" type="button" title="删除" @click="remove(memo)">×</button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.memo {
  padding: var(--s3) var(--s4) var(--s6);
}

.new {
  margin-bottom: var(--s3);
}

.row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 18px;
  align-items: start;
  gap: var(--s2);
  padding: var(--s2) 0;
  border-bottom: 1px solid var(--rule);
}

.pin {
  font-size: var(--t-xs);
  color: var(--muted-soft);
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  padding: 1px 4px;
  margin-top: 1px;
}

.pin[data-on='true'] {
  color: var(--signal);
  border-color: var(--signal-line);
  background: var(--signal-soft);
}

.content {
  min-width: 0;
}

.title {
  font-size: var(--t-sm);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.body {
  margin-top: 1px;
  font-size: var(--t-xs);
  color: var(--muted);
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.kill {
  font-size: var(--t-md);
  color: var(--muted-soft);
  line-height: 1;
  opacity: 0;
}

.row:hover .kill,
.row:focus-within .kill {
  opacity: 1;
}

.kill:hover {
  color: var(--alert);
}
</style>
