import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/report-agent/',
  plugins: [vue()],
  server: {
    port: 5173,
    // 本地开发代理：/report-agent 打到后端
    proxy: {
      '/report-agent': {
        target: 'http://127.0.0.1:8091',
        changeOrigin: true
      }
    }
  }
})
