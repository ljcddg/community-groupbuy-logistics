/**
 * API 层：基于原生 fetch 封装（零运行时依赖）。
 *
 * 设计要点：
 *  1) 所有请求经 Vite 代理打到网关 8090，由网关统一做 JWT 鉴权；
 *  2) token 存在内存 + localStorage，刷新页面不用重新登录；
 *  3) 统一解包后端的 Result{code,message,data,success}：成功返回 data，失败抛 Error(message)，
 *     这样业务代码里只关心正常路径。
 */

const TOKEN_KEY = 'lg_token'
let token = localStorage.getItem(TOKEN_KEY) || ''

export function setToken(t) {
  token = t || ''
  if (t) localStorage.setItem(TOKEN_KEY, t)
  else localStorage.removeItem(TOKEN_KEY)
}

export function getToken() {
  return token
}

/** 后端统一返回体解包 */
async function request(method, path, body) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = 'Bearer ' + token

  let resp
  try {
    resp = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    })
  } catch (e) {
    throw new Error('无法连接网关(8090)，请确认后端服务已启动')
  }

  let json
  try {
    json = await resp.json()
  } catch (e) {
    throw new Error(`响应解析失败 (HTTP ${resp.status})`)
  }

  // 后端约定：HTTP 200 + 业务码；success=true 视为成功
  if (json && json.success) return json.data

  const msg = (json && json.message) || `请求失败 (HTTP ${resp.status})`
  const err = new Error(msg)
  err.code = json && json.code
  throw err
}

const get = (p) => request('GET', p)
const post = (p, b) => request('POST', p, b)

// ---------------- 鉴权 ----------------
/** 演示登录：任意用户名 + 密码 123456 */
export const login = (username = 'demo', password = '123456') =>
  post('/auth/login', { username, password })

// ---------------- 订单 ----------------
export const orderApi = {
  list: () => get('/api/order/list'),
  create: (payload) => post('/api/order/create', payload),
  cutoff: () => post('/api/order/cutoff')
}

// ---------------- 配送 ----------------
export const deliveryApi = {
  list: () => get('/api/delivery/list'),
  detail: (id) => get(`/api/delivery/${id}`),
  assign: (id, driverId, driverName) =>
    post(`/api/delivery/${id}/assign`, { driverId, driverName }),
  transfer: (id, toStatus, remark) =>
    post(`/api/delivery/${id}/transfer`, { toStatus, remark }),
  statusTable: () => get('/api/delivery/status/table'),
  planRoute: () => post('/api/delivery/route/plan'),
  reportException: (id, exceptionType, remark) =>
    post(`/api/delivery/${id}/exception`, { exceptionType, remark }),
  /** 补发：本地消息表 + MQ 异步（最终一致） */
  reship: (id) => post(`/api/delivery/${id}/reship`),
  /** 补发：Seata AT 同步（强一致） */
  reshipSeata: (id) => post(`/api/delivery/${id}/reship-seata`)
}

// ---------------- 仓储 / 自提点 ----------------
export const stockApi = {
  query: (skuId = 1001) => get(`/api/stock/query?skuId=${skuId}`)
}

export const pickupApi = {
  list: () => get('/api/pickup/list')
}
