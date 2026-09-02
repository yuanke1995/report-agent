import { marked } from 'marked'

const ESCAPE_MAP = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
}

/**
 * HTML 转义。
 *
 * 回答文本来自模型，而模型的输出里可能夹带 SQL 片段、尖括号或用户原话，
 * 直接 v-html 渲染等于把注入面交给模型。
 */
export function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, c => ESCAPE_MAP[c])
}

marked.use({
  gfm: true,
  breaks: true,
  // 模型输出属于不可信内容：链接只放行常规协议，挡掉 javascript:/data: 这类伪协议
  walkTokens(token) {
    if (token.type === 'link' || token.type === 'image') {
      if (!/^(https?:|mailto:|#|\/)/i.test(String(token.href || '').trim())) {
        token.href = '#'
      }
    }
  }
})

/**
 * 把模型回答按 Markdown 渲染成安全 HTML。
 *
 * 模型的回答里带表格、加粗和列表，只做转义 + <br/> 的话 GFM 表格会塌成一行
 * 管道符、`**结论**` 也原样显示。做法是先转义尖括号断掉内联 HTML，再交给
 * marked —— Markdown 语法照常生效，但标签不会被执行。
 */
export function toSafeHtml(text) {
  if (!text) return ''
  return marked.parse(escapeHtml(text))
}
