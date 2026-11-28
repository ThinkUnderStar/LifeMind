<script setup lang="ts">
/**
 * 全站图标：统一渲染 iconfont 的线性图标（path 数据见 core/icons.ts）。
 *
 * iconfont 的图标是 1024 viewBox、fill 风格（线条本身就是闭合形状），
 * 所以这里用 fill=currentColor 而不是 stroke，颜色跟随文字色、在高亮态自然变色。
 * 只负责画图形，不设交互和间距；尺寸由 size 传入，与旁边的文字字号对齐。
 */
import { computed } from 'vue'
import { ICON_PATHS } from '../core/icons'

const props = withDefaults(
  defineProps<{
    name: keyof typeof ICON_PATHS
    size?: number
  }>(),
  { size: 16 },
)

const paths = computed(() => ICON_PATHS[props.name] ?? [])
</script>

<template>
  <svg
    class="ui-icon"
    viewBox="0 0 1024 1024"
    :style="{ width: `${size}px`, height: `${size}px` }"
    aria-hidden="true"
  >
    <path v-for="(d, i) in paths" :key="i" :d="d" />
  </svg>
</template>

<style scoped>
.ui-icon {
  display: inline-block;
  flex: 0 0 auto;
  fill: currentColor;
  vertical-align: middle;
}
</style>
