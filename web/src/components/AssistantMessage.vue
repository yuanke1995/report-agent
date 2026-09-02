<template>
  <div class="message assistant">
    <div class="bubble">
      <TraceSteps v-if="message.steps.length" :steps="message.steps" />

      <SqlPanel v-if="message.sql" :sql="message.sql" />

      <ResultTable v-if="message.data?.rows" :data="message.data" />

      <ResultChart v-if="message.chartSpec" :spec="message.chartSpec" />

      <!-- 请求级错误单独成条，不和正常回答混在一起 -->
      <a-alert v-if="message.error" type="error" :message="message.error" show-icon class="answer-error" />

      <!-- 内容已在 useChat 里转义，这里渲染的是安全 HTML -->
      <div v-if="message.rendered" class="answer" v-html="message.rendered" />

      <FeedbackBar
        v-if="message.messageId"
        :value="message.feedback"
        :reason="message.feedbackReason"
        @submit="(...args) => emit('feedback', message, ...args)"
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

.answer-error {
  margin: 8px 0;
}

/* Markdown 渲染出的回答，样式照抄 antd 排版、只在此文件收敛 */
.answer {
  line-height: 1.7;
  font-size: 14px;
  color: #303133;
  word-break: break-word;
}

.answer > :first-child { margin-top: 0; }
.answer > :last-child { margin-bottom: 0; }
.answer p { margin: 6px 0; }
.answer ul, .answer ol { margin: 6px 0; padding-left: 22px; }
.answer li { margin: 2px 0; }
.answer h1, .answer h2, .answer h3, .answer h4 { margin: 10px 0 6px; font-size: 15px; }
.answer code { background: #f2f3f5; padding: 1px 5px; border-radius: 3px; font-size: 12px; font-family: 'SF Mono', Menlo, Consolas, monospace; }
.answer pre { background: #f8f8f8; padding: 10px; border-radius: 6px; overflow-x: auto; margin: 8px 0; }
.answer pre code { background: none; padding: 0; }
.answer table { border-collapse: collapse; margin: 8px 0; font-size: 13px; display: block; overflow-x: auto; }
.answer th, .answer td { border: 1px solid #e8e8e8; padding: 5px 10px; text-align: left; white-space: nowrap; }
.answer th { background: #fafafa; font-weight: 600; }
.answer blockquote { margin: 8px 0; padding-left: 10px; border-left: 3px solid #e8e8e8; color: #606266; }

</style>
