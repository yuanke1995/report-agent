<template>
  <div class="result-table">
    <a-table
      :data-source="data.rows"
      :columns="columns"
      :pagination="false"
      size="small"
      :scroll="{ x: true }"
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

const props = defineProps({
  data: { type: Object, required: true }
})

const columns = computed(() =>
  props.data.columns.map(c => ({ title: c, dataIndex: c, key: c, ellipsis: true }))
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
