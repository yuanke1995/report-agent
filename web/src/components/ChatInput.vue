<template>
  <div class="chat-input">
    <a-input
      :value="modelValue"
      placeholder="问点什么，比如：最近半年每个月的销售额"
      size="large"
      :disabled="loading"
      @update:value="v => emit('update:modelValue', v)"
      @press-enter="emit('send')"
    />
    <a-button
      type="primary"
      size="large"
      :loading="loading"
      :disabled="!modelValue.trim()"
      @click="emit('send')"
    >
      提问
    </a-button>
  </div>
</template>

<script setup>
defineProps({
  modelValue: { type: String, default: '' },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'send'])
</script>

<style scoped>
.chat-input {
  display: flex;
  gap: 8px;
  padding: 16px 24px;
  background: #fff;
  border-top: 1px solid #e8e8e8;
}
</style>
