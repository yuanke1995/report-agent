<template>
  <div class="message user">
    <div class="bubble">{{ content }}</div>
  </div>
</template>

<script setup>
defineProps({
  content: { type: String, required: true }
})
</script>

<style scoped>
.message.user {
  display: flex;
  justify-content: flex-end;
}

.bubble {
  max-width: 85%;
  padding: 12px 16px;
  border-radius: 10px;
  background: #409eff;
  color: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .06);
}
</style>
