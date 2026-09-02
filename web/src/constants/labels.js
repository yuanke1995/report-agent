/**
 * 界面文案与常量。
 *
 * 工具名、状态名的中文映射集中在这里：后端新增工具时只改这一处，
 * 不必到组件模板里找 switch。
 */

/** Agent 工具名 → 执行轨迹上展示的中文标签 */
export const ACTION_LABELS = {
  list_metrics: '查询指标口径',
  get_table_schema: '读取表结构',
  run_report_template: '执行报表模板',
  execute_sql: '执行查询',
  ask_clarification: '请用户澄清'
}

/** 步骤状态 → 中文 */
export const STATUS_LABELS = {
  running: '进行中',
  success: '完成',
  failed: '失败'
}

/** 首屏推荐问题：前两条走模板路径，后两条走 NL2SQL 兜底 */
export const SUGGESTIONS = [
  '最近半年每个月的销售额',
  '各区域销售对比',
  '8 月销售额最高的商品',
  '金卡客户的消费总额'
]

/** 未登记的工具名直接原样显示，便于发现遗漏 */
export function actionLabel(action) {
  return ACTION_LABELS[action] || action
}

export function statusLabel(status) {
  return STATUS_LABELS[status] || status
}

/** 输入框最大长度，与后端 ChatRequest @Size(max=500) 对齐，超长提前截断而非提交后报错 */
export const QUESTION_MAX = 500

/** 结果表每页行数：结果可能上千行，全量渲染会卡死页面 */
export const TABLE_PAGE_SIZE = 20

/** 差评原因白名单，与后端 FeedbackController.REASONS 保持一致 */
export const FEEDBACK_REASONS = ['数字不对', '口径不对', '答非所问', '查询失败']
