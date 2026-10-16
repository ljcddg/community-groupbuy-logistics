<script setup>
/**
 * 步骤三：异常处理 · 补发（两种一致性方案对比）
 *
 * 对应简历里的：
 *   1) 「责任链模式按类型匹配处理方案」→ 上报异常后展示后端算出的方案
 *   2) 「补发涉及物流建单 + 仓储扣库存」→ 这里并排演示两种实现：
 *        · 本地消息表 + MQ 异步扣库存 → 最终一致
 *        · Seata AT（@GlobalTransactional + 同步 Feign）→ 强一致，失败即全局回滚
 */
import { ref, onMounted, computed } from 'vue'
import { deliveryApi, stockApi } from '../api'

const list = ref([])
const stock = ref(null)
const loading = ref(false)
const busy = ref(false)
const selectedId = ref(null)
const result = ref(null)

const exForm = ref({ exceptionType: 1, remark: '外包装破损，客户拒收' })
const reshipCount = ref(1)

const EX_TYPES = [
  { code: 1, name: '商品破损', plan: '按件赔付 + 补发' },
  { code: 2, name: '商品丢失', plan: '全额赔付 + 全额补发' },
  { code: 3, name: '客户拒收', plan: '退回仓储，不补发' },
  { code: 4, name: '配送超时', plan: '催办 + 减免配送费' }
]

const selected = computed(() => list.value.find((d) => d.id === selectedId.value) || null)

/** 只有「配送中(2)」和「异常(5)」状态的单适合做异常/补发演示 */
const canReport = computed(() => selected.value && selected.value.status === 2)
const canReship = computed(() => selected.value && selected.value.status === 5)

async function loadList() {
  loading.value = true
  try {
    list.value = (await deliveryApi.list()) || []
  } catch (e) {
    result.value = { type: 'err', title: '加载配送单失败', text: e.message }
  } finally {
    loading.value = false
  }
}

async function loadStock() {
  try {
    stock.value = await stockApi.query(1001)
  } catch (e) {
    stock.value = null
  }
}

async function report() {
  if (!selectedId.value) return
  busy.value = true
  result.value = null
  try {
    const exId = await deliveryApi.reportException(
      selectedId.value,
      Number(exForm.value.exceptionType),
      exForm.value.remark
    )
    const t = EX_TYPES.find((x) => x.code === Number(exForm.value.exceptionType))
    result.value = {
      type: 'ok',
      title: `异常已上报，异常单 ID = ${exId}`,
      text:
        `责任链按类型「${t ? t.name : ''}」匹配到处理方案：${t ? t.plan : ''}。` +
        '配送单已转入「配送异常(5)」，同时投递了一条延迟消息用于超时升级。'
    }
    await refreshAll()
  } catch (e) {
    result.value = { type: 'err', title: '上报异常失败', text: e.message }
  } finally {
    busy.value = false
  }
}

/** 方案 A：本地消息表 + MQ 异步 → 最终一致 */
async function doReshipMq() {
  busy.value = true
  result.value = null
  const before = stock.value
  try {
    await deliveryApi.reship(selectedId.value)
    result.value = {
      type: 'info',
      title: '已发起补发（本地消息表 + MQ）',
      text:
        '接口立即返回：物流侧在本地事务里把配送单转入「补发中」，并把消息写入本地消息表；' +
        '随后经 MQ 异步通知仓储扣库存。库存可能需要片刻才变化 —— 这正是「最终一致」的特征。'
    }
    // 稍等再刷库存，便于观察异步生效
    setTimeout(refreshAll, 1200)
    setTimeout(refreshAll, 2600)
  } catch (e) {
    result.value = { type: 'err', title: '补发失败', text: e.message }
  } finally {
    busy.value = false
    if (before !== null) setTimeout(loadStock, 1200)
  }
}

/** 方案 B：Seata AT → 强一致 */
async function doReshipSeata() {
  busy.value = true
  result.value = null
  try {
    await deliveryApi.reshipSeata(selectedId.value)
    result.value = {
      type: 'ok',
      title: '补发成功（Seata AT 全局事务已提交）',
      text:
        '物流侧改状态 + 仓储侧扣库存处在同一个全局事务里，XID 经 Feign 请求头传播到仓储服务；' +
        '两步都成功后事务才提交。若扣库存失败，物流侧已提交的改状态会被 undo_log 反向补偿 —— 强一致。'
    }
    await refreshAll()
  } catch (e) {
    result.value = {
      type: 'err',
      title: '补发失败，全局事务已回滚' + (e.code ? `（业务码 ${e.code}）` : ''),
      text:
        e.message +
        ' 注意：此时配送单状态会回到「配送异常(5)」，而不是停留在「补发中」' +
        ' —— 这就是 AT 模式回滚生效的证据。'
    }
    await refreshAll()
  } finally {
    busy.value = false
  }
}

async function refreshAll() {
  await loadList()
  await loadStock()
}

onMounted(refreshAll)
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        <div>
          <h3 class="card-title">上报配送异常</h3>
          <p class="card-desc">
            选择一张 <strong>配送中(2)</strong> 的配送单上报异常，责任链会按类型算出处理方案
          </p>
        </div>
        <button class="btn btn-sm" :disabled="loading" @click="refreshAll">刷新</button>
      </div>

      <div v-if="loading" class="loading">加载中…</div>
      <div v-else-if="!list.length" class="empty">暂无配送单，请先完成「步骤一」的截单</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr><th>选择</th><th>ID</th><th>配送单号</th><th>自提点</th><th>状态</th></tr>
          </thead>
          <tbody>
            <tr
              v-for="d in list"
              :key="d.id"
              :class="{ selected: selectedId === d.id }"
              style="cursor: pointer"
              @click="selectedId = d.id"
            >
              <td>
                <input type="radio" :checked="selectedId === d.id" @change="selectedId = d.id" />
              </td>
              <td class="mono">{{ d.id }}</td>
              <td class="mono">{{ d.deliveryNo }}</td>
              <td>{{ d.pickupName }}</td>
              <td>
                <span class="badge" :class="'s-' + (d.status === 6 ? 6 : d.status)">
                  {{
                    {
                      0: '待分配', 1: '已分配司机', 2: '配送中', 3: '已送达自提点',
                      4: '已完成', 5: '配送异常', 6: '已取消', 7: '补发中'
                    }[d.status] || d.status
                  }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="row" style="margin-top: 16px">
        <div class="field" style="min-width: 170px">
          <label>异常类型</label>
          <select v-model.number="exForm.exceptionType">
            <option v-for="t in EX_TYPES" :key="t.code" :value="t.code">
              {{ t.name }} —— {{ t.plan }}
            </option>
          </select>
        </div>
        <div class="field" style="flex: 1; min-width: 220px">
          <label>备注</label>
          <input v-model="exForm.remark" />
        </div>
        <div class="field" style="justify-content: flex-end">
          <label>&nbsp;</label>
          <button class="btn btn-danger" :disabled="busy || !canReport" @click="report">
            上报异常
          </button>
        </div>
      </div>
      <div v-if="selectedId && !canReport" class="hint" style="margin-top: 8px">
        当前选中的配送单不是「配送中(2)」状态，无法上报异常。
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div>
          <h3 class="card-title">补发 · 两种一致性方案对比</h3>
          <p class="card-desc">
            同样一件事（物流改状态 + 仓储扣库存），两种不同的实现
            <span v-if="stock !== null">
              · 当前 sku 1001 库存：<strong class="mono">{{ stock }}</strong>
            </span>
            <span v-else class="hint">· 库存读取失败</span>
          </p>
        </div>
      </div>

      <div class="compare">
        <div class="compare-box">
          <h4><span class="tag tag-mq">最终一致</span>本地消息表 + MQ</h4>
          <p>
            物流侧在本地事务里改状态、并写一条消息记录；事务提交后异步投递 MQ，
            仓储消费后扣库存，用业务号做幂等。投递失败有定时补偿重投。
            <br />
            <strong>特点</strong>：异步、可容忍秒级延迟、不锁资源、天然可重试。
          </p>
          <button class="btn" :disabled="busy || !canReship" @click="doReshipMq">
            发起补发（MQ 版）
          </button>
        </div>

        <div class="compare-box">
          <h4><span class="tag tag-seata">强一致</span>Seata AT 模式</h4>
          <p>
            物流侧与仓储侧处于同一个全局事务，XID 经 Feign 请求头传播。一阶段各自提交本地事务
            并记录前后镜像到 <span class="mono">undo_log</span>；任一步失败，TC 驱动所有分支
            反向补偿。
            <br />
            <strong>特点</strong>：同步、失败即整体回滚、对业务代码侵入小（一个注解）。
          </p>
          <button class="btn btn-primary" :disabled="busy || !canReship" @click="doReshipSeata">
            发起补发（Seata AT）
          </button>
        </div>
      </div>

      <div v-if="selectedId && !canReship" class="hint" style="margin-top: 12px">
        补发要求配送单处于「配送异常(5)」状态 —— 请先对上方选中的配送单上报一次异常。
      </div>

      <div v-if="result" class="result" :class="result.type" style="margin-top: 14px">
        <div class="result-title">{{ result.title }}</div>
        <div v-if="result.text">{{ result.text }}</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div>
          <h3 class="card-title">演示提示</h3>
        </div>
      </div>
      <p class="hint" style="line-height: 1.9">
        想验证「Seata 真的会回滚」，可以这样做：<br />
        1. 对一张「配送中(2)」的单上报异常 → 状态变为「配送异常(5)」；<br />
        2. 把这个 SKU 的库存改成 0（后端可直接改库，或调
        <span class="mono">/api/stock/deduct</span> 让它扣失败）；<br />
        3. 点「发起补发（Seata AT）」→ 会返回业务码 4002，且配送单<strong>回到「配送异常(5)」</strong>，
        而不是停在「补发中」；<br />
        4. 恢复库存后再点一次 → 成功流转到「补发中(7)」，库存同步减少。
      </p>
    </div>
  </div>
</template>
