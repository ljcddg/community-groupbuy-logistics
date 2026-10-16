<script setup>
/**
 * 步骤一：下单 & 截单
 *
 * 对应简历里的「截单后按自提点聚合生成配送单」：
 *   下单 → t_order(status=0 待截单)
 *   截单 → 按 pickup_id 聚合，每个自提点生成一张配送单
 */
import { ref, onMounted, computed } from 'vue'
import { orderApi, pickupApi } from '../api'

const orders = ref([])
const pickups = ref([])
const loading = ref(false)
const busy = ref(false)
const result = ref(null)

const form = ref({ pickupId: null, itemCount: 2, weightGram: 2000 })

const pendingCount = computed(() => orders.value.filter((o) => o.status === 0).length)

async function loadOrders() {
  loading.value = true
  try {
    orders.value = (await orderApi.list()) || []
  } catch (e) {
    result.value = { type: 'err', title: '加载订单失败', text: e.message }
  } finally {
    loading.value = false
  }
}

async function loadPickups() {
  try {
    pickups.value = (await pickupApi.list()) || []
    if (pickups.value.length && !form.value.pickupId) {
      form.value.pickupId = pickups.value[0].id
    }
  } catch (e) {
    /* 自提点列表失败不阻塞主流程 */
  }
}

async function submitOrder() {
  if (!form.value.pickupId) {
    result.value = { type: 'warn', title: '请先选择自提点', text: '' }
    return
  }
  busy.value = true
  result.value = null
  try {
    const p = pickups.value.find((x) => x.id === form.value.pickupId)
    const id = await orderApi.create({
      pickupId: form.value.pickupId,
      pickupName: p ? p.pickupName : null,
      itemCount: Number(form.value.itemCount),
      weightGram: Number(form.value.weightGram)
    })
    result.value = { type: 'ok', title: `下单成功，订单 ID = ${id}`, text: '状态为「待截单」，可继续下单或直接执行截单。' }
    await loadOrders()
  } catch (e) {
    result.value = { type: 'err', title: '下单失败', text: e.message }
  } finally {
    busy.value = false
  }
}

async function cutoff() {
  busy.value = true
  result.value = null
  try {
    const created = await orderApi.cutoff()
    result.value = created > 0
      ? {
          type: 'ok',
          title: `截单完成，生成配送单 ${created} 张`,
          text: '已按自提点聚合分组：同一自提点的订单合并为一张配送单。' +
                '同时订单服务在本地事务里写入本地消息表，并通过 MQ 通知物流服务。'
        }
      : { type: 'info', title: '本次截单没有可处理的订单', text: '可能所有订单已截单，或当下没有待截单订单。' }
    await loadOrders()
  } catch (e) {
    result.value = { type: 'err', title: '截单失败', text: e.message }
  } finally {
    busy.value = false
  }
}

const ORDER_STATUS = {
  0: { cls: 's-0', text: '待截单' },
  1: { cls: 's-1', text: '已截单' },
  2: { cls: 's-2', text: '已生成配送单' },
  3: { cls: 's-6', text: '已取消' }
}

onMounted(() => {
  loadOrders()
  loadPickups()
})

defineExpose({ reload: loadOrders })
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        <div>
          <h3 class="card-title">下单</h3>
          <p class="card-desc">模拟用户下单，订单落到 t_order（status = 0 待截单）</p>
        </div>
      </div>

      <div class="row">
        <div class="field">
          <label>自提点</label>
          <select v-model.number="form.pickupId">
            <option v-for="p in pickups" :key="p.id" :value="p.id">
              {{ p.pickupName }}
            </option>
            <option v-if="!pickups.length" :value="null">（未取到自提点）</option>
          </select>
        </div>
        <div class="field">
          <label>商品件数</label>
          <input v-model.number="form.itemCount" type="number" min="1" />
        </div>
        <div class="field">
          <label>总重量（克）</label>
          <input v-model.number="form.weightGram" type="number" min="0" step="100" />
        </div>
        <div class="field" style="justify-content: flex-end">
          <label>&nbsp;</label>
          <button class="btn btn-primary" :disabled="busy" @click="submitOrder">提交订单</button>
        </div>
      </div>

      <div v-if="result" class="result" :class="result.type">
        <div class="result-title">{{ result.title }}</div>
        <div v-if="result.text">{{ result.text }}</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div>
          <h3 class="card-title">订单列表</h3>
          <p class="card-desc">
            待截单 <strong>{{ pendingCount }}</strong> 笔 ·
            截单会按自提点聚合，一个自提点生成一张配送单
          </p>
        </div>
        <div class="row">
          <button class="btn btn-sm" :disabled="loading" @click="loadOrders">刷新</button>
          <button class="btn btn-primary btn-sm" :disabled="busy || !pendingCount" @click="cutoff">
            执行截单
          </button>
        </div>
      </div>

      <div v-if="loading" class="loading">加载中…</div>
      <div v-else-if="!orders.length" class="empty">暂无订单，请先下单</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>ID</th><th>订单号</th><th>自提点</th>
              <th>件数</th><th>重量(g)</th><th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in orders" :key="o.id">
              <td class="mono">{{ o.id }}</td>
              <td class="mono">{{ o.orderNo }}</td>
              <td>{{ o.pickupName }}</td>
              <td class="mono">{{ o.itemCount }}</td>
              <td class="mono">{{ o.weightGram }}</td>
              <td>
                <span class="badge" :class="(ORDER_STATUS[o.status] || {}).cls">
                  {{ (ORDER_STATUS[o.status] || {}).text || o.status }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
