/**
 * SSE 流解析。
 *
 * 独立成纯函数是为了可测：跨 chunk 拆分的事件边界是这类代码最容易出错的地方，
 * 而它和 Vue、DOM 都没有关系。
 */

/**
 * 逐块读取 SSE 流并回调每个事件的 data 载荷。
 *
 * @param {ReadableStream} stream Response.body
 * @param {(payload: object) => void} onEvent
 */
export async function readSseStream(stream, onEvent) {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    // SSE 事件以空行分隔。最后一段可能是半个事件，留在 buffer 里等下一个 chunk。
    const chunks = buffer.split('\n\n')
    buffer = chunks.pop()
    for (const chunk of chunks) {
      const payload = parseSseChunk(chunk)
      if (payload) onEvent(payload)
    }
  }
  // 流结束时 buffer 里可能还剩最后一个未以空行结尾的事件
  if (buffer.trim()) {
    const payload = parseSseChunk(buffer)
    if (payload) onEvent(payload)
  }
}

/**
 * 解析单个 SSE 事件块，取出 data: 行的 JSON。
 * 解析不了就返回 null 而不是抛错——一个坏事件不该中断整条流。
 *
 * @returns {object|null}
 */
export function parseSseChunk(chunk) {
  const dataLine = chunk.split('\n').find(line => line.startsWith('data:'))
  if (!dataLine) return null
  try {
    return JSON.parse(dataLine.slice(5).trim())
  } catch {
    return null
  }
}
