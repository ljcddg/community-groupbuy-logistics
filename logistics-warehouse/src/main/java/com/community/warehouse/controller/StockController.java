package com.community.warehouse.controller;

import com.community.common.result.Result;
import com.community.warehouse.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仓储服务对外接口。
 */
@RestController
@RequestMapping("/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    @PostMapping("/deduct")
    public Result<Boolean> deduct(@RequestParam("skuId") Integer skuId,
                                  @RequestParam("quantity") Integer quantity,
                                  @RequestParam("bizNo") String bizNo) {
        return Result.ok(stockService.deduct(skuId, quantity, bizNo));
    }

    @PostMapping("/rollback")
    public Result<Boolean> rollback(@RequestParam("skuId") Integer skuId,
                                    @RequestParam("quantity") Integer quantity,
                                    @RequestParam("bizNo") String bizNo) {
        return Result.ok(stockService.rollback(skuId, quantity, bizNo));
    }

    @GetMapping("/query")
    public Result<Integer> query(@RequestParam("skuId") Integer skuId) {
        return Result.ok(stockService.query(skuId));
    }
}
