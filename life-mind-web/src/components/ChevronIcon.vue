<script setup lang="ts">
/**
 * 细线尖角箭头。全站统一用它替掉文字箭头（→ / ← / ‹ / ›）：
 * 文字箭头在不同字体、字号下粗细不一，和组件库里的 1.5px 描边图标不是一套语言。
 * 只画尖角、不带杆，比实心或带杆箭头更轻，放在文字旁边不抢主内容。
 */
withDefaults(
  defineProps<{
    dir?: 'left' | 'right'
    size?: number
  }>(),
  { dir: 'right', size: 14 },
)
</script>

<template>
  <svg
    class="chev"
    viewBox="0 0 20 20"
    :style="{ width: `${size}px`, height: `${size}px` }"
    aria-hidden="true"
  >
    <path :d="dir === 'right' ? 'M7 4.5l5.5 5.5L7 15.5' : 'M13 4.5L7.5 10 13 15.5'" />
  </svg>
</template>

<style scoped>
.chev {
  display: inline-block;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
