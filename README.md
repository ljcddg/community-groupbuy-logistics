# 社区团购物流配送系统（Community Group-Buy Logistics）

![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)
![Spring Cloud Alibaba](https://img.shields.io/badge/Spring%20Cloud%20Alibaba-2023.0.1.0-orange)

基于 **Spring Cloud Alibaba** 的微服务实战项目，覆盖社区团购配送全链路：
**下单 → 截单聚合 → 路线规划 → 配送状态机 → 异常处理与补发**。

按业务域拆分为**订单、配送、仓储、自提点 4 个业务服务 + Gateway 网关**，
公共代码（Feign 契约、状态枚举、MQ 常量）抽为 `logistics-common` 模块。

> 项目定位：微服务技术栈的综合实践项目。
> 关键设计取舍（状态机实现、路线规划算法、跨服务一致性方案）均在代码注释中说明。

---

## ✨ 功能特性

- **服务治理**：Nacos 注册发现 · OpenFeign 服务间调用（2s/5s 超时 + fallbackFactory 降级）· Sentinel 熔断 · Gateway 统一入口与 JWT 鉴权
- **配送单状态机**：枚举 + 转移表（8 状态、终态锁定），应用层校验 + DB 层 `CAS` 双重防止非法流转，同状态重复提交幂等放行
- **截单聚合**：按自提点分组生成配送单（同一自提点一张单），订单状态 CAS 防重复截单，Redisson 分布式锁保证多实例只跑一次
- **路线规划**：Neo4j 构建配送网络图（自提点节点 + 道路边），批量查询距离矩阵，**最近邻贪心 + 2-opt 局部搜索**排定配送顺序（TSP 近似解），最短路径算法补齐缺失点对距离；超容时 1~2 跳图查询找相邻有余量自提点就近拆分
- **跨服务一致性（双方案并存）**：补发默认走**本地消息表 + MQ 异步扣库存**（最终一致、可审计），另实现 **Seata AT 版本**（`@GlobalTransactional` + XID 随 Feign 请求头传播，强一致），按场景选用
- **可靠性**：MQ 消费双重幂等（Redis SETNX + 消息记录表唯一索引）· RocketMQ 延迟消息超时自动升级（CAS 防并发，最多 3 轮转人工）· Redisson 分布式锁防司机重复上报

## 🛠 技术栈

| 类别 | 技术 |
|---|---|
| 微服务框架 | Spring Boot 3.2.5 · Spring Cloud 2023.0.1 · Spring Cloud Alibaba 2023.0.1.0 |
| 服务治理 | Nacos（注册发现）· OpenFeign · Sentinel · Spring Cloud Gateway |
| 消息队列 | RocketMQ 5.x（普通消息 / 延迟消息 / 消费幂等） |
| 数据层 | MySQL 8 + MyBatis-Plus 3.5.5 · Redis（Redisson）· Neo4j（SDN） |
| 分布式事务 | Seata 2.0 AT 模式 · 本地消息表 |
| 其他 | JWT（jjwt）· Lombok · Hutool · Knife4j |

## 🏗 系统架构

```
                          ┌─────────────────┐
                          │  Gateway :7000  │  JWT 鉴权 / 路由转发
                          └────────┬────────┘
         ┌───────────────┬─────────┴────────┬───────────────┐
         ▼               ▼                  ▼               ▼
  ┌────────────┐  ┌────────────┐    ┌────────────┐  ┌────────────┐
  │   Order    │  │  Delivery  │    │ Warehouse  │  │   Pickup   │
  │   :8101    │  │   :8102    │    │   :8103    │  │   :8104    │
  │ 截单聚合   │  │ 状态机     │    │ 库存扣减   │  │ 自提点     │
  │ 本地消息表 │  │ 路线规划   │    │ 消费扣库存 │  │ Neo4j 图   │
  └─────┬──────┘  └─────┬──────┘    └─────┬──────┘  └────────────┘
        │               │                 │
        └───────────────┴─────────────────┘
                        │
              RocketMQ / MySQL / Redis / Neo4j / Seata TC
```

| 模块 | 端口 | 职责 |
|---|---|---|
| logistics-gateway | 7000 | 统一入口、JWT 鉴权、路由转发（`/api/order/**` → order，`/api/delivery/**` → delivery，`/api/stock/**` → warehouse，`/api/pickup/**` → pickup，StripPrefix=1） |
| logistics-order | 8101 | 订单、截单聚合、本地消息表 |
| logistics-delivery | 8102 | 配送单状态机、路线规划、异常处理、补发 |
| logistics-warehouse | 8103 | 库存扣减/回滚（消费 MQ） |
| logistics-pickup | 8104 | 自提点管理、Neo4j 相邻点查询 |
| logistics-common | — | Feign 契约、状态枚举、MQ 常量、JWT 工具、统一响应 |
| logistics-web | 5173 | Vue 3 + Vite 业务流演示前端 |

## 🚀 快速开始

### 1. 环境要求

- JDK 17+ · Maven 3.8+ · Node.js 18+（前端可选）
- 中间件（本地 Docker 或本机安装均可）：

| 中间件 | 端口 | 说明 |
|---|---|---|
| MySQL | 3306 | 建库 `community_logistics` |
| Redis | 6379 | |
| Nacos | 8848 | 注册中心 |
| RocketMQ | 9876 | NameServer |
| Neo4j | 7687（bolt） | 图数据库 |
| Seata Server | 9000 | TC 事务协调器（Docker 映射到宿主机 9000，yml 中 `grouplist` 直连） |
| Sentinel 控制台 | 8858 | 可选，查看熔断规则 |

### 2. 初始化数据

```bash
# MySQL：建 9 张表（脚本可重复执行）
mysql -uroot -p community_logistics < sql/init.sql

# Neo4j：建配送网络图（5 个自提点 + 道路边，可重复执行）
# 在 Neo4j Browser 中执行 sql/neo4j-init.cypher
```

### 3. 编译打包

```bash
mvn clean package -DskipTests
```

### 4. 启动服务

**方式一：一键脚本（推荐）**

```bash
python run.py start     # 按依赖顺序启动 5 个服务
python run.py status    # 查看端口状态
python run.py logs delivery   # 查看某服务日志
python run.py stop      # 全部停止
```

> 注意：`run.py` 中 gateway 以 **8090** 启动（按作者本机环境覆盖 yml 默认端口），
> 换环境请在 `run.py` 的 `SERVICES` 中按需修改。

**方式二：IDE 启动**

按 `logistics-order → logistics-delivery → logistics-warehouse → logistics-pickup → logistics-gateway` 顺序启动各 `*Application` 主类。

### 5. 启动前端（可选）

```bash
cd logistics-web
npm install
npm run dev        # http://localhost:5173
```

### 6. 环境自检（可选）

```bash
python verify_neo4j.py   # 验证 Neo4j 图数据与最短路径查询
python verify_seata.py   # 验证 Seata TC 连通性与全局事务
```

## 📡 接口演示

```bash
# 1. 登录拿 token（演示账号：任意用户名 + 密码 123456）
curl -X POST http://localhost:7000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"123456"}'

# 2. 演示下单（pickupId 见 sql/init.sql 与 neo4j-init.cypher）
curl -X POST http://localhost:7000/api/order/create \
  -H "Authorization: <token>" -H "Content-Type: application/json" \
  -d '{"pickupId":1,"pickupName":"阳光花园自提点","itemCount":5,"weightGram":3000}'

# 3. 截单：按自提点聚合生成配送单
curl -X POST http://localhost:7000/api/order/cutoff -H "Authorization: <token>"

# 4. 路线规划：查看 route / totalDistance / algorithm
curl -X POST http://localhost:7000/api/delivery/route/plan -H "Authorization: <token>"

# 5. 状态机转移表（一眼看清全部合法流转）
curl http://localhost:7000/api/delivery/status/table -H "Authorization: <token>"

# 6. 异常上报（责任链算处理方案）+ 30s 后观察超时升级日志
curl -X POST http://localhost:7000/api/delivery/1/exception \
  -H "Authorization: <token>" -H "Content-Type: application/json" \
  -d '{"exceptionType":1,"remark":"外包装破损"}'

# 7. 补发：两种一致性方案对比
curl -X POST http://localhost:7000/api/delivery/1/reship       -H "Authorization: <token>"  # 本地消息表 + MQ
curl -X POST http://localhost:7000/api/delivery/1/reship-seata -H "Authorization: <token>"  # Seata AT 强一致
```

## 📁 目录结构

```
├── logistics-common/      # 公共模块：Feign 契约、状态枚举、MQ 常量、JWT
├── logistics-gateway/     # 网关：JWT 鉴权过滤器、路由、登录
├── logistics-order/       # 订单：截单聚合、本地消息表
├── logistics-delivery/    # 配送：状态机、路线规划、异常责任链、补发
├── logistics-warehouse/   # 仓储：库存扣减、MQ 消费幂等
├── logistics-pickup/      # 自提点：Neo4j 相邻点查询
├── logistics-web/         # Vue 3 演示前端
├── sql/                   # init.sql（MySQL）+ neo4j-init.cypher（图数据）
├── run.py                 # 一键启停脚本
└── verify_*.py            # 环境自检脚本
```

## ⚙️ 配置说明

- 各服务 `application.yml` 中 MySQL / Redis 密码为**本地开发默认值**，生产部署请通过环境变量覆盖，不要把真实密码提交到仓库。
- Seata 默认 `registry.type=file` + `grouplist` 直连 TC（`127.0.0.1:9000`），改端口改 `seata.service.grouplist` 即可；接入 Nacos 注册 Seata Server 时改回 `registry.type=nacos`。
- Sentinel 控制台默认连 `127.0.0.1:8858`，未启动控制台不影响服务运行。

## 🤝 贡献与反馈

Issue / PR 欢迎。如果这个项目对你有帮助，点个 Star ⭐ 就是最大的支持。
