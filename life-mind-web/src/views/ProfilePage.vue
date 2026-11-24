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
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { logout, openFeedbackWorkspace, setAccount, toast } from '../core/store'
import * as userApi from '../api/user'
import * as authApi from '../api/auth'
import type { UserProfile } from '../api/user'

type Editing = '' | 'nickname' | 'username' | 'password' | 'phone' | 'email'
/** 改密的三种证明方式：记得原密码、能收到短信、能收到邮件 */
type PwMode = 'original' | 'phone' | 'email'

const profile = ref<UserProfile | null>(null)
const loading = ref(true)
const failed = ref('')

const editing = ref<Editing>('')
const saving = ref(false)
const draft = ref({ nickname: '', username: '', original: '', next: '', confirm: '' })

/** 绑定 / 换绑：验证码发到**新**号码（后端拿 value 去验码），不是旧号码 */
const contactDraft = ref('')
const codeDraft = ref('')
const sending = ref(false)
const countdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

const pwMode = ref<PwMode>('original')
const pwCode = ref('')
const pwNext = ref('')
const pwConfirm = ref('')

/** 注销是软删除且不可逆，做成两段：第一次只把按钮换成确认 */
const armingKill = ref(false)
const killing = ref(false)

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
  contactDraft.value = ''
  codeDraft.value = ''
  pwCode.value = ''
  pwNext.value = ''
  pwConfirm.value = ''
  pwMode.value = 'original'
  stopTimer()
  if (which === 'nickname') draft.value.nickname = profile.value?.nickname ?? ''
  if (which === 'username') draft.value.username = profile.value?.username ?? ''
  if (which === 'password') draft.value.original = draft.value.next = draft.value.confirm = ''
}

function cancel() {
  editing.value = ''
  armingKill.value = false
  stopTimer()
}

function stopTimer() {
  if (timer) clearInterval(timer)
  timer = null
  countdown.value = 0
}

function tick() {
  countdown.value = 60
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) stopTimer()
  }, 1000)
}

onBeforeUnmount(stopTimer)


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

/* ---------------- 绑定 / 换绑 ---------------- */

const PHONE_RE = /^1[3-9]\d{9}$/
const EMAIL_RE = /^[a-zA-Z0-9_.-]+@[a-zA-Z0-9-]+(\.[a-zA-Z0-9]+)*\.[a-zA-Z0-9]{2,6}$/

async function sendBindCode() {
  if (sending.value || countdown.value > 0) return
  const target = contactDraft.value.trim()
  const isPhone = editing.value === 'phone'
  if (!target) return fail(isPhone ? '先填新手机号' : '先填新邮箱')
  if (isPhone && !PHONE_RE.test(target)) return fail('手机号格式不对')
  if (!isPhone && !EMAIL_RE.test(target)) return fail('邮箱格式不对')

  sending.value = true
  try {
    if (isPhone) await authApi.sendPhoneCode(target)
    else await authApi.sendEmailCode(target)
    tick()
    toast('验证码已发出，请查看' + (isPhone ? '手机短信' : '邮箱'))
  } catch (error) {
    fail(error instanceof Error ? error.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function saveContact() {
  const target = contactDraft.value.trim()
  const code = codeDraft.value.trim()
  const isPhone = editing.value === 'phone'
  if (!target) return fail(isPhone ? '请填手机号' : '请填邮箱')
  if (isPhone && !PHONE_RE.test(target)) return fail('手机号格式不对')
  if (!isPhone && !EMAIL_RE.test(target)) return fail('邮箱格式不对')
  if (!code) return fail('请填收到的验证码')

  const bound = isPhone ? profile.value?.phone : profile.value?.email
  saving.value = true
  try {
    if (isPhone) {
      if (bound) await userApi.rebindPhone(target, code)
      else await userApi.bindPhone(target, code)
    } else {
      if (bound) await userApi.rebindEmail(target, code)
      else await userApi.bindEmail(target, code)
    }
    editing.value = ''
    stopTimer()
    await load()
    toast(bound ? '已换绑' : '已绑定')
  } catch (error) {
    fail(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

/* ---------------- 用验证码改密 ---------------- */

async function sendPasswordCode() {
  if (sending.value || countdown.value > 0) return
  sending.value = true
  try {
    if (pwMode.value === 'phone') {
      const phone = profile.value?.phone
      if (!phone) return fail('还没绑定手机号')
      await authApi.sendPhoneCode(phone)
    } else {
      const email = profile.value?.email
      if (!email) return fail('还没绑定邮箱')
      await authApi.sendEmailCode(email)
    }
    tick()
    toast('验证码已发出')
  } catch (error) {
    fail(error instanceof Error ? error.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function savePasswordByCode() {
  if (!pwCode.value.trim()) return fail('请填验证码')
  if (!PASSWORD_RE.test(pwNext.value)) return fail('新密码 8~20 位，需同时包含字母和数字')
  if (pwNext.value !== pwConfirm.value) return fail('两次输入的新密码不一致')

  saving.value = true
  try {
    const payload = {
      code: pwCode.value.trim(),
      newPassword: pwNext.value,
      confirmPassword: pwConfirm.value,
    }
    if (pwMode.value === 'phone') await userApi.changePasswordByPhone(payload)
    else await userApi.changePasswordByEmail(payload)
    toast('密码已修改，请用新密码重新登录')
    logout()
  } catch (error) {
    fail(error instanceof Error ? error.message : '修改失败')
  } finally {
    saving.value = false
  }
}

/* ---------------- 注销 ---------------- */

async function deactivate() {
  if (!armingKill.value) {
    armingKill.value = true
    return
  }
  killing.value = true
  try {
    await authApi.deactivateAccount()
    toast('账号已注销')
    logout()
  } catch (error) {
    fail(error instanceof Error ? error.message : '注销失败')
    armingKill.value = false
  } finally {
    killing.value = false
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
          <button class="act" type="button" @click="start('phone')">
            {{ profile.phone ? '换绑' : '绑定' }}
          </button>
        </div>

        <div class="row">
          <dt>邮箱</dt>
          <dd>{{ profile.email || '未绑定' }}</dd>
          <button class="act" type="button" @click="start('email')">
            {{ profile.email ? '换绑' : '绑定' }}
          </button>
        </div>

        <div class="row">
          <dt>登录密码</dt>
          <template v-if="editing === 'password'">
            <span class="pad"></span>
            <button class="act" type="button" @click="cancel">收起</button>
          </template>
          <template v-else>
            <dd>••••••</dd>
            <button class="act" type="button" @click="start('password')">修改</button>
          </template>
        </div>
      </dl>

      <!-- 绑定 / 换绑：验证码发到新号码，先证明它是你的，再落库 -->
      <div v-if="editing === 'phone' || editing === 'email'" class="pw">
        <input
          v-model="contactDraft"
          class="field wide"
          :type="editing === 'phone' ? 'tel' : 'email'"
          :placeholder="editing === 'phone' ? '新手机号' : '新邮箱'"
          autocomplete="off"
        />
        <div class="code-line">
          <input
            v-model="codeDraft"
            class="field"
            type="text"
            inputmode="numeric"
            maxlength="6"
            placeholder="验证码"
          />
          <button
            class="act on"
            type="button"
            :disabled="saving || sending || countdown > 0"
            @click="sendBindCode"
          >
            {{ countdown > 0 ? `${countdown}s` : sending ? '发送中' : '发送' }}
          </button>
        </div>
        <p class="note">
          验证码发到新的{{ editing === 'phone' ? '手机号' : '邮箱' }}，用来证明它归你所有。
        </p>
        <div class="pw-acts">
          <button class="act on" type="button" :disabled="saving" @click="saveContact">
            {{ saving ? '保存中…' : '保存' }}
          </button>
          <button class="act" type="button" @click="cancel">取消</button>
        </div>
      </div>

      <!-- 改密：原密码 / 短信 / 邮箱三条路。没绑定的那条直接禁用，不等点了才报错 -->
      <div v-if="editing === 'password'" class="pw">
        <nav class="ways" aria-label="验证方式">
          <button type="button" :aria-current="pwMode === 'original'" @click="pwMode = 'original'">原密码</button>
          <button
            type="button"
            :aria-current="pwMode === 'phone'"
            :disabled="!profile.phone"
            :title="profile.phone ? '' : '还没绑定手机号'"
            @click="pwMode = 'phone'"
          >
            短信
          </button>
          <button
            type="button"
            :aria-current="pwMode === 'email'"
            :disabled="!profile.email"
            :title="profile.email ? '' : '还没绑定邮箱'"
            @click="pwMode = 'email'"
          >
            邮箱
          </button>
        </nav>

        <template v-if="pwMode === 'original'">
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
        </template>

        <template v-else>
          <div class="code-line">
            <input v-model="pwCode" class="field" type="text" inputmode="numeric" maxlength="6" placeholder="验证码" />
            <button
              class="act on"
              type="button"
              :disabled="saving || sending || countdown > 0"
              @click="sendPasswordCode"
            >
              {{ countdown > 0 ? `${countdown}s` : sending ? '发送中' : '发送' }}
            </button>
          </div>
          <input v-model="pwNext" class="field wide" type="password" placeholder="新密码，8~20 位且含字母和数字" autocomplete="new-password" />
          <input v-model="pwConfirm" class="field wide" type="password" placeholder="确认新密码" autocomplete="new-password" />
        </template>

        <div class="pw-acts">
          <button
            class="act on"
            type="button"
            :disabled="saving"
            @click="pwMode === 'original' ? savePassword() : savePasswordByCode()"
          >
            {{ saving ? '保存中…' : '保存' }}
          </button>
          <button class="act" type="button" @click="cancel">取消</button>
        </div>
      </div>

      <p v-if="editing === 'username'" class="note">账户名是登录名，改完下次登录要用新名字；一年只能改一次。</p>

      <button class="feedback-link" type="button" @click="openFeedbackWorkspace">意见反馈</button>

      <div class="signout">
        <button class="quit" type="button" @click="logout">退出登录</button>
        <button class="quit danger" type="button" :disabled="killing" @click="deactivate">
          {{ armingKill ? (killing ? '注销中…' : '再点一次确认注销') : '注销账号' }}
        </button>
      </div>
      <p v-if="armingKill" class="note warn">
        注销后账号会被关闭，登录名与绑定信息一并解除，日程与记忆记录不再可用。这一步不可恢复。
      </p>
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
  padding: 2px 0;
  font-size: var(--t-sm);
  color: var(--muted);
  transition: color var(--dur) var(--ease);
}

.quit:hover {
  color: var(--alert);
}

/* 反馈是「帮忙改好它」的入口，语气比设置轻，所以放在退出登录上面单占一行 */
.feedback-link {
  display: block;
  margin-top: var(--s5);
  padding: 2px 0;
  font-size: var(--t-sm);
  color: var(--muted);
}

.feedback-link:hover {
  color: var(--ink);
}

/* 退出登录与注销并排：注销更危险，颜色更淡，不是主按钮 */
.signout {
  display: flex;
  align-items: center;
  gap: var(--s5);
  margin-top: var(--s4);
}

.quit.danger {
  color: var(--muted-soft);
}

.quit.danger:hover:not(:disabled) {
  color: var(--alert);
}

.quit:disabled {
  opacity: 0.6;
}

/* 验证码一行：输入框吃掉剩余宽度，按钮与字段同高 */
.code-line {
  display: flex;
  align-items: center;
  gap: var(--s2);
}

.code-line .field {
  flex: 1;
  min-width: 0;
}

.code-line .act {
  padding: 6px 0;
}

.pw-acts {
  display: flex;
  align-items: center;
  gap: var(--s3);
  margin-top: 2px;
}

/* 改密方式：三个词并排，选中压一道线；未绑定的那条淡掉并禁用 */
.ways {
  display: flex;
  gap: var(--s4);
  padding-bottom: var(--s2);
  border-bottom: 1px solid var(--rule);
}

.ways button {
  padding: 2px 0;
  font-size: var(--t-xs);
  color: var(--muted-soft);
  border-bottom: 1px solid transparent;
}

.ways button[aria-current='true'] {
  color: var(--ink);
  border-bottom-color: var(--ink);
}

.ways button:disabled {
  opacity: 0.4;
  cursor: default;
}

.note.warn {
  color: var(--alert);
}
</style>
