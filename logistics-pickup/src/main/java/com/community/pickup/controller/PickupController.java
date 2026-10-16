package com.community.pickup.controller;

import com.community.common.result.Result;
import com.community.pickup.entity.Pickup;
import com.community.pickup.service.PickupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 自提点服务对外接口。
 */
@RestController
@RequestMapping("/pickup")
@RequiredArgsConstructor
public class PickupController {

    private final PickupService pickupService;

    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable("id") Long id) {
        return Result.ok(pickupService.toMap(pickupService.getById(id)));
    }

    /** 相邻自提点（Neo4j 图查询） */
    @GetMapping("/{id}/adjacent")
    public Result<List<Map<String, Object>>> adjacent(@PathVariable("id") Long id,
                                                      @RequestParam(value = "limit", defaultValue = "3") Integer limit) {
        return Result.ok(pickupService.adjacent(id, limit));
    }

    /** 剩余容量 */
    @GetMapping("/{id}/capacity")
    public Result<Integer> remainCapacity(@PathVariable("id") Long id) {
        return Result.ok(pickupService.remainCapacity(id));
    }

    @GetMapping("/list")
    public Result<List<Pickup>> list() {
        return Result.ok(pickupService.listAll());
    }

    @PostMapping("/occupy")
    public Result<Boolean> occupy(@RequestParam("id") Long id, @RequestParam("delta") Integer delta) {
        return Result.ok(pickupService.occupy(id, delta));
    }

    /** 手动触发 MySQL -> Neo4j 同步 */
    @PostMapping("/sync")
    public Result<Integer> sync() {
        return Result.ok(pickupService.manualSync());
    }
}
