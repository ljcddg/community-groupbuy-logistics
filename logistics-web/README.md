# logistics-web · 业务流演示前端

社区团购物流配送系统的可视化演示界面。**Vue 3 + Vite**，无 UI 组件库依赖，纯手写样式。

## 启动

```bash
cd logistics-web
npm install       # 首次
npm run dev       # 打开 http://localhost:5173
```

> 端口固定 **5173**。Vite 已把 `/api` 与 `/auth` 代理到网关 **127.0.0.1:7000**，所以浏览器不涉及跨域。
>
> ⚠️ 这个端口必须与 `logistics-gateway/src/main/resources/application.yml` 的 `server.port` 保持一致。
> 改了网关端口却忘了改这里，症状是：后端 5 个服务都正常，前端却全部 `ECONNREFUSED`。

## 前置条件：后端要先起来

前端所有数据都走真实接口，**必须先启动后端**：

```bash
# 在项目根目录
python run.py start          # 依次启动 pickup / warehouse / delivery / order / gateway
python run.py status         # 确认 5 个端口都是 UP（含网关 7000，漏了它前端必然报错）
```

| 服务 | 端口 |
|---|---|
| logistics-gateway | **7000** |
| logistics-order | 8101 |
| logistics-delivery | 8102 |
| logistics-warehouse | 8103 |
| logistics-pickup | 8104 |

另外需要 Nacos 8848 / MySQL 3306 / Redis 6379 / RocketMQ 9876 / Neo4j 7687 / Seata 9000 都在运行。

## 免登录

页面加载时会用演示账号自动登录（用户名任意，密码 `123456`，网关本地实现），
token 存 localStorage，刷新不用重登。若网关不通，顶部会显示红色提示。

## 三个步骤对应简历里的业务板块

| 步骤 | 覆盖的简历点 |
|---|---|
| ① 下单 · 截单 | 截单后按自提点聚合生成配送单；本地消息表 + MQ 通知下游 |
| ② 配送单 · 状态机 · 路线规划 | 枚举 + 转移表状态机、非法流转拒绝；Neo4j 距离矩阵 + 贪心/2-opt 排顺序 |
| ③ 异常 · 补发 | 责任链按异常类型匹配方案；**两种补发方案对比**（本地消息表 MQ vs Seata AT） |

## 怎么演示 Seata 回滚（有说服力）

1. 步骤② 里把一张配送单流转到「配送中(2)」（点两次按钮：→ 已分配司机 → 配送中）
2. 步骤③ 选中它 → 上报异常（如「商品破损」）→ 状态变为「配送异常(5)」
3. 把 sku 1001 的库存改成 0（直接改库，或调仓储接口让它扣失败）
4. 点「发起补发（Seata AT）」→ 会返回业务码 **4002**，且配送单**回到「配送异常(5)」**
   —— 不是停在「补发中」，这就是 AT 回滚生效的证据
5. 恢复库存后再点一次 → 成功流转到「补发中(7)」，库存同步减少

想更严谨，可以跑根目录的 `verify_seata.py`，它把上面这套流程做成了自动化断言。

## 目录结构

```
logistics-web/
├── index.html
├── vite.config.js          # 网关代理配置
├── package.json
└── src/
    ├── main.js
    ├── App.vue             # 顶部 + 步骤导航 + 自动登录
    ├── style.css           # 全部样式（无 UI 库）
    ├── api/index.js        # fetch 封装 + 统一 Result 解包
    └── components/
        ├── StepOrder.vue       # ① 下单 / 截单
        ├── StepDelivery.vue    # ② 配送单 / 状态机 / 路线规划
        └── StepException.vue   # ③ 异常 / 补发对比
```
