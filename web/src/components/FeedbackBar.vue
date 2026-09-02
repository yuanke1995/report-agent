<template>
  <div class="feedback-bar">
    <a-button
      size="small"
      :type="value === 1 ? 'primary' : 'default'"
      :disabled="value !== 0"
      @click="emit('submit', 1)"
    >
      👍 有用
    </a-button>

    <!-- 差评要选原因：后端白名单是 数字不对/口径不对/答非所问/查询失败，
         直接点下去只能替用户瞎填，所以点👎先弹菜单挑原因再提交 -->
    <a-dropdown :trigger="['click']" :disabled="value !== 0">
      <a-button size="small" danger :type="value === -1 ? 'primary' : 'default'" :disabled="value !== 0">
        👎 没用
      </a-button>
      <template #overlay>
        <a-menu @click="({ key }) => emit('submit', -1, key)">
          <a-menu-item v-for="r in FEEDBACK_REASONS" :key="r">{{ r }}</a-menu-item>
        </a-menu>
      </template>
    </a-dropdown>

    <span v-if="value === -1 && reason" class="feedback-done">已记录：{{ reason }}</span>
  </div>
</template>

<script setup>
import { FEEDBACK_REASONS } from '../constants/labels'

defineProps({
  /** 0=未评价 1=有用 -1=没用 */
  value: { type: Number, default: 0 },
  /** 差评选中的原因，评价后展示用 */
  reason: { type: String, default: '' }
})

const emit = defineEmits(['submit'])
</script>

<style scoped>
.feedback-bar {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.feedback-done {
  color: #909399;
  font-size: 12px;
}
</style>
