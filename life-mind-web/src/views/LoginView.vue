<script setup lang="ts">
/**
 * 登录。后端密码登录强制要图形验证码，所以这一屏不是「用户名+密码」两行就够：
 * tempKey 由前端生成（后端用 uuidValidate 校验，格式不对直接拒），
 * 验证码图片请求时带上它，登录时把 tempKey 和用户输入的四个字符一起回传。
 */
import { onMounted, ref } from 'vue'
import { captchaImageUrl, newTempKey } from '../api/auth'
import { login } from '../core/store'
import { ApiError } from '../api/http'

const username = ref('')
const password = ref('')
const captcha = ref('')
const tempKey = ref(newTempKey())
const error = ref('')
const busy = ref(false)
// 后端没起或验证码接口异常时，<img> 会露出 alt 文字把按钮撑破，这里换成可点的占位
const captchaFailed = ref(false)

function refreshCaptcha() {
  // 换 key 而不是复用：同一 key 重取会覆盖服务端存的答案，用户看到新图却提交旧答案
  tempKey.value = newTempKey()
  captcha.value = ''
  captchaFailed.value = false
}

async function submit() {
  if (busy.value) return
  error.value = ''

  if (!username.value.trim()) {
    error.value = '请填用户名'
    return
  }
  if (!password.value) {
    error.value = '请填密码'
    return
  }
  if (captcha.value.trim().length < 4) {
    error.value = '验证码是图片上的四个字符'
    return
  }

  busy.value = true
  try {
    await login({
      username: username.value.trim(),
      password: password.value,
      tempKey: tempKey.value,
      captchaCode: captcha.value.trim(),
    })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '登录没有成功，请再试一次'
    refreshCaptcha()
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  // 首屏就把图取好，用户看到表单时验证码已就位
  refreshCaptcha()
})
</script>

<template>
  <main class="gate">
    <div class="gate-body">
      <header class="head">
        <h1>LifeMind</h1>
        <p class="lede">日程、待办和提醒，说一句就行。</p>
      </header>

      <form class="form" @submit.prevent="submit">
        <label class="row">
          <span class="label">用户名</span>
          <input
            v-model="username"
            class="field"
            type="text"
            autocomplete="username"
            spellcheck="false"
            :disabled="busy"
          />
        </label>

        <label class="row">
          <span class="label">密码</span>
          <input
            v-model="password"
            class="field"
            type="password"
            autocomplete="current-password"
            :disabled="busy"
          />
        </label>

        <label class="row">
          <span class="label">验证码</span>
          <div class="captcha">
            <input
              v-model="captcha"
              class="field"
              type="text"
              inputmode="text"
              maxlength="6"
              autocomplete="off"
              spellcheck="false"
              :disabled="busy"
            />
            <button
              type="button"
              class="captcha-img"
              title="看不清，换一张"
              :disabled="busy"
              @click="refreshCaptcha"
            >
              <img
                v-if="!captchaFailed"
                :src="captchaImageUrl(tempKey)"
                alt="图形验证码"
                @error="captchaFailed = true"
              />
              <span v-else class="captcha-fail">点击取图</span>
            </button>
          </div>
        </label>

        <p v-if="error" class="error" role="alert">{{ error }}</p>

        <button class="btn btn-primary submit" type="submit" :disabled="busy">
          {{ busy ? '正在登录…' : '登录' }}
        </button>
      </form>

      <p class="foot">没有账号就用注册接口建一个，这一版界面先只做登录。</p>
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
  margin-bottom: var(--s5);
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

.captcha {
  display: flex;
  gap: var(--s2);
}

.captcha-img {
  flex: 0 0 auto;
  width: 92px;
  height: 37px;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  overflow: hidden;
  background: var(--paper-raised);
}

.captcha-img img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.captcha-fail {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  font-size: var(--t-xs);
  color: var(--muted);
}

.error {
  color: var(--alert);
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
