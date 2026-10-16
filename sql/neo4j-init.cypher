-- =====================================================================
-- Neo4j 图数据初始化 —— 配送网络图
-- 模型：(:Pickup {pickupId, pickupName, capacity, remainCapacity})-[:ROAD {distance}]->(:Pickup)
-- 说明：图只存"拓扑与关系"，配送顺序由应用层 TSP 启发式算法求解（见 RoutePlanService）
-- =====================================================================

// 1. 清空旧图（演示环境可重复执行）
MATCH (n:Pickup) DETACH DELETE n;

// 2. 创建自提点节点（与 MySQL t_pickup 保持一致，以 MySQL 为准同步过来）
CREATE (:Pickup {pickupId: 1, pickupName: '阳光花园自提点', capacity: 100, remainCapacity: 70});
CREATE (:Pickup {pickupId: 2, pickupName: '翠竹苑自提点',   capacity: 80,  remainCapacity: 5});
CREATE (:Pickup {pickupId: 3, pickupName: '万科城自提点',   capacity: 120, remainCapacity: 80});
CREATE (:Pickup {pickupId: 4, pickupName: '龙湖天街自提点', capacity: 150, remainCapacity: 130});
CREATE (:Pickup {pickupId: 5, pickupName: '金地格林自提点', capacity: 60,  remainCapacity: 5});

// 3. 创建道路边（双向，distance 单位：米）
//    注意：Neo4j 关系是有向的，双向道路需建两条边
MATCH (a:Pickup {pickupId:1}), (b:Pickup {pickupId:2}) CREATE (a)-[:ROAD {distance: 1200}]->(b), (b)-[:ROAD {distance: 1200}]->(a);
MATCH (a:Pickup {pickupId:1}), (b:Pickup {pickupId:3}) CREATE (a)-[:ROAD {distance: 2100}]->(b), (b)-[:ROAD {distance: 2100}]->(a);
MATCH (a:Pickup {pickupId:1}), (b:Pickup {pickupId:4}) CREATE (a)-[:ROAD {distance: 3400}]->(b), (b)-[:ROAD {distance: 3400}]->(a);
MATCH (a:Pickup {pickupId:2}), (b:Pickup {pickupId:3}) CREATE (a)-[:ROAD {distance: 1500}]->(b), (b)-[:ROAD {distance: 1500}]->(a);
MATCH (a:Pickup {pickupId:2}), (b:Pickup {pickupId:5}) CREATE (a)-[:ROAD {distance: 1800}]->(b), (b)-[:ROAD {distance: 1800}]->(a);
MATCH (a:Pickup {pickupId:3}), (b:Pickup {pickupId:4}) CREATE (a)-[:ROAD {distance: 1100}]->(b), (b)-[:ROAD {distance: 1100}]->(a);
MATCH (a:Pickup {pickupId:3}), (b:Pickup {pickupId:5}) CREATE (a)-[:ROAD {distance: 2600}]->(b), (b)-[:ROAD {distance: 2600}]->(a);
MATCH (a:Pickup {pickupId:4}), (b:Pickup {pickupId:5}) CREATE (a)-[:ROAD {distance: 2900}]->(b), (b)-[:ROAD {distance: 2900}]->(a);

// 4. 校验：查看全图
MATCH (a:Pickup)-[r:ROAD]->(b:Pickup)
RETURN a.pickupId AS from, a.pickupName AS fromName,
       b.pickupId AS to,   b.pickupName AS toName, r.distance AS distance
ORDER BY from, to;

// 5. 校验：两点间最短路径（这才是 Neo4j shortestPath 的正当用法）
MATCH (a:Pickup {pickupId:1}), (b:Pickup {pickupId:5})
MATCH p = shortestPath((a)-[:ROAD*..6]->(b))
RETURN [n IN nodes(p) | n.pickupId] AS path,
       reduce(s = 0, r IN relationships(p) | s + r.distance) AS totalDistance;

// 6. 校验：超容就近拆分 —— 找相邻且有余量的自提点
//    （自提点 2 剩余仅 5 件，需要 20 件时往邻居找）
MATCH (src:Pickup {pickupId: 2})
MATCH p = (src)-[:ROAD*1..2]->(cand:Pickup)
WHERE cand.remainCapacity >= 20
WITH cand, reduce(s = 0, r IN relationships(p) | s + r.distance) AS totalDist
ORDER BY totalDist ASC
RETURN cand.pickupId AS pickupId, cand.pickupName AS pickupName,
       cand.remainCapacity AS remainCapacity, totalDist AS distance
LIMIT 5;
