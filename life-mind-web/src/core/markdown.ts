/**
 * Markdown 渲染：marked 解析 + DOMPurify 消毒。
 *
 * AI 输出与用户改写内容都不可信，v-html 前必须消毒，否则一段 `<img onerror=...>`
 * 就能在对话页里执行脚本。marked 默认允许原始 HTML 透传，所以 sanitize 这一步不能省。
 *
 * 链接统一新窗口打开并切断 opener/referrer（在 DOMPurify 的属性 hook 里做，
 * 比事后正则替换 <a 可靠）。代码块交给 marked 输出 <pre><code>，样式在宿主组件配。
 */
import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.setOptions({ breaks: true, gfm: true })

// 消毒阶段给所有链接补安全属性。模块只加载一次，这里加守卫防 HMR 重复注册 hook
let linkHookInstalled = false
if (!linkHookInstalled) {
  linkHookInstalled = true
  DOMPurify.addHook('afterSanitizeAttributes', (node) => {
    if (node instanceof Element && node.tagName === 'A') {
      node.setAttribute('target', '_blank')
      node.setAttribute('rel', 'noopener noreferrer')
    }
  })
}

/** 把一段 markdown 文本渲染成消毒后的安全 HTML */
export function renderMarkdown(source: string): string {
  if (!source) return ''
  const raw = marked.parse(source, { async: false }) as string
  return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } })
}
