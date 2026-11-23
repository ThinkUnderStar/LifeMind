import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/** vite.config 跑在 Node 里，项目没装 @types/node，这里给用到的 process.env 一个最小声明 */
declare const process: { env: Record<string, string | undefined> }

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

/**
 * 头像等公开文件由 MinIO 直接提供，库里存的是 `/minio/{bucket}/{object}`。
 * 生产由 nginx 反代这个前缀，开发期没有 nginx，所以在 dev server 里同样转发一条，
 * 否则 `<img src="/minio/...">` 会打到前端自己身上变成 404。
 */
const MINIO = process.env.LM_MINIO || 'http://127.0.0.1:9000'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/minio': {
        target: MINIO,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/minio/, ''),
      },
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
