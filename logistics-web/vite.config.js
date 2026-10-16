import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 通过 Vite 代理把 /api 与 /auth 转发到网关，避免浏览器跨域。
// 端口以 logistics-gateway/src/main/resources/application.yml 的 server.port 为准 ——
// 当前是 7000。改网关端口时这里要同步改，否则后端全通、前端仍报 ECONNREFUSED。
const GATEWAY = 'http://127.0.0.1:7000'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    open: false,
    proxy: {
      '/api': { target: GATEWAY, changeOrigin: true },
      '/auth': { target: GATEWAY, changeOrigin: true }
    }
  }
})
