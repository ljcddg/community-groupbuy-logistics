<script setup>
import { ref, onMounted } from 'vue'
import { login, setToken, getToken } from './api'
import StepOrder from './components/StepOrder.vue'
import StepDelivery from './components/StepDelivery.vue'
import StepException from './components/StepException.vue'

const tabs = [
  { key: 'order', index: '1', label: '下单 · 截单' },
  { key: 'delivery', index: '2', label: '配送单 · 状态机 · 路线规划' },
  { key: 'exception', index: '3', label: '异常 · 补发（一致性对比）' }
]
const active = ref('order')
const gatewayOk = ref(false)
const authMsg = ref('正在连接网关…')
const authFailed = ref(false)

async function bootstrap() {
  // 已有 token 直接复用，否则用演示账号自动登录（避免演示时被登录页打断）
  if (getToken()) {
    gatewayOk.value = true
    authMsg.value = '已登录（复用本地 token）'
    return
  }
  try {
    const data = await login('demo', '123456')
    setToken(data.token)
    gatewayOk.value = true
    authMsg.value = `已登录 · userId=${data.userId}`
  } catch (e) {
    gatewayOk.value = false
    authFailed.value = true
    authMsg.value = e.message
  }
}

onMounted(bootstrap)
</script>

<template>
  <div>
    <header class="app-header">
      <div>
        <h1 class="app-title">社区团购物流配送 · 业务流演示</h1>
        <p class="app-sub">
          下单 → 截单聚合 → 配送单状态机 → 路线规划 → 异常责任链 → 补发（最终一致 / 强一致）
        </p>
      </div>
      <div class="app-header-right">
        <span class="hint">
          <span class="dot-live" :class="{ 'dot-dead': !gatewayOk }"></span>{{ authMsg }}
        </span>
      </div>
    </header>

    <div v-if="authFailed" class="result err" style="margin-bottom: 16px">
      <div class="result-title">无法连接网关（127.0.0.1:7000）</div>
      <div>
        请先启动后端服务（<strong>尤其是网关</strong>）。可在项目根目录执行
        <span class="mono">python run.py start</span>，
        或确认网关端口与 <span class="mono">application.yml</span> 里的 server.port 一致（当前 7000）。
      </div>
    </div>

    <nav class="steps">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="step"
        :class="{ active: active === t.key }"
        @click="active = t.key"
      >
        <span class="step-index">{{ t.index }}</span>{{ t.label }}
      </button>
    </nav>

    <StepOrder v-show="active === 'order'" />
    <StepDelivery v-show="active === 'delivery'" />
    <StepException v-show="active === 'exception'" />
  </div>
</template>
