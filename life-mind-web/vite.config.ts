import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发期的代理有两个来源，按优先级：
 *
 * 1. nginx（默认，`npm run dev` 会优先起它）——浏览器访问 8088，nginx 把
 *    `/api/` 转给网关 8001，`/` 转给本 dev server。同源，浏览器侧没有跨域问题。
 * 2. 下面的 server.proxy——本机没装 nginx 时的兜底，脚本检测不到 nginx 才走这条，
 *    能力等价（Vite 的 http-proxy 同样转发），只是不满足「跨域统一由 nginx 处理」
 *    的部署约定，所以仅作降级。
 *
 * 两条都指向网关而不是各微服务端口，这不是图省事：JWT 校验、`LifeMind-User-Id`
 * /`-Roles`/`-Scopes`/`-Plugins` 的注入全部只发生在网关。直接转发到 8005 或 8004
 * 会绕开注入，下游拿不到身份直接 401，而且前端绝不能自己塞 userId 头。
 */
const GATEWAY = process.env.LM_GATEWAY || 'http://127.0.0.1:8001'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: GATEWAY,
        changeOrigin: true,
        // SSE 必须不被缓冲，否则要等整段生成完才一次性到
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache'
            }
          })
        },
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})
