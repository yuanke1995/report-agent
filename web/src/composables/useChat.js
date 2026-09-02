import { reactive, ref, onMounted } from 'vue'
import { message as antMessage } from 'ant-design-vue'
import { chat, createSession, submitFeedback } from '../api/agent'
import { inferChartSpec } from '../utils/chart'
import { toSafeHtml } from '../utils/html'

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
      pending: true,
      stage: '',
      content: '',
      rendered: '',
      error: '',
      steps: [],
      sql: null,
      data: null,
      chartSpec: null,
      messageId: null,
      feedback: 0,
      feedbackReason: ''
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
      msg.error = '请求失败：' + e.message
    } finally {
      msg.pending = false
      thinking.value = false
    }
  }

  /** 把单个 SSE 事件折叠进消息对象。 */
  function applyEvent(event, msg) {
    const { type, content } = event

    // 后端每个事件都带 sessionId。首轮若 session/new 失败，会话是服务端补建的，
    // 这里必须接住，否则每次提问都 sessionId=null，多轮上下文全丢。
    if (event.sessionId && !sessionId.value) sessionId.value = event.sessionId

    switch (type) {
      case 'stage':
        msg.stage = content
        break

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
        // 出错时后端会先发 error、再把同一句话作为 token 发一遍，
        // 不去重就会在错误提示下面又原样显示一次
        if (msg.error && content && msg.error.includes(content.trim())) break
        // token 是增量下发的，要累加而不是覆盖
        msg.content += content
        msg.rendered = toSafeHtml(msg.content)
        break

      case 'clarify':
        msg.content = content
        msg.rendered = toSafeHtml(msg.content)
        break

      case 'error':
        // 错误单独存字段、由界面渲染成 alert，不和正常回答混在一起
        msg.error = content
        break

      case 'warn':
        antMessage.warning(content)
        break

      case 'done': {
        const done = safeParse(content)
        if (done?.messageId) msg.messageId = done.messageId
        break
      }

      default:
        break
    }
  }

  /**
   * 同一个步骤后端会推两次（beginStep 进行中 → endStep 完成/失败），
   * 直接 push 会让轨迹里每步都出现两行，所以按 round + action 原地更新。
   *
   * 同一轮里同一个工具被调两次时，前一对已经收尾、没有 running 行可匹配，
   * 会正常追加成新行。
   */
  function applyStep(content, msg) {
    const step = safeParse(content)
    if (!step) return
    const i = msg.steps.findIndex(
      s => s.round === step.round && s.action === step.action && s.status === 'running'
    )
    if (i >= 0) {
      msg.steps[i] = step
    } else {
      msg.steps.push(step)
    }
  }

  /**
   * @param {object} msg 被评价的消息
   * @param {1|-1} rating
   * @param {string} [reason] 差评原因，必须是后端白名单里的值
   */
  async function sendFeedback(msg, rating, reason) {
    // 一条回答只允许评一次，避免重复落库
    if (!msg.messageId || msg.feedback !== 0) return
    try {
      const res = await submitFeedback(msg.messageId, rating, reason)
      if (res.success) {
        msg.feedback = rating
        msg.feedbackReason = reason || ''
        antMessage.success(res.msg || (rating === 1 ? '感谢反馈' : '已记录'))
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
