# life-mind-web

LifeMind 的网页端。中间对话，右侧是插件面板——agent 调用工具时会推一条 `ui` 事件，右侧自动切到对应插件的对应分段，用户不用自己去翻。

## 跑起来

```bash
npm install
npm run dev
```

`npm run dev` 做三件事：起 Vite（5173）→ 起 nginx（8088）→ 把两者绑在一条命令的生命周期里。浏览器只访问 **http://localhost:8088**。

跨域在 nginx 里消掉：页面和 `/api` 同源，后端不需要加 CORS 头。`/api/` 会被转给网关 8001 并去掉前缀，所以 `/api/life-mind/schedule/item/page` → `/life-mind/schedule/item/page`。

**必须走网关，不能直连微服务。** JWT 校验和 `LifeMind-User-Id` / `-Roles` / `-Scopes` / `-Plugins` 四个头只在网关注入。直接转 8005 会让下游拿不到身份直接 401；而前端绝不能自己塞 userId 头，那等于把身份判定权交给浏览器。

没装 nginx 也能跑，脚本会自动降级到 Vite 代理并打印一行说明。装好后不用改代码，放进 PATH、`C:\nginx`，或项目内 `tools/nginx/`，就会自动切回来；也可以用 `NGINX_BIN=/path/to/nginx npm run dev` 显式指定。

### nginx 与中文路径（Windows 必读）

仓库里 `tools/nginx/` 放了 nginx 1.26.2 的 Windows 版（被 `.gitignore` 排除，二进制不入库）。下载地址 `https://nginx.org/download/nginx-1.26.2.zip`，解压后把 `nginx-1.26.2/` 整个改成 `tools/nginx/` 即可。

nginx for Windows 内部走 ANSI 版 Win32 API（`CreateFileA`），**路径里只要有非 ASCII 字符就会失败**：

```
CreateFile() "...\.nginx\logs\error.log" failed
(1113: No mapping for the Unicode character exists in the target multi-byte code page)
```

这跟权限无关，改目录 ACL 或 `chcp 65001` 都没用。实测三种组合：

| exe 路径 | 运行时 prefix | 结果 |
|---|---|---|
| 中文 | 中文 | 起不来，连 error.log 都写不出 |
| 中文 | ASCII | 起不来，进程创建不出来 |
| ASCII | ASCII | 正常 |

本项目路径是 `桌面\勿动\LifeMind`，正好命中。所以脚本会检测 `ROOT` 是否纯 ASCII：不是的话，运行时目录改放 `%LOCALAPPDATA%\life-mind-web\nginx`，并把 exe 从 `tools/nginx` 复制一份过去再执行。Vite 不受影响，Node 走 Unicode API，跑在中文路径下正常。

另一个 Windows 坑：**nginx.exe 本身就是 master 进程，不会 fork 后退出**，所以启动不能等它返回（`spawnSync` 会永久阻塞——端口已经监听了，但脚本再也不往下走）。脚本用非阻塞 `spawn` + 端口探测判成败。

依赖：node ≥ 18（用到 `crypto.randomUUID`、`Response`）。

请求前缀默认就是 `/api`，所以不开 `.env` 也能跑。要改的话 `cp .env.example .env.development` 再改——环境文件按仓库约定不入库（根 `.gitignore` 排除了 `.env.*`）。

## 目录

```
src/
├── api/            后端接口，一个服务一个文件，只做「拼路径 + 解 Result」
│   ├── http.ts     fetch 基座：BASE、令牌、错误解包、401 广播
│   ├── chat.ts     SSE 三个流式入口 + 历史/任务/挂起确认
│   └── schedule.ts 事项 / 系列 / 提醒 / 统计 / 备忘
├── core/
│   ├── types.ts    后端契约，逐字段对照 DTO 抄的
│   ├── store.ts    全局状态与动作（会话/消息/流/确认卡/面板路由）
│   ├── sse.ts      SSE 字节流 → 帧，纯函数
│   ├── date.ts     两种时间格式归一（Java 空格 / Python ISO）
│   └── plugin-registry.ts  插件注册表
├── views/          外壳：登录、三栏骨架、会话栏、对话栏、面板宿主
├── components/     确认卡、提示条、到点提醒
├── plugins/
│   └── schedule/   日程插件：面板根 + 今天/全部/重复/备忘
└── styles/         token 与基座样式
```

## 三条数据流

| 方向 | 机制 | 用在哪 |
|---|---|---|
| 请求-响应 | `api/*.ts` → `Result<T>` | 会话、事项、插件开关 |
| 流式 | `POST /chat/send` + `fetch` 读流 | 对话正文、工具进度、面板跳转、确认卡 |
| 轮询 | 30 秒一次 `?isRead=0&needPopup=1` | 网页内到点提醒 |

SSE 帧统一是 `data: {"type":...,"data":...}`：

- `text` 回复片段，直接追加
- `tool` 插件工具开始/结束，`start` 与 `end` 带同一个 `id`，据此配对显示进度
- `ui` 该打开哪个插件、哪个分段、定位到哪一天或哪一条
- `interrupt` 需要用户确认，弹卡后调 `/chat/resume`
- `done` 本轮结束
- `error` 执行失败

没用 `EventSource`：它只支持 GET 且无法设 `Authorization` 头，而 `/chat/send` 是 POST。

## 加一个插件要动四处

后端三处已经定死：`plugin` 表的 `plugin_key`、agent 的 `SERVICE_TOOLS`、SSE `ui` 帧的 `plugin` 字段。前端是第四处，**键必须逐字相同**——写错一个字母不会报错，只会表现为「agent 推了面板事件，界面没反应」。

1. `src/api/<key>.ts`：该服务的接口封装
2. `src/plugins/<key>/`：面板组件（根组件接收 `epoch` prop，变了就重新取数）
3. `src/core/plugin-registry.ts`：加一条 manifest，声明 `views`（ui 帧的 view 值 → 面板分段）
4. 在 `SERVICE` 里加该服务的路径前缀（若与 `/life-mind/<服务名>` 不同）

插件**列表**仍然来自 `account` 的 `/plugin/list`，因为「能不能用」是用户的事实；注册表声明的是「界面做没做出来」。两者取交集才是可点开的。账单、健康在服务端还没实现，所以显示为「即将打开」而不是给一个点了会 404 的入口。

## 几个必须知道的契约细节

- **时间有两种格式**：Java 侧 `"2026-11-25 19:00:00"`（空格），Python 侧 ISO。`new Date("2026-11-25 19:00:00")` 在 Safari 上返回 Invalid Date，一律走 `core/date.ts` 的 `parseDateTime`。
- **周完成率是 0~100**，不是 0~1，别再乘 100。
- **分页字段两边一致**：`records / total / size / current`（Java 是 MyBatis-Plus 的 IPage，Python 是 PageData，恰好同名）。
- **密码登录强制图形验证码**：`tempKey` 由前端生成且必须是 UUID，验证码图片接口返回的是字节流不是 JSON。
- **`scope=future` 必须带 `anchorItemId`**：服务端靠它判断「从哪一次起」，只给日期无法定位。
- **重复计划必须有终点**：`countLimit` 与 `untilDate` 至少一个，否则 400。

## 已知缺口

- **到点提醒会重复弹**：通知表没有 `notified_time`，同一条未读通知在用户点掉前会被反复取到。现在靠 `state.popped` 在本地记已弹集合，刷新页面后失效。根治要等后端加字段。
- **不能改事项的重复规则所基于的时刻**：`baseTime` 不可改（后端限制），要换时间只能删系列重建。
- **回溯（改写历史消息）还没接到界面**：`api/chat.ts` 里已有 `backtrackChat`，但没有入口。
- **没有注册页**：`AuthController` 有 `/auth/register`，这一版界面只做登录。
