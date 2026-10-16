package com.community.delivery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.common.enums.DeliveryStatusEnum;
import com.community.common.exception.BizException;
import com.community.common.feign.FeignApi;
import com.community.common.result.Result;
import com.community.delivery.entity.Delivery;
import com.community.delivery.entity.DeliveryTrace;
import com.community.delivery.mapper.DeliveryMapper;
import com.community.delivery.mapper.DeliveryTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 配送单管理：创建、查询、状态流转入口、路线规划触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryMapper deliveryMapper;
    private final DeliveryTraceMapper traceMapper;
    private final DeliveryStateMachineService stateMachine;
    private final RoutePlanService routePlanService;
    private final FeignApi.PickupClient pickupClient;

    /**
     * 创建配送单。
     * 面试点：创建时先校验自提点剩余容量；若容量不足，
     * 调用 Neo4j 图查询找相邻自提点做<b>就近拆分</b>（这部分是"图查询"的真正用武之地）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(String batchNo, Long pickupId, String pickupName,
                       Integer itemCount, Integer weightGram) {

        // 容量校验 + 超容就近拆分
        int remain = queryRemainCapacity(pickupId);
        if (remain < itemCount) {
            log.warn("自提点 {} 剩余容量 {} < 需求 {}，触发就近拆分", pickupId, remain, itemCount);
            splitToAdjacent(pickupId, pickupName, itemCount, remain);
        }

        Delivery d = new Delivery();
        d.setDeliveryNo("DL" + System.currentTimeMillis() + (int) (Math.random() * 1000));
        d.setBatchNo(batchNo);
        d.setPickupId(pickupId);
        d.setPickupName(pickupName);
        d.setItemCount(itemCount);
        d.setWeightGram(weightGram);
        d.setStatus(DeliveryStatusEnum.PENDING_ASSIGN.getCode());
        d.setCreateTime(LocalDateTime.now());
        d.setUpdateTime(LocalDateTime.now());
        deliveryMapper.insert(d);

        // 记录初始轨迹
        DeliveryTrace trace = new DeliveryTrace();
        trace.setDeliveryId(d.getId());
        trace.setDeliveryNo(d.getDeliveryNo());
        trace.setFromStatus(null);
        trace.setToStatus(DeliveryStatusEnum.PENDING_ASSIGN.getCode());
        trace.setRemark("配送单生成");
        trace.setOperator("system");
        trace.setCreateTime(LocalDateTime.now());
        traceMapper.insert(trace);

        log.info("配送单已创建 no={} pickupId={} itemCount={}", d.getDeliveryNo(), pickupId, itemCount);
        return d.getId();
    }

    private int queryRemainCapacity(Long pickupId) {
        try {
            Result<Integer> r = pickupClient.remainCapacity(pickupId);
            if (r != null && r.isSuccess() && r.getData() != null) {
                return r.getData();
            }
        } catch (Exception e) {
            log.warn("查询自提点容量失败 pickupId={}，默认放行", pickupId, e);
        }
        return Integer.MAX_VALUE;
    }

    /** 超容就近拆分：图查询找有余量的相邻自提点 */
    private void splitToAdjacent(Long pickupId, String pickupName, Integer itemCount, int remain) {
        int need = itemCount - Math.max(remain, 0);
        List<Map<String, Object>> adjacent = routePlanService.findAdjacentWithCapacity(pickupId, need, 2);
        if (adjacent.isEmpty()) {
            log.warn("自提点 {} 超容且无相邻自提点可承接，仍按原自提点建单（转人工）", pickupId);
            return;
        }
        // 演示：记录拆分建议（真实场景会额外生成一张配送单指向相邻自提点）
        log.info("超容拆分建议 pickupId={} need={} 候选={}", pickupId, need, adjacent);
    }

    public Delivery getById(Long id) {
        Delivery d = deliveryMapper.selectById(id);
        if (d == null) {
            throw new BizException(com.community.common.result.ResultCode.DELIVERY_NOT_FOUND);
        }
        return d;
    }

    /** 配送单详情（含轨迹 + 可流转的下一状态） */
    public Map<String, Object> detail(Long id) {
        Delivery d = getById(id);
        Map<String, Object> m = new HashMap<>();
        m.put("id", d.getId());
        m.put("deliveryNo", d.getDeliveryNo());
        m.put("batchNo", d.getBatchNo());
        m.put("pickupId", d.getPickupId());
        m.put("pickupName", d.getPickupName());
        m.put("itemCount", d.getItemCount());
        m.put("weightGram", d.getWeightGram());
        m.put("driverName", d.getDriverName());
        m.put("status", d.getStatus());

        DeliveryStatusEnum st = DeliveryStatusEnum.of(d.getStatus());
        m.put("statusDesc", st == null ? "未知" : st.getDesc());
        m.put("nextStates", st == null ? List.of() :
                st.nextStates().stream().map(s -> Map.of("code", s.getCode(), "desc", s.getDesc()))
                        .collect(Collectors.toList()));
        m.put("routePlan", d.getRoutePlan());
        m.put("totalDistance", d.getTotalDistance());

        List<DeliveryTrace> traces = traceMapper.selectByDeliveryId(id);
        m.put("traces", traces.stream().map(t -> {
            Map<String, Object> tm = new HashMap<>();
            tm.put("fromStatus", t.getFromStatus());
            tm.put("toStatus", t.getToStatus());
            tm.put("remark", t.getRemark());
            tm.put("operator", t.getOperator());
            tm.put("time", t.getCreateTime() == null ? null : t.getCreateTime().toString());
            return tm;
        }).collect(Collectors.toList()));
        return m;
    }

    public List<Delivery> listAll() {
        return deliveryMapper.selectList(new LambdaQueryWrapper<Delivery>()
                .orderByDesc(Delivery::getId).last("LIMIT 100"));
    }

    /** 触发路线规划：把所有待配送自提点排序，结果写回各配送单 */
    public Map<String, Object> planRoute() {
        List<Delivery> pending = deliveryMapper.selectPendingForRoute();
        if (pending.isEmpty()) {
            return Map.of("message", "无待规划配送单", "count", 0);
        }
        List<Long> pickupIds = pending.stream().map(Delivery::getPickupId).distinct().collect(Collectors.toList());
        RoutePlanService.RouteResult result = routePlanService.plan(pickupIds, pickupIds.get(0));

        String routeStr = result.route().stream().map(String::valueOf).collect(Collectors.joining(","));
        for (Delivery d : pending) {
            deliveryMapper.updateRoute(d.getId(), routeStr, result.totalDistance());
        }
        log.info("路线规划完成：{}", routeStr);
        return Map.of(
                "route", result.route(),
                "totalDistance", result.totalDistance(),
                "algorithm", result.algorithm(),
                "affected", pending.size()
        );
    }

    /** 状态流转（对外统一入口） */
    public void transfer(Long id, Integer toStatusCode, Long driverId, String driverName, String remark) {
        DeliveryStatusEnum to = DeliveryStatusEnum.of(toStatusCode);
        if (to == null) {
            throw new BizException(com.community.common.result.ResultCode.PARAM_ERROR, "未知目标状态: " + toStatusCode);
        }
        stateMachine.transfer(id, to, driverId, driverName, "api", remark);
    }
}
