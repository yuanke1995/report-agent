<template>
  <div ref="scroller" class="message-list">
    <WelcomePanel v-if="!messages.length" @pick="q => emit('pick', q)" />

    <template v-for="(m, i) in messages" :key="i">
      <UserMessage v-if="m.role === 'user'" :content="m.content" />
      <AssistantMessage
        v-else
        :message="m"
        @feedback="(msg, rating) => emit('feedback', msg, rating)"
      />
    </template>

    <div v-if="thinking" class="message assistant">
      <div class="bubble thinking">
        <a-spin size="small" />
        <span>正在分析…</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import UserMessage from './UserMessage.vue'
import AssistantMessage from './AssistantMessage.vue'
import WelcomePanel from './WelcomePanel.vue'

const props = defineProps({
  messages: { type: Array, required: true },
  thinking: { type: Boolean, default: false }
})

const emit = defineEmits(['pick', 'feedback'])

// 滚动跟随由列表自己负责：谁拥有滚动容器谁管滚动，
// 会话逻辑（useChat）因此不需要知道 DOM 的存在。
const scroller = ref(null)

watch(
  // 流式回答是在消息对象内部增量改字段的，deep 才能跟住
  () => [props.messages, props.thinking],
  () => {
    nextTick(() => {
      if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
    })
  },
  { deep: true }
)
</script>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.message {
  display: flex;
}

.bubble {
  max-width: 85%;
  padding: 12px 16px;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .06);
}

.thinking {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #909399;
}
</style>
