<script setup lang="ts">
/**
 * 备忘。它和日程的区别是「没有时间」——所以这一栏刻意不显示任何时刻，
 * 只按置顶顺序排列，让用户一眼看出「这是我的速记本」，而不是「没排期的日程」。
 *
 * 新建用一行输入 + 回车：备忘的价值在「想到就记」，弹一个表单填四栏会让人放弃记。
 */
import { ref, watch } from 'vue'
import { createMemo, deleteMemo, getMemo, pageMemos, pinMemo, updateMemo } from '../../api/schedule'
import type { Memo } from '../../core/types'
import { toast } from '../../core/store'

const props = defineProps<{ epoch: number }>()

const rows = ref<Memo[]>([])
const draft = ref('')
const loading = ref(false)
const busy = ref(false)

/** 编辑中的那一条。备忘正文可能很长，列表里给全文、点开是改它，不是另开一个详情页 */
const editingId = ref<number | null>(null)
const editTitle = ref('')
const editContent = ref('')
const saving = ref(false)

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

/**
 * 新建。后端要求 content 非空（@NotBlank），标题可为空——
 * 所以这一行输入存成正文，标题取它前面一小段：用户打的本来就是「那一句话」。
 */
async function add() {
  const text = draft.value.trim()
  if (!text || busy.value) return
  busy.value = true
  try {
    await createMemo({ title: text.slice(0, 20), content: text })
    draft.value = ''
    await load()
  } catch (error) {
    toast(error instanceof Error ? error.message : '没记上', 'error')
  } finally {
    busy.value = false
  }
}

/** 打开编辑时取一次详情：列表是批量查的，编辑要的是这条最新的一版 */
async function startEdit(memo: Memo) {
  editingId.value = memo.id
  editTitle.value = memo.title
  editContent.value = memo.content ?? ''
  try {
    const fresh = await getMemo(memo.id)
    if (editingId.value !== memo.id) return
    editTitle.value = fresh.title
    editContent.value = fresh.content ?? ''
  } catch {
    // 详情取不到就用列表里的内容继续编辑，不把用户挡在门外
  }
}

function stopEdit() {
  editingId.value = null
  editTitle.value = ''
  editContent.value = ''
}

async function save() {
  if (editingId.value === null || saving.value) return
  const content = editContent.value.trim()
  if (!content) return toast('正文不能为空', 'error')
  saving.value = true
  try {
    await updateMemo({
      id: editingId.value,
      title: editTitle.value.trim() || content.slice(0, 20),
      content,
    })
    stopEdit()
    await load()
    toast('已更新')
  } catch (error) {
    toast(error instanceof Error ? error.message : '没改成', 'error')
  } finally {
    saving.value = false
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
        <template v-if="editingId === memo.id">
          <div class="edit">
            <input v-model="editTitle" class="field" type="text" placeholder="标题（可空）" />
            <textarea v-model="editContent" class="field area" rows="4" placeholder="正文"></textarea>
            <div class="acts">
              <button class="act primary" type="button" :disabled="saving" @click="save">
                {{ saving ? '保存中…' : '保存' }}
              </button>
              <button class="act" type="button" @click="stopEdit">取消</button>
            </div>
          </div>
        </template>

        <template v-else>
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

          <button class="content open" type="button" @click="startEdit(memo)">
            <span class="title">{{ memo.title }}</span>
            <span v-if="memo.content" class="body">{{ memo.content }}</span>
          </button>

          <button class="kill" type="button" title="删除" @click="remove(memo)">×</button>
        </template>
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

button.open {
  border: 0;
  background: none;
  font: inherit;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

button.open:hover .title {
  color: var(--ink-soft);
}

.edit {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.area {
  resize: vertical;
  min-height: 72px;
  line-height: 1.6;
}

.acts {
  display: flex;
  gap: var(--s3);
}

.act {
  padding: 3px 0;
  font-size: var(--t-xs);
  color: var(--muted);
}

.act:hover {
  color: var(--ink);
}

.act.primary {
  color: var(--signal);
}

.act:disabled {
  opacity: 0.5;
}

.title {
  display: block;
  font-size: var(--t-sm);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.body {
  display: block;
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
