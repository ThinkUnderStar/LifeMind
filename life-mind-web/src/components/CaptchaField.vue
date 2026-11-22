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
/*
 * 这里的 .field 必须自己写一遍。
 * 父组件 AuthView 的 .field 是 scoped 的，加的是父组件的 data-v 属性，
 * 而 scoped 只会把属性打在子组件的根节点上，碰不到这里面的 input——
 * 之前这个输入框一直是吃 base.css 的默认样式（6px 圆角、9px 内边距），
 * 放在登录卡片里跟另外三个字段对不齐，这就是「验证码那一行看着歪」的原因。
 */
.captcha {
  display: flex;
  align-items: center;
  gap: var(--s2);
}

.field {
  width: 100%;
  height: 46px;
  padding: 0 14px;
  border: 1px solid rgba(23, 24, 26, 0.11);
  border-radius: 3px;
  background: #fff;
  font-size: 14px;
  transition: border-color 160ms var(--ease), box-shadow 160ms var(--ease);
}

.field::placeholder {
  color: rgba(23, 24, 26, 0.28);
}

.field:hover:not(:disabled):not(:focus) {
  border-color: rgba(23, 24, 26, 0.22);
}

.field:focus {
  outline: none;
  border-color: var(--ink);
  box-shadow: inset 0 0 0 1px var(--ink);
}

.shot {
  flex: 0 0 auto;
  /* 必须与原图 200×100 保持 2:1。验证码字符几乎顶满上下（约占 80% 高），
     容器一扁就会切掉字符的顶和底——104×37 时裁掉的是上下各 29%，
     只剩中间一截，人眼认不出来。92×46 正好 2:1，也和左边 46px 的输入框等高，
     两个框的上下边才能落在同一条线上。 */
  width: 92px;
  height: 46px;
  border: 1px solid rgba(23, 24, 26, 0.11);
  border-radius: 3px;
  overflow: hidden;
  background: #fff;
  /* 表单区不做位移和缩放：花样全在背景，这里只提示「可点」 */
  transition: border-color 160ms var(--ease);
}

.shot:hover:not(:disabled) {
  border-color: rgba(23, 24, 26, 0.3);
}

.shot img {
  display: block;
  width: 100%;
  height: 100%;
  /* 容器已按 2:1 对齐原图，contain 与 cover 等价；用 contain 是为了将来
     后端换图尺寸时宁可留白，也不要裁掉字符。 */
  object-fit: contain;
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
