<script setup lang="ts">
/**
 * 图形验证码输入框 + 图片。登录和注册共用一份，避免两处各写一遍再各自写错。
 *
 * 这里有一个必须避开的坑：图片地址**不能**写成模板里的
 * `:src="captchaImageUrl(tempKey)"`。那个函数带 `t=Date.now()`，
 * 只要组件因为任何原因重渲染（比如用户每敲一个字符），表达式就会重新求值，
 * URL 一变浏览器就去重取图片，而服务端会拿**同一个 tempKey** 重新生成答案并覆盖 Redis 里那份。
 * 表现就是「输入一位，图就换一张，怎么输都不对」。
 *
 * 所以地址只在 refresh() 里算一次存进 src，重渲染不会让它变。
 * 父组件通过组件 ref 读 tempKey 提交，提交失败后再调 refresh() 换新。
 */
import { onMounted, ref } from 'vue'
import { captchaImageUrl, newTempKey } from '../api/auth'

withDefaults(defineProps<{ disabled?: boolean }>(), { disabled: false })

const code = defineModel<string>({ default: '' })

const tempKey = ref('')
const src = ref('')
const failed = ref(false)
const pending = ref(true)

function refresh() {
  // 换 key 而不是复用：同一 key 重取会覆盖服务端存的答案
  tempKey.value = newTempKey()
  src.value = captchaImageUrl(tempKey.value)
  code.value = ''
  failed.value = false
  pending.value = true
}

function onLoad() {
  pending.value = false
}

function onError() {
  // 后端没起或网关不通时，宁可给一个可点的占位，也不要让 alt 文字把布局撑破
  pending.value = false
  failed.value = true
}

defineExpose({ tempKey, refresh })
onMounted(refresh)
</script>

<template>
  <div class="captcha">
    <input
      v-model="code"
      class="field"
      type="text"
      maxlength="5"
      autocomplete="off"
      autocapitalize="off"
      spellcheck="false"
      placeholder="图片上五个字符"
      aria-label="图形验证码"
      :disabled="disabled"
    />
    <button
      type="button"
      class="shot"
      :title="failed ? '点一下重新取图' : '看不清？换一张'"
      :disabled="disabled"
      @click="refresh"
    >
      <img v-if="src && !failed" :src="src" alt="图形验证码" @load="onLoad" @error="onError" />
      <span v-else class="fallback">{{ pending ? '取图中' : '重新取图' }}</span>
    </button>
  </div>
</template>

<style scoped>
.captcha {
  display: flex;
  gap: var(--s2);
}

.shot {
  flex: 0 0 auto;
  width: 104px;
  height: 37px;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  overflow: hidden;
  background: var(--paper-raised);
  transition: border-color var(--dur) var(--ease);
}

.shot:hover:not(:disabled) {
  border-color: var(--rule-strong);
}

.shot img {
  display: block;
  width: 100%;
  height: 100%;
  /* 原图 200×100；容器 104×37 下 cover 只裁掉左右各约 7%，字符比例正常。
     fill 会把图横向拉伸四成，字符变胖更难看。 */
  object-fit: cover;
}

.fallback {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  font-size: var(--t-xs);
  color: var(--muted);
}
</style>
