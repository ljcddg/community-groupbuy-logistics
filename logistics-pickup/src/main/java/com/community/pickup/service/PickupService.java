package com.community.pickup.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.common.exception.BizException;
import com.community.common.result.ResultCode;
import com.community.common.util.Neo4jRecordUtil;
import com.community.pickup.entity.Pickup;
import com.community.pickup.mapper.PickupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自提点服务。
 *
 * <p><b>面试点：Neo4j 与 MySQL 的一致性</b>
 * <ul>
 *   <li>自提点数据同时存在 MySQL（业务表）和 Neo4j（图节点），必然要回答"怎么保持一致"。</li>
 *   <li><b>策略：MySQL 为权威源（source of truth），Neo4j 为只读副本。</b>
 *       变更时先写 MySQL，再异步同步到 Neo4j。</li>
 *   <li><b>为什么异步而不是双写强一致？</b> 图数据只用于"查询关系"，
 *       短暂延迟不影响业务正确性；强一致要引入分布式事务，收益不匹配成本。
 *       这是典型的"按需选一致性级别"。</li>
 *   <li>本 Demo 用定时全量同步（简单直观）；生产可改为"发 MQ -> 消费端增量更新图"。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PickupService {

    private final PickupMapper pickupMapper;
    private final Neo4jClient neo4jClient;

    public Pickup getById(Long id) {
        Pickup p = pickupMapper.selectById(id);
        if (p == null) {
            throw new BizException(ResultCode.PICKUP_NOT_FOUND);
        }
        return p;
    }

    public Integer remainCapacity(Long id) {
        return getById(id).getRemainCapacity();
    }

    public List<Pickup> listAll() {
        return pickupMapper.selectList(new LambdaQueryWrapper<Pickup>()
                .eq(Pickup::getStatus, 1).orderByAsc(Pickup::getId));
    }

    /** 转成 Map 便于 Feign 返回 */
    public Map<String, Object> toMap(Pickup p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("pickupName", p.getPickupName());
        m.put("address", p.getAddress());
        m.put("capacity", p.getCapacity());
        m.put("usedCapacity", p.getUsedCapacity());
        m.put("remainCapacity", p.getRemainCapacity());
        return m;
    }

    /** 占用容量（原子，防超容） */
    @Transactional(rollbackFor = Exception.class)
    public boolean occupy(Long id, Integer delta) {
        int affected = pickupMapper.occupy(id, delta);
        if (affected == 0) {
            throw new BizException(ResultCode.PICKUP_CAPACITY_EXCEEDED,
                    String.format("自提点[%d] 容量不足，无法占用 %d 件", id, delta));
        }
        return true;
    }

    /** 查询相邻自提点（从 Neo4j 图查询，供超容就近拆分使用） */
    public List<Map<String, Object>> adjacent(Long pickupId, Integer limit) {
        // 注意：Cypher 的 LIMIT 不接受参数占位符，只能字符串拼接；
        // 这里对 limit 做强校验（1~100）以防注入。
        int safeLimit = (limit == null || limit <= 0) ? 3 : Math.min(limit, 100);
        String cypher = """
                MATCH (src:Pickup {pickupId: $pickupId})
                MATCH (src)-[r:ROAD]->(adj:Pickup)
                RETURN adj.pickupId AS pickupId,
                       adj.pickupName AS pickupName,
                       adj.remainCapacity AS remainCapacity,
                       r.distance AS distance
                ORDER BY r.distance ASC
                LIMIT %d
                """.formatted(safeLimit);
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            // 注意：spring-data-neo4j 的 fetchAs(Map.class) 在多列 RETURN 时
            // 必须显式给 mapper，否则抛 "Records with more than one value ..."。
            // 这里统一走 Neo4jRecordUtil 封装。
            result = Neo4jRecordUtil.queryMaps(
                    neo4jClient, cypher,
                    Map.of("pickupId", pickupId),
                    "pickupId", "pickupName", "remainCapacity", "distance");
        } catch (Exception e) {
            log.error("相邻自提点查询失败 pickupId={}", pickupId, e);
        }
        return result;
    }

    /**
     * 定时把 MySQL 的自提点数据同步到 Neo4j（只更新节点属性，不动关系）。
     * 每 60 秒一次；生产可改为事件驱动（监听自提点变更 -> 发 MQ -> 增量同步）。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 20_000L)
    public void syncToNeo4j() {
        List<Pickup> all = pickupMapper.selectList(new LambdaQueryWrapper<Pickup>().eq(Pickup::getStatus, 1));
        if (all.isEmpty()) {
            return;
        }
        int ok = 0;
        for (Pickup p : all) {
            try {
                String cypher = """
                        MERGE (n:Pickup {pickupId: $pickupId})
                        SET n.pickupName = $pickupName,
                            n.capacity = $capacity,
                            n.remainCapacity = $remainCapacity
                        """;
                neo4jClient.query(cypher)
                        .bind(p.getId()).to("pickupId")
                        .bind(p.getPickupName()).to("pickupName")
                        .bind(p.getCapacity()).to("capacity")
                        .bind(p.getRemainCapacity()).to("remainCapacity")
                        .run();
                ok++;
            } catch (Exception e) {
                log.error("同步自提点到 Neo4j 失败 pickupId={}", p.getId(), e);
            }
        }
        log.info("自提点->Neo4j 同步完成 {}/{}", ok, all.size());
    }

    /** 手动触发一次同步（演示/排错用） */
    public int manualSync() {
        syncToNeo4j();
        return pickupMapper.selectList(null).size();
    }
}
