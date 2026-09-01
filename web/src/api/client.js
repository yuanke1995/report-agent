import { API_BASE, TRUSTED_TOKEN, USER_ID } from '../config'

/**
 * HTTP 客户端。
 *
 * 存在的意义是鉴权头只在这一处拼装：之前三个调用点各自手写
 * X-Trusted-Token / X-User-Id，改 token 来源时容易漏掉其中一处。
 */

function authHeaders(extra = {}) {
  return {
    'X-Trusted-Token': TRUSTED_TOKEN,
    'X-User-Id': USER_ID,
    ...extra
  }
}

/** 普通 JSON 请求，返回后端 ResultJson 的解析结果。 */
export async function postJson(path, body) {
  const resp = await fetch(API_BASE + path, {
    method: 'POST',
    headers: authHeaders(body ? { 'Content-Type': 'application/json' } : {}),
    body: body ? JSON.stringify(body) : undefined
  })
  return resp.json()
}

/**
 * 流式请求，返回原始 Response 交给调用方逐块读取。
 *
 * 这里不能用 EventSource：SSE 标准只支持 GET，而问题文本要放在
 * 请求体里（问题可能很长，且不该出现在 URL 与访问日志中）。
 */
export async function postStream(path, body) {
  const resp = await fetch(API_BASE + path, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body)
  })
  if (!resp.ok || !resp.body) {
    throw new Error('HTTP ' + resp.status)
  }
  return resp
}
