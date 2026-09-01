<template>
  <div class="app">
    <AppHeader />

    <main class="main">
      <MessageList
        :messages="messages"
        :thinking="thinking"
        @pick="ask"
        @feedback="sendFeedback"
      />

      <ChatInput v-model="question" :loading="thinking" @send="ask" />
    </main>
  </div>
</template>

<script setup>
import AppHeader from './components/AppHeader.vue'
import MessageList from './components/MessageList.vue'
import ChatInput from './components/ChatInput.vue'
import { useChat } from './composables/useChat'

// App 只做布局装配：状态在 useChat，渲染在各组件，这里不该有业务逻辑
const { question, thinking, messages, ask, sendFeedback } = useChat()
</script>

<style scoped>
.app {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  max-width: 1000px;
  width: 100%;
  margin: 0 auto;
  min-height: 0;
}
</style>
