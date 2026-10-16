package com.community.delivery.controller;

import com.community.common.enums.DeliveryStatusEnum;
import com.community.common.result.Result;
import com.community.delivery.entity.Delivery;
import com.community.delivery.service.DeliveryExceptionService;
import com.community.delivery.service.DeliveryService;
import com.community.delivery.service.ReshipService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 物流服务对外接口。
 */
@Slf4j
@RestController
@RequestMapping("/delivery")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final DeliveryExceptionService exceptionService;
    private final ReshipService reshipService;

    /** 创建配送单（供订单服务 Feign 调用） */
    @PostMapping("/create")
    public Result<Long> create(@RequestBody Map<String, Object> req) {
        Long pickupId = toLong(req.get("pickupId"));
        String pickupName = str(req.get("pickupName"));
        Integer itemCount = toInt(req.get("itemCount"));
        Integer weightGram = toInt(req.get("weightGram"));
        String batchNo = str(req.get("batchNo"));
        Long id = deliveryService.create(batchNo, pickupId, pickupName, itemCount, weightGram);
        return Result.ok(id);
    }

    /** 配送单详情（含轨迹 + 可流转状态） */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return Result.ok(deliveryService.detail(id));
    }

    @GetMapping("/list")
    public Result<List<Delivery>> list() {
        return Result.ok(deliveryService.listAll());
    }

    /** 状态流转 */
    @PostMapping("/{id}/transfer")
    public Result<Void> transfer(@PathVariable("id") Long id, @RequestBody TransferReq req) {
        deliveryService.transfer(id, req.getToStatus(), req.getDriverId(), req.getDriverName(), req.getRemark());
        return Result.ok();
    }

    /** 指派司机（PENDING_ASSIGN -> ASSIGNED） */
    @PostMapping("/{id}/assign")
    public Result<Void> assign(@PathVariable("id") Long id, @RequestBody TransferReq req) {
        deliveryService.transfer(id, DeliveryStatusEnum.ASSIGNED.getCode(),
                req.getDriverId(), req.getDriverName(), "指派司机");
        return Result.ok();
    }

    /** 触发路线规划 */
    @PostMapping("/route/plan")
    public Result<Map<String, Object>> planRoute() {
        return Result.ok(deliveryService.planRoute());
    }

    /** 上报配送异常 */
    @PostMapping("/{id}/exception")
    public Result<Long> reportException(@PathVariable("id") Long id, @RequestBody ExceptionReq req) {
        Long eid = exceptionService.reportException(id, req.getExceptionType(), req.getRemark());
        return Result.ok(eid);
    }

    /** 发起补发（本地消息表 + MQ 异步扣库存，最终一致） */
    @PostMapping("/{id}/reship")
    public Result<Void> reship(@PathVariable("id") Long id) {
        reshipService.reship(id);
        return Result.ok();
    }

    /**
     * 发起补发（Seata AT 模式，同步 Feign 扣库存，强一致）。
     * 与 /reship 并存，便于对比两种一致性方案。
     */
    @PostMapping("/{id}/reship-seata")
    public Result<Void> reshipSeata(@PathVariable("id") Long id) {
        reshipService.reshipWithSeata(id);
        return Result.ok();
    }

    /** 查询状态机全量转移表（自检 / 面试演示用） */
    @GetMapping("/status/table")
    public Result<List<Map<String, Object>>> statusTable() {
        List<Map<String, Object>> table = Arrays.stream(DeliveryStatusEnum.values())
                .map(s -> Map.of(
                        "code", (Object) s.getCode(),
                        "name", s.name(),
                        "desc", s.getDesc(),
                        "next", s.nextStates().stream()
                                .map(n -> n.getCode() + ":" + n.getDesc())
                                .collect(Collectors.toList())))
                .collect(Collectors.toList());
        return Result.ok(table);
    }

    @Data
    public static class TransferReq {
        private Integer toStatus;
        private Long driverId;
        private String driverName;
        private String remark;
    }

    @Data
    public static class ExceptionReq {
        private Integer exceptionType;
        private String remark;
    }

    private Long toLong(Object o) {
        return o == null ? null : Long.valueOf(o.toString());
    }

    private Integer toInt(Object o) {
        return o == null ? null : Integer.valueOf(o.toString());
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
