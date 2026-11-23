<script setup lang="ts">
/**
 * 个人主页。住在右侧工作区，从左侧栏底部的账号进入。
 *
 * 资料以 `GET /user/profile` 为准（昵称、绑定情况、头像都是账号服务里的真实数据），
 * 不再拿登录时记的账户名充数。
 *
 * 能改的四样：昵称、头像、账户名、密码。手机号与邮箱的绑定要下短信 / 邮件验证码，
 * 是另一条链路，这里只展示当前绑定状态，不做半截的入口。
 */
import { computed, onMounted, ref } from 'vue'
import { logout, setAccount, toast } from '../core/store'
import * as userApi from '../api/user'
import type { UserProfile } from '../api/user'

type Editing = '' | 'nickname' | 'username' | 'password'

const profile = ref<UserProfile | null>(null)
const loading = ref(true)
const failed = ref('')

const editing = ref<Editing>('')
const saving = ref(false)
const draft = ref({ nickname: '', username: '', original: '', next: '', confirm: '' })

const avatarInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)

const title = computed(() => profile.value?.nickname || profile.value?.username || '')
const initial = computed(() => (title.value || '我').trim().charAt(0).toUpperCase())

/** 账户名一年只能改一次：没到一年就把入口关掉，别让人白填一遍表单 */
const usernameLocked = computed(() => {
  const raw = profile.value?.usernameChangeTime
  if (!raw) return false
  const last = new Date(raw.replace(' ', 'T'))
  if (Number.isNaN(last.getTime())) return false
  return Date.now() - last.getTime() < 365 * 24 * 3600 * 1000
})

const lockHint = computed(() => {
  const raw = profile.value?.usernameChangeTime
  if (!raw) return ''
  const last = new Date(raw.replace(' ', 'T'))
  const days = Math.ceil((last.getTime() + 365 * 24 * 3600 * 1000 - Date.now()) / 86400000)
  return `账户名一年只能改一次，还需等待 ${Math.max(days, 1)} 天`
})

async function load() {
  loading.value = true
  failed.value = ''
  try {
    profile.value = await userApi.getProfile()
  } catch (error) {
    failed.value = error instanceof Error ? error.message : '资料读取失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

function start(which: Editing) {
  editing.value = which
  if (which === 'nickname') draft.value.nickname = profile.value?.nickname ?? ''
  if (which === 'username') draft.value.username = profile.value?.username ?? ''
  if (which === 'password') draft.value.original = draft.value.next = draft.value.confirm = ''
}

function cancel() {
  editing.value = ''
}

const NICKNAME_RE = /^[\u4e00-\u9fa5a-zA-Z0-9_-]{1,16}$/
const USERNAME_RE = /^[a-zA-Z][a-zA-Z0-9_]{3,15}$/
const PASSWORD_RE = /^(?![0-9]+$)(?![a-zA-Z]+$)[0-9A-Za-z!@#$%^&*_-]{8,20}$/

function fail(text: string) {
  toast(text, 'error')
}

async function saveNickname() {
  const value = draft.value.nickname.trim()
  if (!NICKNAME_RE.test(value)) return fail('昵称 1~16 位，只能用中文、字母、数字、下划线、连字符')
  saving.value = true
  try {
    await userApi.changeNickname(value)
    if (profile.value) profile.value.nickname = value
    editing.value = ''
    toast('昵称已更新')
  } catch (error) {
    fail(error instanceof Error ? error.message : '修改失败')
  } finally {
    saving.value = false
  }
}

async function saveUsername() {
  const value = draft.value.username.trim()
  if (!USERNAME_RE.test(value)) return fail('账户名 4~16 位，字母开头，只能用字母、数字、下划线')
  if (value === profile.value?.username) return fail('新账户名与当前相同')
  saving.value = true
  try {
    await userApi.changeUsername(value)
    if (profile.value) {
      profile.value.username = value
      profile.value.usernameChangeTime = new Date().toISOString()
    }
    setAccount(value)
    editing.value = ''
    toast('账户名已更新，下次登录请用新名字')
  } catch (error) {
    fail(error instanceof Error ? error.message : '修改失败')
  } finally {
    saving.value = false
  }
}

async function savePassword() {
  const { original, next, confirm } = draft.value
  if (!original) return fail('请输入当前密码')
  if (!PASSWORD_RE.test(next)) return fail('新密码 8~20 位，需同时包含字母和数字')
  if (next !== confirm) return fail('两次输入的新密码不一致')
  saving.value = true
  try {
    await userApi.changePasswordByOriginal({
      originalPassword: original,
      newPassword: next,
      confirmPassword: confirm,
    })
    // 改密会让所有令牌作废，留着也只会一路 401，直接回登录页
    toast('密码已修改，请用新密码重新登录')
    logout()
  } catch (error) {
    fail(error instanceof Error ? error.message : '修改失败')
  } finally {
    saving.value = false
  }
}

async function pickAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 2 * 1024 * 1024) return fail('头像不能超过 2MB')
  uploading.value = true
  try {
    await userApi.changeAvatar(file)
    await load()
    toast('头像已更新')
  } catch (error) {
    fail(error instanceof Error ? error.message : '上传失败')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <section class="profile">
    <p v-if="loading" class="hint">读取中…</p>
    <div v-else-if="failed" class="hint">
      {{ failed }}
      <button type="button" class="act" @click="load">重试</button>
    </div>

    <template v-else-if="profile">
      <header class="ident">
        <button
          class="avatar"
          type="button"
          :disabled="uploading"
          title="更换头像"
          @click="avatarInput?.click()"
        >
          <img v-if="profile.avatar" :src="profile.avatar" alt="" />
          <span v-else>{{ initial }}</span>
        </button>
        <div class="who">
          <p class="name">{{ title }}</p>
          <button class="act small" type="button" :disabled="uploading" @click="avatarInput?.click()">
            {{ uploading ? '上传中…' : '更换头像' }}
          </button>
        </div>
        <input ref="avatarInput" class="file" type="file" accept="image/*" @change="pickAvatar" />
      </header>

      <dl class="rows">
        <div class="row">
          <dt>昵称</dt>
          <template v-if="editing === 'nickname'">
            <input
              v-model="draft.nickname"
              class="field"
              maxlength="16"
              placeholder="1~16 位"
              @keydown.enter="saveNickname"
              @keydown.esc="cancel"
            />
            <button class="act on" type="button" :disabled="saving" @click="saveNickname">保存</button>
            <button class="act" type="button" @click="cancel">取消</button>
          </template>
          <template v-else>
            <dd>{{ profile.nickname || '未设置' }}</dd>
            <button class="act" type="button" @click="start('nickname')">修改</button>
          </template>
        </div>

        <div class="row">
          <dt>账户名</dt>
          <template v-if="editing === 'username'">
            <input
              v-model="draft.username"
              class="field"
              maxlength="16"
              placeholder="字母开头，4~16 位"
              @keydown.enter="saveUsername"
              @keydown.esc="cancel"
            />
            <button class="act on" type="button" :disabled="saving" @click="saveUsername">保存</button>
            <button class="act" type="button" @click="cancel">取消</button>
          </template>
          <template v-else>
            <dd>{{ profile.username }}</dd>
            <button
              class="act"
              type="button"
              :disabled="usernameLocked"
              :title="usernameLocked ? lockHint : '一年可以改一次'"
              @click="start('username')"
            >
              {{ usernameLocked ? '一年内已改' : '修改' }}
            </button>
          </template>
        </div>

        <div class="row">
          <dt>手机</dt>
          <dd>{{ profile.phone || '未绑定' }}</dd>
        </div>

        <div class="row">
          <dt>邮箱</dt>
          <dd>{{ profile.email || '未绑定' }}</dd>
        </div>

        <div class="row">
          <dt>登录密码</dt>
          <template v-if="editing === 'password'">
            <span class="pad"></span>
            <button class="act on" type="button" :disabled="saving" @click="savePassword">保存</button>
            <button class="act" type="button" @click="cancel">取消</button>
          </template>
          <template v-else>
            <dd>••••••</dd>
            <button class="act" type="button" @click="start('password')">修改</button>
          </template>
        </div>
      </dl>

      <div v-if="editing === 'password'" class="pw">
        <input v-model="draft.original" class="field wide" type="password" placeholder="当前密码" autocomplete="current-password" />
        <input v-model="draft.next" class="field wide" type="password" placeholder="新密码，8~20 位且含字母和数字" autocomplete="new-password" />
        <input
          v-model="draft.confirm"
          class="field wide"
          type="password"
          placeholder="确认新密码"
          autocomplete="new-password"
          @keydown.enter="savePassword"
        />
      </div>

      <p v-if="editing === 'username'" class="note">账户名是登录名，改完下次登录要用新名字；一年只能改一次。</p>

      <button class="quit" type="button" @click="logout">退出登录</button>
    </template>
  </section>
</template>

<style scoped>
.profile {
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow-y: auto;
  padding: var(--s5) var(--s4) var(--s6);
}

.hint {
  display: flex;
  align-items: center;
  gap: var(--s2);
  font-size: var(--t-sm);
  color: var(--muted);
}

.ident {
  display: flex;
  align-items: center;
  gap: 11px;
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 44px;
  height: 44px;
  overflow: hidden;
  border-radius: 50%;
  background: var(--paper-sunken);
  color: var(--ink-soft);
  font-size: var(--t-md);
  line-height: 1;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.who {
  min-width: 0;
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--t-md);
  font-weight: 500;
  color: var(--ink);
}

.file {
  display: none;
}

/* 一行一条：左标签、中间值或输入框、右侧文字按钮 */
.rows {
  margin-top: var(--s5);
}

.row {
  display: flex;
  align-items: center;
  gap: var(--s2);
  min-height: 40px;
  padding: 6px 0;
  font-size: var(--t-sm);
}

.row + .row {
  border-top: 1px solid var(--rule);
}

.row dt {
  flex: 0 0 64px;
  color: var(--muted);
}

.row dd {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-soft);
}

.pad {
  flex: 1;
}

.field {
  flex: 1;
  min-width: 0;
  padding: 6px 9px;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  font-size: var(--t-sm);
  color: var(--ink);
  background: var(--paper);
}

.field:focus {
  border-color: var(--rule-strong);
}

.field.wide {
  width: 100%;
}

.pw {
  display: flex;
  flex-direction: column;
  gap: var(--s2);
  margin-top: var(--s3);
}

.act {
  flex: 0 0 auto;
  padding: 2px 0;
  font-size: var(--t-xs);
  color: var(--muted);
  transition: color var(--dur) var(--ease);
}

.act:hover:not(:disabled) {
  color: var(--ink);
}

.act.on {
  color: var(--signal);
}

.act.small {
  margin-top: 2px;
  font-size: var(--t-xs);
  color: var(--muted-soft);
}

.act:disabled {
  color: var(--muted-soft);
  cursor: default;
}

.note {
  margin-top: var(--s3);
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--muted-soft);
}

.quit {
  margin-top: var(--s5);
  padding: 2px 0;
  font-size: var(--t-sm);
  color: var(--muted);
  transition: color var(--dur) var(--ease);
}

.quit:hover {
  color: var(--alert);
}
</style>
