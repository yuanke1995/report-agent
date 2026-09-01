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
