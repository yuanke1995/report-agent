<template>
  <div ref="root" class="result-chart" />
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { buildChartOption } from '../utils/chart'

const props = defineProps({
  spec: { type: Object, required: true }
})

// 组件自己持有容器 ref。原先的实现是 document.querySelectorAll('.chart') 取最后一个，
// 页面上出现第二张图后就会画到错误的容器里，并且靠 __chartInited 标记阻止重绘，
// 数据更新也不会刷新。
const root = ref(null)
let chart = null

function render() {
  if (!root.value) return
  if (!chart) chart = echarts.init(root.value)
  // 第二个参数 true = 不与旧 option 合并，避免切换图表类型时残留旧 series
  chart.setOption(buildChartOption(props.spec), true)
}

function handleResize() {
  chart?.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', handleResize)
})

watch(() => props.spec, render, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  // ECharts 实例持有 canvas 与事件监听，不 dispose 会随会话增长一直泄漏
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.result-chart {
  width: 100%;
  height: 300px;
  margin: 8px 0;
}
</style>
