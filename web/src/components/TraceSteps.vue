<template>
  <div class="trace-steps">
    <div v-for="(step, i) in steps" :key="i" class="step" :class="step.status">
      <span class="step-dot" />
      <!-- 后端 AgentStep 自带给用户看的 label（"执行报表模板 返回 7 行"这类），
           比工具名更能说明这步干了什么，优先用它 -->
      <span class="step-action">{{ step.label || actionLabel(step.action) }}</span>
      <span class="step-status">{{ statusLabel(step.status) }}</span>
      <span v-if="step.status !== 'running' && step.elapsedMs" class="step-time">{{ step.elapsedMs }}ms</span>
      <span v-if="step.error" class="step-error" :title="step.error">{{ step.error }}</span>
    </div>
  </div>
</template>

<script setup>
import { actionLabel, statusLabel } from '../constants/labels'

defineProps({
  steps: { type: Array, required: true }
})
</script>

<style scoped>
.trace-steps {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
}

.step {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #606266;
}

.step-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c0c4cc;
  flex: none;
}

.step.running .step-dot {
  background: #409eff;
  animation: pulse 1s infinite;
}

.step.success .step-dot {
  background: #67c23a;
}

.step.failed .step-dot {
  background: #f56c6c;
}

.step-time {
  color: #c0c4cc;
  font-size: 12px;
}

.step-error {
  color: #f56c6c;
  font-size: 12px;
  max-width: 400px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@keyframes pulse {
  50% { opacity: .3; }
}
</style>
