/**
 * 明暗主题。只有三档：浅色、深色、跟随系统。
 *
 * 为什么单独一个文件：主题要在 Vue 挂载之前落到 <html> 上，否则首屏会闪一下；
 * 而且「跟随系统」需要一直监听 prefers-color-scheme，和组件状态无关。
 *
 * localStorage 存的是用户选的档位（含 system），data-theme 上写的是**实际生效**的
 * light/dark——CSS 只认这两个值，不需要在样式里处理 system 的分支。
 */

export const THEMES = [
  { key: 'light', name: '浅色', color: '#fbfbfa' },
  { key: 'dark', name: '深色', color: '#17181a' },
  {
    key: 'system',
    name: '跟随系统',
    color: 'linear-gradient(90deg, #fbfbfa 50%, #17181a 50%)',
  },
] as const

export type ThemeKey = (typeof THEMES)[number]['key']

const THEME_KEY = 'lm.theme'
const darkQuery = window.matchMedia('(prefers-color-scheme: dark)')

export function currentTheme(): ThemeKey {
  const saved = localStorage.getItem(THEME_KEY)
  return THEMES.some((t) => t.key === saved) ? (saved as ThemeKey) : 'system'
}

export function applyTheme(key: ThemeKey): void {
  localStorage.setItem(THEME_KEY, key)
  const effective = key === 'system' ? (darkQuery.matches ? 'dark' : 'light') : key
  document.documentElement.dataset.theme = effective
}

export function initTheme(): void {
  applyTheme(currentTheme())
  darkQuery.addEventListener('change', () => {
    if (currentTheme() === 'system') applyTheme('system')
  })
}
