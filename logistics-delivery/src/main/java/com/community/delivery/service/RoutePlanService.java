package com.community.delivery.service;

import com.community.common.exception.BizException;
import com.community.common.result.ResultCode;
import com.community.common.util.Neo4jRecordUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 路线规划 —— <b>这是简历里最容易讲错的地方，务必看清这里的定位</b>。
 *
 * <h3>先说结论：Neo4j 不负责"排配送顺序"</h3>
 * <ul>
 *   <li>Neo4j 的 {@code shortestPath} / Dijkstra 解决的是<b>两点间最短路</b>，
 *       而不是"按什么顺序访问所有点"。</li>
 *   <li>"访问全部自提点、顺序最优"是 <b>TSP（旅行商问题）</b>；多车是 <b>VRP（车辆路径问题）</b>，
 *       两者都是 NP-hard，Neo4j 没有内建求解器。</li>
 *   <li>所以正确分工是：<b>Neo4j 存拓扑 + 查关系/距离矩阵</b>，
 *       <b>顺序用应用层启发式算法（最近邻贪心 + 2-opt 优化）求近似最优</b>。</li>
 * </ul>
 * 面试被问"你怎么用 Neo4j 排配送顺序"，正确答法是上面这句分工，
 * 而不是"调 shortestPath" —— 后者会立刻暴露理解偏差。
 *
 * <h3>Neo4j 在本系统里真正承担的三件事</h3>
 * <ol>
 *   <li><b>图建模</b>：自提点为节点，点间可通行关系为边，边权=距离 → 得到配送网络图。</li>
 *   <li><b>距离矩阵查询</b>：用图查询批量取任意两点最短距离，供 TSP 启发式使用
 *       （避免在应用层硬编码全连接矩阵，路网变更只需改图）。</li>
 *   <li><b>超容就近拆分</b>：自提点容量超限时，用图查询找相邻的、有空余容量的自提点
 *       （见 {@link #findAdjacentWithCapacity}）—— 这才是图数据库的天然强项。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutePlanService {

    private final Neo4jClient neo4jClient;

    /** 贪心+2opt 的迭代上限，防大图卡死 */
    private static final int TWO_OPT_MAX_PASS = 200;

    /**
     * 为一批自提点规划配送顺序。
     *
     * @param pickupIds 本次要配送的自提点
     * @param depotId   起点（仓库/首站），可为 null 表示从第一个点出发
     * @return 规划结果（顺序、总距离、算法说明）
     */
    public RouteResult plan(List<Long> pickupIds, Long depotId) {
        if (pickupIds == null || pickupIds.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "自提点列表为空，无法规划路线");
        }
        if (pickupIds.size() == 1) {
            return new RouteResult(pickupIds, 0, "单点无需规划");
        }

        // 1) 从 Neo4j 拉取距离矩阵（图查询，而不是应用层硬编码）
        Map<Long, Map<Long, Integer>> distMatrix = loadDistanceMatrix(pickupIds);

        // 2) 最近邻贪心构造初始解
        List<Long> route = nearestNeighbor(pickupIds, depotId, distMatrix);
        int cost = routeCost(route, distMatrix);

        // 3) 2-opt 局部搜索优化（消除路径交叉）
        int[] improved = twoOpt(route, distMatrix);
        int finalCost = improved[0];

        List<Long> finalRoute = new ArrayList<>();
        for (int i = 1; i < improved.length; i++) {
            finalRoute.add((long) improved[i]);
        }

        log.info("路线规划完成：贪心初解 cost={} -> 2opt 优化后 cost={}，顺序={}", cost, finalCost, finalRoute);
        return new RouteResult(finalRoute, finalCost,
                String.format("Neo4j距离矩阵 + 最近邻贪心(初解%d) + 2-opt局部优化(终解%d)", cost, finalCost));
    }

    /**
     * 从 Neo4j 加载距离矩阵。
     * 图模型：(p1:Pickup)-[:ROAD {distance: 1200}]->(p2:Pickup)
     * 无直达边时用 shortestPath 兜底（真正体现"图查询"价值）。
     */
    public Map<Long, Map<Long, Integer>> loadDistanceMatrix(List<Long> ids) {
        Map<Long, Map<Long, Integer>> matrix = new LinkedHashMap<>();
        for (Long id : ids) {
            matrix.put(id, new HashMap<>());
            matrix.get(id).put(id, 0);
        }

        try {
            // 一次查询把子图内的边全取回来，交给应用层做矩阵（避免 N² 次独立查询）
            // 多列 RETURN 必须显式 mapper，统一走 Neo4jRecordUtil。
            String cypher = """
                    MATCH (a:Pickup)-[r:ROAD]->(b:Pickup)
                    WHERE a.pickupId IN $ids AND b.pickupId IN $ids
                    RETURN a.pickupId AS from, b.pickupId AS to, r.distance AS dist
                    """;
            List<Map<String, Object>> rows = Neo4jRecordUtil.queryMaps(
                    neo4jClient, cypher, Map.of("ids", ids), "from", "to", "dist");
            for (Map<String, Object> row : rows) {
                Long from = toLong(row.get("from"));
                Long to = toLong(row.get("to"));
                Integer dist = toInt(row.get("dist"));
                if (from != null && to != null && dist != null && matrix.containsKey(from)) {
                    matrix.get(from).put(to, dist);
                }
            }
        } catch (Exception e) {
            log.error("Neo4j 距离矩阵查询失败，降级为欧氏距离估算", e);
        }

        // 补齐缺失的边：用图上的最短路径兜底，仍缺失则给一个较大的估算值
        for (Long from : ids) {
            for (Long to : ids) {
                if (Objects.equals(from, to)) {
                    continue;
                }
                Map<Long, Integer> row = matrix.get(from);
                if (!row.containsKey(to)) {
                    Integer sp = shortestPathDistance(from, to);
                    row.put(to, sp != null ? sp : 999_999);
                }
            }
        }
        return matrix;
    }

    /**
     * 两点间最短路径距离 —— 这才是 Neo4j shortestPath 的正当用法。
     */
    public Integer shortestPathDistance(Long fromId, Long toId) {
        try {
            String cypher = """
                    MATCH (a:Pickup {pickupId: $from}), (b:Pickup {pickupId: $to})
                    MATCH p = shortestPath((a)-[:ROAD*..6]->(b))
                    RETURN reduce(s = 0, r IN relationships(p) | s + r.distance) AS dist
                    LIMIT 1
                    """;
            return neo4jClient.query(cypher)
                    .bind(fromId).to("from")
                    .bind(toId).to("to")
                    .fetchAs(Integer.class)
                    .one()
                    .orElse(null);
        } catch (Exception e) {
            log.debug("shortestPath 查询失败 {}->{}: {}", fromId, toId, e.getMessage());
            return null;
        }
    }

    /**
     * <b>超容就近拆分</b> —— 自提点容量超限时，用图查询找相邻且有余量的自提点。
     * 图数据库在这里的不可替代性：一跳/两跳邻居遍历比 MySQL 递归 CTE 或应用层多次查询高效得多。
     *
     * @param pickupId 超容的自提点
     * @param needCapacity 需要承接的订单量
     * @param maxHop   最多向外扩几跳（限制搜索范围）
     * @return 可以承接的自提点列表（按距离升序）
     */
    public List<Map<String, Object>> findAdjacentWithCapacity(Long pickupId, int needCapacity, int maxHop) {
        String cypher = """
                MATCH (src:Pickup {pickupId: $pickupId})
                MATCH p = (src)-[:ROAD*1..%d]->(cand:Pickup)
                WHERE cand.remainCapacity >= $need
                WITH cand, reduce(s = 0, r IN relationships(p) | s + r.distance) AS totalDist
                ORDER BY totalDist ASC
                RETURN cand.pickupId AS pickupId,
                       cand.pickupName AS pickupName,
                       cand.remainCapacity AS remainCapacity,
                       totalDist AS distance
                LIMIT 5
                """.formatted(Math.max(1, maxHop));

        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = Neo4jRecordUtil.queryMaps(
                    neo4jClient, cypher,
                    Map.of("pickupId", pickupId, "need", needCapacity),
                    "pickupId", "pickupName", "remainCapacity", "distance");
            for (Map<String, Object> row : rows) {
                Map<String, Object> m = new HashMap<>();
                m.put("pickupId", toLong(row.get("pickupId")));
                m.put("pickupName", String.valueOf(row.get("pickupName")));
                m.put("remainCapacity", toInt(row.get("remainCapacity")));
                m.put("distance", toInt(row.get("distance")));
                result.add(m);
            }
        } catch (Exception e) {
            log.error("相邻自提点容量查询失败 pickupId={}", pickupId, e);
        }
        return result;
    }

    // ==================== 启发式算法 ====================

    /** 最近邻贪心：每步选最近的未访问点 */
    private List<Long> nearestNeighbor(List<Long> ids, Long depotId, Map<Long, Map<Long, Integer>> matrix) {
        List<Long> unvisited = new ArrayList<>(ids);
        List<Long> route = new ArrayList<>();
        Long current = depotId != null && unvisited.contains(depotId) ? depotId : unvisited.remove(0);
        route.add(current);

        while (!unvisited.isEmpty()) {
            Long nearest = null;
            int best = Integer.MAX_VALUE;
            Map<Long, Integer> row = matrix.getOrDefault(current, Map.of());
            for (Long cand : unvisited) {
                int d = row.getOrDefault(cand, 999_999);
                if (d < best) {
                    best = d;
                    nearest = cand;
                }
            }
            if (nearest == null) {
                nearest = unvisited.get(0);
            }
            route.add(nearest);
            unvisited.remove(nearest);
            current = nearest;
        }
        return route;
    }

    /** 2-opt 局部搜索：反转子路径，若总距离下降则接受 */
    private int[] twoOpt(List<Long> route, Map<Long, Map<Long, Integer>> matrix) {
        int n = route.size();
        int[] arr = new int[n + 1];
        arr[0] = routeCost(route, matrix);
        for (int i = 0; i < n; i++) {
            arr[i + 1] = route.get(i).intValue();
        }

        boolean improved = true;
        int pass = 0;
        while (improved && pass++ < TWO_OPT_MAX_PASS) {
            improved = false;
            for (int i = 1; i < n - 1; i++) {
                for (int k = i + 1; k < n; k++) {
                    int delta = twoOptDelta(arr, i, k, matrix);
                    if (delta < 0) {
                        reverse(arr, i, k);
                        arr[0] += delta;
                        improved = true;
                    }
                }
            }
        }
        return arr;
    }

    /** 计算 2-opt 交换的距离增量（避免每次重算全路径，O(1)） */
    private int twoOptDelta(int[] arr, int i, int k, Map<Long, Map<Long, Integer>> matrix) {
        int n = arr.length - 1;
        int a = arr[i - 1], b = arr[i], c = arr[k], d = (k + 1 <= n) ? arr[k + 1] : -1;
        int dAB = dist(matrix, a, b);
        int dCD = dist(matrix, c, d);
        int dAC = dist(matrix, a, c);
        int dBD = dist(matrix, b, d);
        return (dAC + dBD) - (dAB + dCD);
    }

    private void reverse(int[] arr, int i, int k) {
        while (i < k) {
            int t = arr[i];
            arr[i] = arr[k];
            arr[k] = t;
            i++;
            k--;
        }
    }

    private int dist(Map<Long, Map<Long, Integer>> matrix, int a, int b) {
        if (a < 0 || b < 0) {
            return 0; // 开放路径，虚拟端点不算距离
        }
        return matrix.getOrDefault((long) a, Map.of()).getOrDefault((long) b, 999_999);
    }

    private int routeCost(List<Long> route, Map<Long, Map<Long, Integer>> matrix) {
        int total = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            total += matrix.getOrDefault(route.get(i), Map.of())
                    .getOrDefault(route.get(i + 1), 999_999);
        }
        return total;
    }

    private Long toLong(Object o) {
        return o == null ? null : Long.valueOf(o.toString());
    }

    private Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 路线规划结果 */
    public record RouteResult(List<Long> route, int totalDistance, String algorithm) {
    }
}
