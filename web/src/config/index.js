/**
 * 前端运行期配置。
 *
 * 所有环境相关的值集中在这里，组件与接口层不直接读 import.meta.env，
 * 换部署环境时只改这一个文件。
 */

/** 后端接口前缀。与 vite 代理规则、后端 context-path 三者必须一致。 */
export const API_BASE = '/report-agent/api'

/**
 * 内部鉴权 token，必须与后端 report-agent.trusted-token 完全一致，
 * 否则 /api/** 一律返回 401。
 *
 * 优先 localStorage（便于在浏览器里临时切换而不重启 vite），
 * 其次 web/.env.local 的 VITE_AGENT_TOKEN。
 */
export const TRUSTED_TOKEN = localStorage.getItem('agentToken')
  || import.meta.env.VITE_AGENT_TOKEN
  || 'local-dev-token-change-me'

/** 模拟登录用户标识，后端按此维度做会话归属与限流。 */
export const USER_ID = localStorage.getItem('agentUser')
  || import.meta.env.VITE_AGENT_USER
  || 'demo'
