<script setup lang="ts">
/**
 * 认证屏：登录 / 注册两个模式共用一套布局。
 *
 * 为什么合并成一屏而不是两个页面：项目里没有路由，切屏靠 ref 状态；
 * 两个模式字段高度重叠（用户名、密码、验证码），拆成两个组件就要把
 * 验证码刷新、错误处理、提交中禁用各写两遍，容易两边行为不一致。
 *
 * 三条正则是后端 ValidateUtils 的逐字复制，只用于「提交前给出可读提示」，
 * 真正的裁决权始终在后端——前端放过、后端拒绝时错误文案会直接透出来。
 */
import { computed, ref } from 'vue'
import CaptchaField from '../components/CaptchaField.vue'
import { register } from '../api/auth'
import { login as enter } from '../core/store'
import { ApiError } from '../api/http'

type Mode = 'login' | 'register'

const mode = ref<Mode>('login')
const busy = ref(false)
const error = ref('')
const notice = ref('')

const username = ref('')
const password = ref('')
const nickname = ref('')
const confirmPassword = ref('')
const captcha = ref('')
const captchaRef = ref<InstanceType<typeof CaptchaField> | null>(null)

const isRegister = computed(() => mode.value === 'register')

/** 字母开头，4~16 位，字母数字下划线 */
const USERNAME_RE = /^[a-zA-Z][a-zA-Z0-9_]{3,15}$/
/** 8~20 位，不能纯数字、不能纯字母 */
const PASSWORD_RE = /^(?![0-9]+$)(?![a-zA-Z]+$)[0-9A-Za-z!@#$%^&*_-]{8,20}$/
/** 1~16 位，中文 / 字母 / 数字 / 下划线 / 连字符 */
const NICKNAME_RE = /^[\u4e00-\u9fa5a-zA-Z0-9_-]{1,16}$/

function reject(message: string) {
  // 只提示，不换验证码：这些是本地格式问题，服务端那份还没被消费，仍然可用
  error.value = message
}

async function submit() {
  if (busy.value) return
  error.value = ''
  notice.value = ''

  const name = username.value.trim()
  if (!name) return reject('请填用户名')
  if (!USERNAME_RE.test(name)) return reject('用户名要字母开头、4~16 位，只能用字母、数字、下划线')
  if (!password.value) return reject('请填密码')
  if (!PASSWORD_RE.test(password.value)) return reject('密码 8~20 位，必须同时含字母和数字')

  if (isRegister.value) {
    const alias = nickname.value.trim()
    if (!alias) return reject('请填昵称')
    if (!NICKNAME_RE.test(alias)) return reject('昵称 1~16 位，可用中文、字母、数字、下划线、连字符')
    if (password.value !== confirmPassword.value) return reject('两次输入的密码不一致')
  }

  if (captcha.value.trim().length !== 5) return reject('验证码是图片上的五个字符')

  const tempKey = captchaRef.value?.tempKey
  if (!tempKey) return reject('验证码还没取到，点一下图片重新取')

  busy.value = true
  try {
    if (isRegister.value) {
      await register({
        username: name,
        password: password.value,
        confirmPassword: confirmPassword.value,
        nickname: nickname.value.trim(),
        tempKey,
        captchaCode: captcha.value.trim(),
      })
      // 后端注册只返回 Result<Void>，不签发令牌，所以把人送回登录态
      notice.value = `注册成功，用 ${name} 登录`
      mode.value = 'login'
      password.value = ''
      confirmPassword.value = ''
      nickname.value = ''
      captchaRef.value?.refresh()
    } else {
      await enter({
        username: name,
        password: password.value,
        tempKey,
        captchaCode: captcha.value.trim(),
      })
    }
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '请求没有完成，请再试一次'
    // 提交到服务端才算真正消费掉验证码（无论成败），所以这里必须换一张
    captchaRef.value?.refresh()
  } finally {
    busy.value = false
  }
}

function switchTo(next: Mode) {
  if (busy.value || mode.value === next) return
  mode.value = next
  error.value = ''
  notice.value = ''
  confirmPassword.value = ''
  captchaRef.value?.refresh()
}
</script>

<template>
  <main class="gate">
    <div class="gate-body">
      <header class="head">
        <h1>LifeMind</h1>
        <p class="lede">日程、待办和提醒，说一句就行。</p>
      </header>

      <div class="tabs" role="tablist" aria-label="登录或注册">
        <button
          type="button"
          role="tab"
          class="tab"
          :class="{ on: !isRegister }"
          :aria-selected="!isRegister"
          :disabled="busy"
          @click="switchTo('login')"
        >
          登录
        </button>
        <button
          type="button"
          role="tab"
          class="tab"
          :class="{ on: isRegister }"
          :aria-selected="isRegister"
          :disabled="busy"
          @click="switchTo('register')"
        >
          注册
        </button>
      </div>

      <form class="form" @submit.prevent="submit">
        <label class="row">
          <span class="label">用户名</span>
          <input
            v-model="username"
            class="field"
            type="text"
            autocomplete="username"
            spellcheck="false"
            placeholder="字母开头，4~16 位"
            :disabled="busy"
          />
        </label>

        <label v-if="isRegister" class="row">
          <span class="label">昵称</span>
          <input
            v-model="nickname"
            class="field"
            type="text"
            autocomplete="nickname"
            spellcheck="false"
            placeholder="怎么称呼你"
            :disabled="busy"
          />
        </label>

        <label class="row">
          <span class="label">密码</span>
          <input
            v-model="password"
            class="field"
            type="password"
            :autocomplete="isRegister ? 'new-password' : 'current-password'"
            :placeholder="isRegister ? '8~20 位，字母加数字' : ''"
            :disabled="busy"
          />
        </label>

        <label v-if="isRegister" class="row">
          <span class="label">确认密码</span>
          <input
            v-model="confirmPassword"
            class="field"
            type="password"
            autocomplete="new-password"
            :disabled="busy"
          />
        </label>

        <div class="row">
          <span class="label">验证码</span>
          <CaptchaField ref="captchaRef" v-model="captcha" :disabled="busy" />
        </div>

        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <p v-if="notice" class="notice" role="status">{{ notice }}</p>

        <button class="btn btn-primary submit" type="submit" :disabled="busy">
          {{ busy ? (isRegister ? '正在注册…' : '正在登录…') : isRegister ? '注册' : '登录' }}
        </button>
      </form>

      <p class="foot">
        {{ isRegister ? '注册后自动绑定普通用户身份，日程插件默认开启。' : '新用户请切到「注册」，验证码看不清就点图片换一张。' }}
      </p>
    </div>
  </main>
</template>

<style scoped>
.gate {
  display: grid;
  place-items: center;
  min-height: 100%;
  padding: var(--s6) var(--s4);
}

.gate-body {
  width: 100%;
  max-width: 352px;
}

.head {
  padding-bottom: var(--s5);
  border-bottom: 1px solid var(--rule);
}

h1 {
  font-size: var(--t-xl);
  letter-spacing: -0.02em;
}

.lede {
  margin-top: var(--s2);
  color: var(--muted);
  font-size: var(--t-sm);
}

.tabs {
  display: flex;
  gap: var(--s5);
  margin: var(--s4) 0 var(--s5);
}

.tab {
  position: relative;
  padding-bottom: 7px;
  color: var(--muted);
  font-size: var(--t-sm);
  transition: color var(--dur) var(--ease);
}

.tab:hover:not(:disabled) {
  color: var(--ink);
}

.tab.on {
  color: var(--ink);
  font-weight: 500;
}

.tab.on::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: var(--signal);
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--s4);
}

.row {
  display: block;
}

.label {
  display: block;
  margin-bottom: 6px;
  font-size: var(--t-xs);
  color: var(--muted);
}

.error {
  color: var(--alert);
  font-size: var(--t-sm);
  line-height: 1.5;
}

.notice {
  color: var(--signal);
  font-size: var(--t-sm);
  line-height: 1.5;
}

.submit {
  justify-content: center;
  padding: 10px 14px;
  margin-top: var(--s1);
}

.foot {
  margin-top: var(--s5);
  color: var(--muted-soft);
  font-size: var(--t-xs);
  line-height: 1.6;
}
</style>
