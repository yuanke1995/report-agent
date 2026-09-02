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

  // 数值列要跳过 X 轴那一列——"年份""月份"这类数字维度被画成一条曲线没有任何意义
  const yFields = columns
    .slice(1)
    .filter(c => isNumericColumn(rows, c))
    .slice(0, 2)
  if (!yFields.length) return null

  return {
    type: firstIsTime ? 'line' : 'bar',
    xField,
    yFields,
    rows
  }
}

/** 该列是否数值列：判断不能只看第一行，首行恰好为 null/空时整列会被误判。 */
function isNumericColumn(rows, col) {
  for (const row of rows) {
    const v = row[col]
    if (v === null || v === undefined || v === '') continue
    return typeof v === 'number' || (typeof v === 'string' && Number.isFinite(Number(v)))
  }
  return false
}

function toNumber(v) {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** 两列量纲差 10 倍以上时，第二条系列共享 Y 轴会被压成 0 像素、图上完全看不见。 */
function needsSecondAxis(rows, cols) {
  if (cols.length < 2) return false
  const max = c => Math.max(...rows.map(r => Math.abs(toNumber(r[c]) ?? 0)))
  const [a, b] = [max(cols[0]), max(cols[1])]
  if (!a || !b) return false
  return Math.max(a, b) / Math.min(a, b) > 10
}

/**
 * 把图表规格转成 ECharts option。
 *
 * @param {{type: string, xField: string, yFields: string[], rows: object[]}} spec
 */
export function buildChartOption(spec) {
  const dualAxis = needsSecondAxis(spec.rows, spec.yFields)
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: spec.yFields },
    grid: { left: 70, right: dualAxis ? 70 : 20, top: 40, bottom: 40 },
    xAxis: {
      type: 'category',
      data: spec.rows.map(r => String(r[spec.xField])),
      axisLabel: { rotate: 30 }
    },
    yAxis: dualAxis
      ? [{ type: 'value', name: spec.yFields[0] }, { type: 'value', name: spec.yFields[1] }]
      : { type: 'value' },
    series: spec.yFields.map((field, i) => ({
      name: field,
      type: spec.type,
      smooth: spec.type === 'line',
      yAxisIndex: dualAxis ? i : 0,
      data: spec.rows.map(r => toNumber(r[field]))
    }))
  }
}
