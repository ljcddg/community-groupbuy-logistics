package com.community.common.feign;

import com.community.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 服务间 Feign 契约集中定义。
 *
 * <p><b>面试点</b>：
 * <ul>
 *   <li><b>契约下沉</b>：接口定义放 common，调用方只依赖接口不依赖实现，避免各服务重复声明。</li>
 *   <li><b>contextId 必须指定</b>：多个 {@code @FeignClient} 的 {@code name} 不同时其实可省，
 *       但同 name 多接口时必需，否则 Bean 名冲突。养成显式写 contextId 的习惯。</li>
 *   <li><b>fallbackFactory</b>：这里指向 common 里的通用降级工厂，
 *       各服务如需定制降级逻辑，可自行用配置类覆盖对应 Bean。</li>
 *   <li><b>超时</b>：Feign 默认读超时较长，务必在 yml 显式配 connectTimeout/readTimeout，
 *       否则下游卡住会拖垮自己（并配合 Sentinel 熔断）。</li>
 * </ul>
 */
public interface FeignApi {

    /** 自提点服务 */
    @FeignClient(name = "logistics-pickup", path = "/pickup", contextId = "pickupClient",
            fallbackFactory = FeignFallbacks.PickupClientFallbackFactory.class)
    interface PickupClient {

        @GetMapping("/{id}")
        Result<Map<String, Object>> getById(@PathVariable("id") Long id);

        /** 查询相邻自提点（Neo4j 图查询，用于超容就近拆分） */
        @GetMapping("/{id}/adjacent")
        Result<List<Map<String, Object>>> adjacent(@PathVariable("id") Long id,
                                                   @RequestParam(value = "limit", defaultValue = "3") Integer limit);

        /** 剩余容量 */
        @GetMapping("/{id}/capacity")
        Result<Integer> remainCapacity(@PathVariable("id") Long id);
    }

    /** 仓储服务 */
    @FeignClient(name = "logistics-warehouse", path = "/stock", contextId = "warehouseClient",
            fallbackFactory = FeignFallbacks.WarehouseClientFallbackFactory.class)
    interface WarehouseClient {

        @PostMapping("/deduct")
        Result<Boolean> deduct(@RequestParam("skuId") Integer skuId,
                               @RequestParam("quantity") Integer quantity,
                               @RequestParam("bizNo") String bizNo);

        @PostMapping("/rollback")
        Result<Boolean> rollback(@RequestParam("skuId") Integer skuId,
                                 @RequestParam("quantity") Integer quantity,
                                 @RequestParam("bizNo") String bizNo);

        @GetMapping("/query")
        Result<Integer> query(@RequestParam("skuId") Integer skuId);
    }

    /** 物流服务（供订单服务创建配送单） */
    @FeignClient(name = "logistics-delivery", path = "/delivery", contextId = "deliveryClient",
            fallbackFactory = FeignFallbacks.DeliveryClientFallbackFactory.class)
    interface DeliveryClient {

        @PostMapping("/create")
        Result<Long> create(@RequestBody Map<String, Object> req);

        @GetMapping("/{id}")
        Result<Map<String, Object>> getById(@PathVariable("id") Long id);
    }
}
