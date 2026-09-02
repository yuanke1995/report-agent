<template>
  <div class="result-table">
    <a-table
      :data-source="data.rows"
      :columns="columns"
      :pagination="data.rows.length > TABLE_PAGE_SIZE ? { pageSize: TABLE_PAGE_SIZE, size: 'small' } : false"
      size="small"
      :scroll="{ x: 'max-content' }"
      :row-key="rowKey"
    />
    <a-alert
      v-if="data.truncated"
      type="warning"
      message="结果已达行数上限被截断"
      show-icon
      class="truncate-warn"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { TABLE_PAGE_SIZE } from '../constants/labels'

const props = defineProps({
  data: { type: Object, required: true }
})

// 列名用数组形式：SQL 列名可能带 "."（如 order_id.amount），
// 字符串形式会被 antd 当成嵌套取值路径而取不到值
const columns = computed(() =>
  props.data.columns.map(c => ({
    title: c,
    dataIndex: [c],
    key: c,
    ellipsis: true,
    customRender: ({ text }) => (text === null || text === undefined ? '-' : String(text))
  }))
)

// 结果集是任意 SQL 的产物，没有稳定主键可用，直接拿行下标当 key
const rowKey = (record, index) => index
</script>

<style scoped>
.result-table {
  margin: 8px 0;
}

.truncate-warn {
  margin-top: 8px;
}
</style>
