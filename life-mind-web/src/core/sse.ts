/**
 * SSE 解码。纯函数，不碰网络也不碰 UI，只把字节流切成帧。
 *
 * 为什么不用 EventSource：它只支持 GET，而 `/chat/send` 是 POST，
 * 且需要 `Authorization` 头——EventSource 无法设置请求头。
 * 所以只能用 fetch + ReadableStream 自己解。
 *
 * 帧格式按 SSE 规范：以空行分隔，每行 `field: value`。
 * 我们要的只有 `data:`，一个帧可能有多行 data，按规范用换行拼接。
 */

export type FrameHandler = (payload: unknown) => void

/** 单个帧的文本 → payload。解析失败不抛，避免一帧坏数据打断整轮对话 */
function decodeFrame(raw: string): unknown | undefined {
  const dataLines: string[] = []

  for (const line of raw.split('\n')) {
    if (!line || line.startsWith(':')) continue // 注释行与心跳
    if (!line.startsWith('data:')) continue
    // 规范允许 `data:xxx` 与 `data: xxx` 两种写法
    const value = line.slice(5)
    dataLines.push(value.startsWith(' ') ? value.slice(1) : value)
  }

  if (!dataLines.length) return undefined

  const text = dataLines.join('\n')
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

/**
 * 消费一个 text/event-stream 响应，逐帧回调。
 *
 * 用 `\n\n` 切帧而不是逐行处理，是因为一个 JSON 里可能带换行（比如回复正文），
 * 逐行拼会把它切成半个 JSON。
 */
export async function readSseStream(res: Response, onFrame: FrameHandler): Promise<void> {
  if (!res.body) throw new Error('响应没有可读流，可能是浏览器不支持或连接已被关闭')

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break

      // stream: true 保证多字节字符（中文）被切断时不会解出乱码
      buffer += decoder.decode(value, { stream: true })

      let boundary = buffer.indexOf('\n\n')
      while (boundary !== -1) {
        const raw = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        const payload = decodeFrame(raw)
        if (payload !== undefined) onFrame(payload)
        boundary = buffer.indexOf('\n\n')
      }
    }

    // 流结束时可能还剩一帧没被空行收尾
    if (buffer.trim()) {
      const payload = decodeFrame(buffer)
      if (payload !== undefined) onFrame(payload)
    }
  } finally {
    reader.releaseLock()
  }
}
