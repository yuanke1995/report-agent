import { reactive, ref, onMounted } from 'vue'
import { message as antMessage } from 'ant-design-vue'
import { chat, createSession, submitFeedback } from '../api/agent'
import { inferChartSpec } from '../utils/chart'
import { toSafeHtml, escapeHtml } from '../utils/html'

/**
 * 问答会话状态机。
 *
 * 把 SSE 事件流折叠成消息列表，是整个前端唯一有状态的地方；
 * 组件只负责渲染它暴露的数据，不自己拼接接口或解析事件。
 */
export function useChat() {
  const question = ref('')
  const thinking = ref(false)
  const messages = ref([])
  const sessionId = ref(null)

  onMounted(async () => {
    sessionId.value = await createSession()
  })

  /** 新建一条助手消息占位。 */
  function newAssistantMessage() {
    // 必须 reactive：SSE 回调是逐块增量修改这个对象的字段（content/steps/data…），
    // 普通对象改字段不会触发模板重新渲染，流式效果就没了。
    return reactive({
      role: 'assistant',
      content: '',
      rendered: '',
      steps: [],
      sql: null,
      data: null,
      chartSpec: null,
      messageId: null,
      feedback: 0
    })
  }

  async function ask(text) {
    const q = (text ?? question.value).trim()
    if (!q || thinking.value) return

    question.value = ''
    thinking.value = true

    const msg = newAssistantMessage()
    messages.value.push({ role: 'user', content: q })
    messages.value.push(msg)

    try {
      await chat({ question: q, sessionId: sessionId.value }, event => applyEvent(event, msg))
    } catch (e) {
      msg.content = '请求失败：' + e.message
      msg.rendered = escapeHtml(msg.content)
    } finally {
      thinking.value = false
    }
  }

  /** 把单个 SSE 事件折叠进消息对象。 */
  function applyEvent(event, msg) {
    const { type, content } = event
    switch (type) {
      case 'step':
        applyStep(content, msg)
        break

      case 'sql':
        msg.sql = content
        break

      case 'data': {
        const data = safeParse(content)
        if (!data) break
        msg.data = data
        msg.chartSpec = inferChartSpec(data)
        break
      }

      case 'token':
        // token 是增量下发的，要累加而不是覆盖
        msg.content += content
        msg.rendered = toSafeHtml(msg.content)
        break

      case 'clarify':
        msg.content = content
        msg.rendered = toSafeHtml(msg.content)
        break

      case 'error':
        msg.content = content
        msg.rendered = escapeHtml(msg.content)
        break

      case 'warn':
        antMessage.warning(content)
        break

      case 'done': {
        const done = safeParse(content)
        if (done?.messageId) msg.messageId = done.messageId
        break
      }

      // stage 只是阶段提示，界面上由 thinking 态统一表达，无需单独渲染
      default:
        break
    }
  }

  function applyStep(content, msg) {
    const step = safeParse(content)
    if (!step) return
    // 同一动作的 running 事件可能重复下发，已有进行中的就不再追加
    const pendingSame = msg.steps.some(s => s.action === step.action && s.status === 'running')
    if (pendingSame && step.status === 'running') return
    msg.steps.push(step)
  }

  async function sendFeedback(msg, rating) {
    // 一条回答只允许评一次，避免重复落库
    if (!msg.messageId || msg.feedback !== 0) return
    try {
      const res = await submitFeedback(msg.messageId, rating)
      if (res.success) {
        msg.feedback = rating
        antMessage.success(rating === 1 ? '感谢反馈' : '已记录')
      } else {
        antMessage.error(res.msg || '提交失败')
      }
    } catch (e) {
      antMessage.error('提交失败：' + e.message)
    }
  }

  return { question, thinking, messages, ask, sendFeedback }
}

function safeParse(text) {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}
