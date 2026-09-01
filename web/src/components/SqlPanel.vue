<template>
  <a-collapse size="small" class="sql-panel">
    <a-collapse-panel key="sql" header="查看 SQL">
      <pre class="sql">{{ sql }}</pre>
    </a-collapse-panel>
  </a-collapse>
</template>

<script setup>
defineProps({
  sql: { type: String, required: true }
})
</script>

<style scoped>
.sql-panel {
  margin: 8px 0;
}

.sql {
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  background: #f8f8f8;
  padding: 10px;
  border-radius: 6px;
  overflow-x: auto;
  white-space: pre-wrap;
}
</style>
