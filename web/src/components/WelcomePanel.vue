<template>
  <div class="welcome">
    <p>用自然语言查数据，例如：</p>
    <div class="suggestions">
      <button v-for="q in SUGGESTIONS" :key="q" @click="emit('pick', q)">{{ q }}</button>
    </div>
  </div>
</template>

<script setup>
import { SUGGESTIONS } from '../constants/labels'

const emit = defineEmits(['pick'])
</script>

<style scoped>
.welcome {
  text-align: center;
  color: #909399;
  margin-top: 80px;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-top: 16px;
}

.suggestions button {
  padding: 8px 14px;
  border: 1px solid #d9d9d9;
  border-radius: 16px;
  background: #fff;
  cursor: pointer;
  color: #409eff;
}

.suggestions button:hover {
  border-color: #409eff;
}
</style>
