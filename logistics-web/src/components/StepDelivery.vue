<script setup>
/**
 * 步骤二：配送单 · 状态机 · 路线规划
 *
 * 对应简历里的三个点：
 *   1) 「枚举 + 转移表实现状态机，非法流转直接拒绝」
 *      —— 详情里按后端返回的 nextStates 动态渲染按钮，非法流转后端会返回 3002 并给出可选状态
 *   2) 「Neo4j 建配送网络图 → 查距离矩阵 → 应用层贪心 + 2-opt 排顺序」
 *      —— 路线规划按钮
 *   3) 状态机全量转移表可视化
 */
import { ref, onMounted, computed } from 'vue'
import { deliveryApi } from '../api'

const list = ref([])
const loading = ref(false)
const busy = ref(false)
const current = ref(null)
const detail = ref(null)
const result = ref(null)
const routeResult = ref(null)
const flowTable = ref([])

const driver = ref({ driverId: 501, driverName: '张师傅' })

const STATUS = {
  0: { cls: 's-0', text: '待分配' },
  1: { cls: 's-1', text: '已分配司机' },
  2: { cls: 's-2', text: '配送中' },
  3: { cls: 's-3', text: '已送达自提点' },
  4: { cls: 's-4', text: '已完成' },
  5: { cls: 's-5', text: '配送异常' },
  6: { cls: 's-6', text: '已取消' },
  7: { cls: 's-7', text: '补发中' }
}

const st = (code) => STATUS[code] || { cls: 's-0', text: String(code) }

const nextStates = computed(() => (detail.value && detail.value.nextStates) || [])

async function loadList() {
  loading.value = true
  try {
    list.value = (await deliveryApi.list()) || []
    if (current.value && !list.value.some((d) => d.id === current.value)) {
      current.value = null
      detail.value = null
    }
  } catch (e) {
    result.value = { type: 'err', title: '加载配送单失败', text: e.message }
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  current.value = row.id
  result.value = null
  try {
    detail.value = await deliveryApi.detail(row.id)
  } catch (e) {
    result.value = { type: 'err', title: '加载详情失败', text: e.message }
  }
}

async function doAssign() {
  busy.value = true
  result.value = null
  try {
    await deliveryApi.assign(current.value, Number(driver.value.driverId), driver.value.driverName)
    result.value = {
      type: 'ok',
      title: `已指派司机 ${driver.value.driverName}`,
      text: '状态：待分配(0) → 已分配司机(1)。流转同时写入 t_delivery_trace 轨迹，并可经 MQ 通知下游更新轨迹缓存。'
    }
    await refresh()
  } catch (e) {
    result.value = { type: 'err', title: '指派失败', text: e.message }
  } finally {
    busy.value = false
  }
}

async function doTransfer(toStatus) {
  busy.value = true
  result.value = null
  try {
    await deliveryApi.transfer(current.value, toStatus)
    const t = st(toStatus)
    result.value = { type: 'ok', title: `流转成功 → ${t.text}`, text: `已写入轨迹；当前可流转状态已更新。` }
    await refresh()
  } catch (e) {
    // 非法流转：后端返回业务码 3002，并附带"从 X 不能到 Y，可选：[...]"的友好提示
    result.value = {
      type: 'err',
      title: '流转被拒绝' + (e.code ? `（业务码 ${e.code}）` : ''),
      text: e.message
    }
  } finally {
    busy.value = false
  }
}

async function planRoute() {
  busy.value = true
  result.value = null
  routeResult.value = null
  try {
    const r = await deliveryApi.planRoute()
    routeResult.value = r
    result.value = {
      type: r && r.count === 0 ? 'info' : 'ok',
      title: r && r.count === 0 ? '无待规划配送单' : '路线规划完成',
      text: r && r.algorithm ? `算法：${r.algorithm}` : ''
    }
    await refresh()
  } catch (e) {
    result.value = { type: 'err', title: '路线规划失败', text: e.message }
  } finally {
    busy.value = false
  }
}

async function refresh() {
  await loadList()
  if (current.value) {
    try {
      detail.value = await deliveryApi.detail(current.value)
    } catch (e) { /* ignore */ }
  }
}

async function loadFlowTable() {
  try {
    flowTable.value = (await deliveryApi.statusTable()) || []
  } catch (e) { /* ignore */ }
}

onMounted(() => {
  loadList()
  loadFlowTable()
})
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        <div>
          <h3 class="card-title">配送单列表</h3>
          <p class="card-desc">点击任意一行查看详情、执行状态流转</p>
        </div>
        <div class="row">
          <button class="btn btn-sm" :disabled="loading" @click="loadList">刷新</button>
          <button class="btn btn-primary btn-sm" :disabled="busy" @click="planRoute">
            触发路线规划
          </button>
        </div>
      </div>

      <div v-if="loading" class="loading">加载中…</div>
      <div v-else-if="!list.length" class="empty">暂无配送单，请先到「步骤一」执行截单</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>ID</th><th>配送单号</th><th>批次</th><th>自提点</th>
              <th>件数</th><th>司机</th><th>状态</th><th>规划路线</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="d in list"
              :key="d.id"
              :class="{ selected: current === d.id }"
              style="cursor: pointer"
              @click="openDetail(d)"
            >
              <td class="mono">{{ d.id }}</td>
              <td class="mono">{{ d.deliveryNo }}</td>
              <td class="mono">{{ d.batchNo }}</td>
              <td>{{ d.pickupName }}</td>
              <td class="mono">{{ d.itemCount }}</td>
              <td>{{ d.driverName || '—' }}</td>
              <td><span class="badge" :class="st(d.status).cls">{{ st(d.status).text }}</span></td>
              <td class="mono">{{ d.routePlan || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="routeResult && routeResult.route" class="result info" style="margin-top: 12px">
        <div class="result-title">路线规划结果</div>
        <div>
          配送顺序：<span class="mono">{{ (routeResult.route || []).join(' → ') }}</span>
          · 总里程：<span class="mono">{{ routeResult.totalDistance }}</span> 米
          · 影响配送单：<span class="mono">{{ routeResult.affected }}</span> 张
        </div>
        <div v-if="routeResult.algorithm" class="hint" style="margin-top: 4px">
          {{ routeResult.algorithm }}
        </div>
      </div>

      <div v-if="result" class="result" :class="result.type">
        <div class="result-title">{{ result.title }}</div>
        <div v-if="result.text">{{ result.text }}</div>
      </div>
    </div>

    <div v-if="detail" class="card">
      <div class="card-head">
        <div>
          <h3 class="card-title">
            配送单详情 ·
            <span class="mono">{{ detail.deliveryNo }}</span>
          </h3>
          <p class="card-desc">
            自提点 {{ detail.pickupName }} · {{ detail.itemCount }} 件 /
            {{ detail.weightGram }} g
          </p>
        </div>
        <span class="badge" :class="st(detail.status).cls">{{ st(detail.status).text }}</span>
      </div>

      <div class="kv">
        <div class="kv-item">
          <div class="kv-label">总里程</div>
          <div class="kv-value">{{ detail.totalDistance ?? '—' }}</div>
        </div>
        <div class="kv-item">
          <div class="kv-label">规划顺序</div>
          <div class="kv-value">{{ detail.routePlan || '—' }}</div>
        </div>
        <div class="kv-item">
          <div class="kv-label">司机</div>
          <div class="kv-value">{{ detail.driverName || '—' }}</div>
        </div>
      </div>

      <div class="row" style="margin-top: 16px">
        <div class="field">
          <label>司机 ID</label>
          <input v-model.number="driver.driverId" type="number" />
        </div>
        <div class="field">
          <label>司机姓名</label>
          <input v-model="driver.driverName" />
        </div>
        <div class="field" style="justify-content: flex-end">
          <label>&nbsp;</label>
          <button class="btn" :disabled="busy || detail.status !== 0" @click="doAssign">
            指派司机
          </button>
        </div>
      </div>

      <div style="margin-top: 16px">
        <div class="hint" style="margin-bottom: 7px">
          可流转状态（由后端转移表返回，点击即尝试流转 —— 非法流转会被拒绝并给出可选状态）
        </div>
        <div class="row">
          <button
            v-for="n in nextStates"
            :key="n.code"
            class="btn btn-sm"
            :disabled="busy"
            @click="doTransfer(n.code)"
          >
            → {{ n.desc }}（{{ n.code }}）
          </button>
          <span v-if="!nextStates.length" class="hint">当前为终态，无可流转状态</span>
        </div>
      </div>

      <div v-if="detail.traces && detail.traces.length" style="margin-top: 18px">
        <div class="hint" style="margin-bottom: 6px">流转轨迹</div>
        <div class="timeline">
          <div v-for="(t, i) in detail.traces" :key="i" class="tl-item">
            <span class="mono">
              {{ t.fromStatus === null ? '—' : t.fromStatus }} → {{ t.toStatus }}
            </span>
            <span>{{ t.remark }}</span>
            <span class="tl-op">{{ t.operator }}</span>
            <span class="tl-time">{{ t.time }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div>
          <h3 class="card-title">状态机转移表</h3>
          <p class="card-desc">枚举 + 静态转移表，是被追问最多的地方</p>
        </div>
      </div>
      <div class="flow-table">
        <div v-for="f in flowTable" :key="f.code" class="flow-row">
          <span class="flow-from">
            <span class="badge" :class="st(f.code).cls">{{ f.code }} {{ f.desc }}</span>
          </span>
          <span class="hint">→</span>
          <span v-if="f.next && f.next.length" class="mono" style="font-size: 12.5px">
            {{ f.next.join('  |  ') }}
          </span>
          <span v-else class="hint">终态，无后继</span>
        </div>
      </div>
    </div>
  </div>
</template>
