<template>
  <div class="app">
    <header class="header">
      <h1>企业报表智能体</h1>
      <span class="sub">受控式 Text-to-SQL · 模板优先 + NL2SQL 兜底</span>
    </header>

    <main class="main">
      <!-- 会话区 -->
      <div class="chat" ref="chatRef">
        <div v-if="!messages.length" class="welcome">
          <p>用自然语言查数据，例如：</p>
          <div class="suggestions">
            <button v-for="q in suggestions" :key="q" @click="ask(q)">{{ q }}</button>
          </div>
        </div>

        <div v-for="(m, i) in messages" :key="i" class="message" :class="m.role">
          <div class="bubble">
            <div v-if="m.role === 'user'" class="question">{{ m.content }}</div>
            <template v-else>
              <!-- 执行轨迹 -->
              <div v-if="m.steps && m.steps.length" class="steps">
                <div v-for="(s, j) in m.steps" :key="j" class="step" :class="s.status">
                  <span class="step-dot" />
                  <span class="step-action">{{ actionLabel(s.action) }}</span>
                  <span class="step-status">{{ statusLabel(s.status) }}</span>
                  <span v-if="s.error" class="step-error" :title="s.error">{{ s.error }}</span>
                </div>
              </div>

              <!-- SQL 折叠面板 -->
              <a-collapse v-if="m.sql" size="small" class="sql-panel">
                <a-collapse-panel key="sql" header="查看 SQL">
                  <pre class="sql">{{ m.sql }}</pre>
                </a-collapse-panel>
              </a-collapse>

              <!-- 结果表格 -->
              <div v-if="m.data && m.data.rows" class="result">
                <a-table
                  :data-source="m.data.rows"
                  :columns="m.data.columns.map(c => ({ title: c, dataIndex: c, key: c, ellipsis: true }))"
                  :pagination="false"
                  size="small"
                  :scroll="{ x: true }"
                />
                <a-alert v-if="m.data.truncated" type="warning" message="结果已达行数上限被截断" show-icon class="truncate-warn" />
              </div>

              <!-- 图表 -->
              <div v-if="m.chartOption" class="chart" />

              <!-- 回答文本 -->
              <div class="answer markdown" v-html="m.rendered" />

              <!-- 反馈 -->
              <div v-if="m.messageId" class="feedback">
                <a-button size="small" @click="sendFeedback(m, 1)" :type="m.feedback === 1 ? 'primary' : 'default'">
                  👍 有用
                </a-button>
                <a-button size="small" danger @click="sendFeedback(m, -1)" :type="m.feedback === -1 ? 'danger' : 'default'">
                  👎 没用
                </a-button>
              </div>
            </template>
          </div>
        </div>
        <div v-if="thinking" class="message assistant">
          <div class="bubble thinking">
            <a-spin size="small" />
            <span>正在分析…</span>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="input-bar">
        <a-input
          v-model:value="question"
          placeholder="问点什么，比如：最近半年每个月的销售额"
          size="large"
          @press-enter="ask"
          :disabled="thinking"
        />
        <a-button type="primary" size="large" :loading="thinking" @click="ask" :disabled="!question.trim()">
          提问
        </a-button>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, onMounted } from 'vue'
import { message as antMessage } from 'ant-design-vue'
import * as echarts from 'echarts'

// ---------- 状态 ----------
const chatRef = ref(null)
const question = ref('')
const thinking = ref(false)
const messages = ref([])
let sessionId = null

const suggestions = [
  '最近半年每个月的销售额',
  '各区域销售对比',
  '8 月销售额最高的商品',
  '金卡客户的消费总额'
]

const TOKEN = localStorage.getItem('agentToken') || 'local-dev-token'
const USER = localStorage.getItem('agentUser') || 'demo'

const actionLabels = {
  list_metrics: '查询指标口径',
  get_table_schema: '读取表结构',
  run_report_template: '执行报表模板',
  execute_sql: '执行查询',
  ask_clarification: '请用户澄清'
}
const statusLabels = { running: '进行中', success: '完成', failed: '失败' }

function actionLabel(a) { return actionLabels[a] || a }
function statusLabel(s) { return statusLabels[s] || s }

// ---------- SSE 消费 ----------
async function ask(text) {
  const q = (text || question.value).trim()
  if (!q || thinking.value) return
  question.value = ''
  thinking.value = true

  // 必须用 reactive 包装：SSE 回调里对 msg 的增量修改（content/steps/data...）
  // 要通过响应式代理触发 v-if/v-for 的即时渲染，普通对象不触发
  const msg = reactive({
    role: 'assistant',
    content: '',
    rendered: '',
    steps: [],
    sql: null,
    data: null,
    chartOption: null,
    messageId: null,
    feedback: 0
  })
  messages.value.push({ role: 'user', content: q })
  messages.value.push(msg)
  scrollToBottom()

  try {
    const resp = await fetch('/report-agent/api/agent/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Trusted-Token': TOKEN,
        'X-User-Id': USER
      },
      body: JSON.stringify({ question: q, sessionId })
    })
    if (!resp.ok || !resp.body) throw new Error('HTTP ' + resp.status)

    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE 事件以空行分隔
      const events = buffer.split('\n\n')
      buffer = events.pop()
      for (const evt of events) {
        handleEvent(evt, msg)
      }
    }
    if (buffer.trim()) handleEvent(buffer, msg)
  } catch (e) {
    msg.content = '请求失败：' + e.message
    msg.rendered = escapeHtml(msg.content)
  } finally {
    thinking.value = false
    scrollToBottom()
  }
}

function handleEvent(raw, msg) {
  const dataLine = raw.split('\n').find(l => l.startsWith('data:'))
  if (!dataLine) return
  let payload
  try {
    payload = JSON.parse(dataLine.slice(5).trim())
  } catch { return }
  const content = payload.content
  switch (payload.type) {
    case 'step': {
      const step = JSON.parse(content)
      const existing = msg.steps.find(s => s.action === step.action && s.status === 'running')
      if (existing && step.status === 'running') return
      msg.steps.push(step)
      break
    }
    case 'sql':
      msg.sql = content
      break
    case 'data': {
      const d = JSON.parse(content)
      msg.data = d
      buildChart(msg)
      break
    }
    case 'token':
      msg.content += content
      msg.rendered = escapeHtml(msg.content).replace(/\n/g, '<br/>')
      break
    case 'clarify':
      msg.content = content
      msg.rendered = escapeHtml(msg.content).replace(/\n/g, '<br/>')
      break
    case 'error':
      msg.content = content
      msg.rendered = escapeHtml(msg.content)
      break
    case 'warn':
      antMessage.warning(content)
      break
    case 'done':
      if (content) {
        try { msg.messageId = JSON.parse(content).messageId } catch { /* ignore */ }
      }
      break
  }
  scrollToBottom()
}

// ---------- 图表 ----------
function buildChart(msg) {
  const d = msg.data
  if (!d || !d.rows || !d.rows.length) return
  const cols = d.columns
  if (cols.length < 2) return // 单列结果不值得画图

  // 推断图表类型：第一列是时间 → 折线；类别列（字符串）→ 柱状
  const firstIsTime = /时间|日期|月份|年月|季度/.test(cols[0]) || /^\d{4}-\d{2}/.test(String(d.rows[0][cols[0]]))
  const type = firstIsTime ? 'line' : 'bar'

  // 数值列（画图用前两列数值）
  const numericCols = cols.filter(c => typeof d.rows[0][c] === 'number').slice(0, 2)
  if (!numericCols.length) return

  msg.chartOption = {
    type,
    xField: cols[0],
    yFields: numericCols,
    rows: d.rows
  }
  nextTick(() => renderChart(msg))
}

function renderChart(msg) {
  // 直接用 DOM 查询取图表容器：v-for 内的动态 v-if 元素的 ref 绑定
  // 在 Vue 3 中行为不可靠，DOM 查询最直接
  const els = document.querySelectorAll('.chart')
  if (!els.length) return
  const el = els[els.length - 1]
  if (!el || el.__chartInited) return
  el.__chartInited = true
  const chart = echarts.init(el)
  const opt = msg.chartOption
  const series = opt.yFields.map(y => ({
    name: y,
    type: opt.type,
    smooth: opt.type === 'line',
    data: opt.rows.map(r => r[y])
  }))
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: opt.yFields },
    grid: { left: 60, right: 20, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: opt.rows.map(r => String(r[opt.xField])), axisLabel: { rotate: 30 } },
    yAxis: { type: 'value' },
    series
  })
}

// ---------- 反馈 ----------
async function sendFeedback(m, rating) {
  if (!m.messageId || m.feedback !== 0) return
  const body = { messageId: m.messageId, rating }
  if (rating === -1) {
    body.reason = '数字不对'
  }
  try {
    const resp = await fetch('/report-agent/api/agent/feedback', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Trusted-Token': TOKEN,
        'X-User-Id': USER
      },
      body: JSON.stringify(body)
    })
    const d = await resp.json()
    if (d.success) {
      m.feedback = rating
      antMessage.success(rating === 1 ? '感谢反馈' : '已记录')
    } else {
      antMessage.error(d.msg || '提交失败')
    }
  } catch (e) {
    antMessage.error('提交失败：' + e.message)
  }
}

// ---------- 工具 ----------
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[c])
}

function scrollToBottom() {
  nextTick(() => {
    if (chatRef.value) chatRef.value.scrollTop = chatRef.value.scrollHeight
  })
}

onMounted(() => {
  // 新会话
  fetch('/report-agent/api/agent/session/new', {
    method: 'POST',
    headers: { 'X-Trusted-Token': TOKEN, 'X-User-Id': USER }
  }).then(r => r.json()).then(d => {
    if (d.data) sessionId = d.data.sessionId
  }).catch(() => { /* 会话创建失败也不阻塞提问 */ })
})
</script>

<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif; background: #f5f6fa; }
.app { display: flex; flex-direction: column; height: 100vh; }
.header { padding: 16px 24px; background: #fff; border-bottom: 1px solid #e8e8e8; display: flex; align-items: baseline; gap: 12px; }
.header h1 { font-size: 18px; color: #1f2d3d; }
.header .sub { color: #909399; font-size: 13px; }
.main { flex: 1; display: flex; flex-direction: column; max-width: 1000px; width: 100%; margin: 0 auto; }
.chat { flex: 1; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 16px; }
.welcome { text-align: center; color: #909399; margin-top: 80px; }
.suggestions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; margin-top: 16px; }
.suggestions button { padding: 8px 14px; border: 1px solid #d9d9d9; border-radius: 16px; background: #fff; cursor: pointer; color: #409eff; }
.suggestions button:hover { border-color: #409eff; }
.message { display: flex; }
.message.user { justify-content: flex-end; }
.bubble { max-width: 85%; padding: 12px 16px; border-radius: 10px; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.message.user .bubble { background: #409eff; color: #fff; }
.thinking { display: flex; align-items: center; gap: 8px; color: #909399; }
.steps { display: flex; flex-direction: column; gap: 4px; margin-bottom: 10px; }
.step { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #606266; }
.step-dot { width: 8px; height: 8px; border-radius: 50%; background: #c0c4cc; }
.step.running .step-dot { background: #409eff; animation: pulse 1s infinite; }
.step.success .step-dot { background: #67c23a; }
.step.failed .step-dot { background: #f56c6c; }
.step-error { color: #f56c6c; font-size: 12px; max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@keyframes pulse { 50% { opacity: .3; } }
.sql-panel { margin: 8px 0; }
.sql { font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px; background: #f8f8f8; padding: 10px; border-radius: 6px; overflow-x: auto; white-space: pre-wrap; }
.result { margin: 8px 0; }
.truncate-warn { margin-top: 8px; }
.chart { width: 100%; height: 300px; margin: 8px 0; }
.answer { line-height: 1.7; font-size: 14px; color: #303133; white-space: pre-wrap; }
.feedback { margin-top: 10px; display: flex; gap: 8px; }
.input-bar { display: flex; gap: 8px; padding: 16px 24px; background: #fff; border-top: 1px solid #e8e8e8; }
</style>
