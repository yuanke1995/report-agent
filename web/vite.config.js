import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  // 前后端分离：前端由 vite / nginx 独立托管，不再打进后端 jar，
  // 所以站点根就是 /，不跟后端的 context-path 混在一起。
  base: '/',
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 只代理后端接口前缀。这里必须带 /api：后端 context-path 是 /report-agent，
      // 若只写 /report-agent，前端自己的资源路径也会被一起吃掉。
      // 走代理而非跨域直连，浏览器视角仍是同源，后端因此无需开 CORS。
      '/report-agent/api': {
        target: 'http://127.0.0.1:8091',
        changeOrigin: true
      },
      // Swagger / actuator 调试时也可从 5173 访问
      '/report-agent/swagger-ui': { target: 'http://127.0.0.1:8091', changeOrigin: true },
      '/report-agent/v3': { target: 'http://127.0.0.1:8091', changeOrigin: true },
      '/report-agent/actuator': { target: 'http://127.0.0.1:8091', changeOrigin: true }
    }
  }
})
