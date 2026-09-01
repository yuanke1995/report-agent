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
 * 直接 v-html 渲染等于把注入面交给模型。所以先转义，再只把换行还成 <br/>。
 */
export function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, c => ESCAPE_MAP[c])
}

/** 转义后保留换行，用于 v-html 渲染模型回答。 */
export function toSafeHtml(text) {
  return escapeHtml(text).replace(/\n/g, '<br/>')
}
