import { postJson, postStream } from './client'
import { readSseStream } from '../utils/sse'

/**
 * 智能体业务接口。组件只调这里的方法，不碰 fetch 与路径字符串。
 */

/** 新建会话，失败返回 null（会话创建失败不该阻塞提问）。 */
export async function createSession() {
  try {
    const res = await postJson('/agent/session/new')
    return res?.data?.sessionId ?? null
  } catch {
    return null
  }
}

/**
 * 发起问答并消费 SSE 流。
 *
 * @param {{question: string, sessionId: string|null}} payload
 * @param {(event: {type: string, content: string}) => void} onEvent 每个事件回调一次
 */
export async function chat(payload, onEvent) {
  const resp = await postStream('/agent/chat', payload)
  await readSseStream(resp.body, onEvent)
}

/**
 * 提交回答反馈。
 *
 * @param {string} messageId
 * @param {1|-1} rating 1=有用 -1=没用
 * @param {string} [reason] 差评原因，后端要求差评必填
 */
export async function submitFeedback(messageId, rating, reason) {
  const body = { messageId, rating }
  if (rating === -1) {
    body.reason = reason || '数字不对'
  }
  return postJson('/agent/feedback', body)
}
