<template>
  <div ref="scroller" class="message-list" @scroll="onScroll">
    <WelcomePanel v-if="!messages.length" @pick="q => emit('pick', q)" />

    <template v-for="(m, i) in messages" :key="i">
      <UserMessage v-if="m.role === 'user'" :content="m.content" />
      <AssistantMessage
        v-else
        :message="m"
        @feedback="(msg, rating, reason) => emit('feedback', msg, rating, reason)"
      />
    </template>

    <!-- 思考气泡直接挂在最后一条助手消息里：流式 token 还在写的时候弹
         一条新的"正在分析…"占位，看着像回答分了两截 -->
    <div v-if="lastAssistant?.pending" class="thinking">
      <a-spin size="small" />
      <span>{{ lastAssistant.stage || '正在分析…' }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import UserMessage from './UserMessage.vue'
import AssistantMessage from './AssistantMessage.vue'
import WelcomePanel from './WelcomePanel.vue'

const props = defineProps({
  messages: { type: Array, required: true },
  thinking: { type: Boolean, default: false }
})

const emit = defineEmits(['pick', 'feedback'])

/** 最后一条助手消息，思考气泡挂在这里。 */
const lastAssistant = computed(() => {
  for (let i = props.messages.length - 1; i >= 0; i--) {
    if (props.messages[i].role === 'assistant') return props.messages[i]
  }
  return null
})

// 滚动跟随由列表自己负责：谁拥有滚动容器谁管滚动，
// 会话逻辑（useChat）因此不需要知道 DOM 的存在。
const scroller = ref(null)
let stickToBottom = true

// 用户主动往上翻历史时不要再把他拽回底部
function onScroll() {
  const el = scroller.value
  if (!el) return
  stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

watch(
  // 流式回答是在消息对象内部增量改字段的，deep 才能跟住
  () => [props.messages, props.thinking],
  () => {
    if (!stickToBottom) return
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
