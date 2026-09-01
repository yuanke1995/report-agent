/**
 * 图表推断与 ECharts option 构建。
 *
 * 结果集的形状由 SQL 决定，前端只能靠列名与值类型猜图表类型，
 * 所以推断规则集中在这里，方便按业务反馈调整。
 */

/** 列名命中这些词就认为是时间轴 */
const TIME_COLUMN_PATTERN = /时间|日期|月份|年月|季度/

/**
 * 从结果集推断图表规格，不适合画图时返回 null。
 *
 * @param {{columns: string[], rows: object[]}} data
 * @returns {{type: 'line'|'bar', xField: string, yFields: string[], rows: object[]}|null}
 */
export function inferChartSpec(data) {
  if (!data?.rows?.length) return null

  const { columns, rows } = data
  // 单列结果（如一个总额）画图没有信息量
  if (!columns || columns.length < 2) return null

  const xField = columns[0]
  // 第一列是时间 → 折线看趋势；否则是类别 → 柱状做对比
  const firstIsTime = TIME_COLUMN_PATTERN.test(xField)
    || /^\d{4}-\d{2}/.test(String(rows[0][xField]))

  // 只取前两个数值列：再多图例就挤了，而且量纲通常也不一致
  const yFields = columns.filter(c => typeof rows[0][c] === 'number').slice(0, 2)
  if (!yFields.length) return null

  return {
    type: firstIsTime ? 'line' : 'bar',
    xField,
    yFields,
    rows
  }
}

/**
 * 把图表规格转成 ECharts option。
 *
 * @param {{type: string, xField: string, yFields: string[], rows: object[]}} spec
 */
export function buildChartOption(spec) {
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: spec.yFields },
    grid: { left: 60, right: 20, top: 40, bottom: 40 },
    xAxis: {
      type: 'category',
      data: spec.rows.map(r => String(r[spec.xField])),
      axisLabel: { rotate: 30 }
    },
    yAxis: { type: 'value' },
    series: spec.yFields.map(field => ({
      name: field,
      type: spec.type,
      smooth: spec.type === 'line',
      data: spec.rows.map(r => r[field])
    }))
  }
}
