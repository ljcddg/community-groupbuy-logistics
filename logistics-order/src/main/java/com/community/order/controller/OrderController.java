package com.community.order.controller;

import com.community.common.result.Result;
import com.community.order.service.OrderCutoffService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 订单服务对外接口。
 */
@Slf4j
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderCutoffService orderCutoffService;

    /** 手动下单（演示用） */
    @PostMapping("/create")
    public Result<Long> create(@RequestBody CreateOrderReq req) {
        Long id = orderCutoffService.createOrder(
                req.getUserId() == null ? 1001L : req.getUserId(),
                req.getPickupId(), req.getPickupName(),
                req.getItemCount(), req.getWeightGram());
        return Result.ok(id);
    }

    /** 触发截单：生成配送单 + 写本地消息表 */
    @PostMapping("/cutoff")
    public Result<Integer> cutoff() {
        int n = orderCutoffService.cutoff();
        return Result.ok(n);
    }

    /** 查询订单列表 */
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(orderCutoffService.listAllAsMap());
    }

    @Data
    public static class CreateOrderReq {
        private Long userId;
        private Long pickupId;
        private String pickupName;
        private Integer itemCount;
        private Integer weightGram;
    }
}
