<template>
  <div class="message assistant">
    <div class="bubble">
      <TraceSteps v-if="message.steps.length" :steps="message.steps" />

      <SqlPanel v-if="message.sql" :sql="message.sql" />

      <ResultTable v-if="message.data?.rows" :data="message.data" />

      <ResultChart v-if="message.chartSpec" :spec="message.chartSpec" />

      <!-- 内容已在 useChat 里转义，这里渲染的是安全 HTML -->
      <div class="answer" v-html="message.rendered" />

      <FeedbackBar
        v-if="message.messageId"
        :value="message.feedback"
        @submit="rating => emit('feedback', message, rating)"
      />
    </div>
  </div>
</template>

<script setup>
import TraceSteps from './TraceSteps.vue'
import SqlPanel from './SqlPanel.vue'
import ResultTable from './ResultTable.vue'
import ResultChart from './ResultChart.vue'
import FeedbackBar from './FeedbackBar.vue'

defineProps({
  message: { type: Object, required: true }
})

const emit = defineEmits(['feedback'])
</script>

<style scoped>
.message.assistant {
  display: flex;
}

.bubble {
  max-width: 85%;
  padding: 12px 16px;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .06);
}

.answer {
  line-height: 1.7;
  font-size: 14px;
  color: #303133;
  white-space: pre-wrap;
}
</style>
