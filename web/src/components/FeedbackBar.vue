<template>
  <div class="feedback-bar">
    <a-button
      size="small"
      :type="value === 1 ? 'primary' : 'default'"
      @click="emit('submit', 1)"
    >
      👍 有用
    </a-button>
    <a-button
      size="small"
      danger
      :type="value === -1 ? 'primary' : 'default'"
      @click="emit('submit', -1)"
    >
      👎 没用
    </a-button>
  </div>
</template>

<script setup>
defineProps({
  /** 0=未评价 1=有用 -1=没用 */
  value: { type: Number, default: 0 }
})

const emit = defineEmits(['submit'])
</script>

<style scoped>
.feedback-bar {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}
</style>
