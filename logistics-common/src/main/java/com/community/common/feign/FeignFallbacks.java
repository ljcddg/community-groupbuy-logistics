package com.community.common.feign;

import com.community.common.result.Result;
import com.community.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;
import java.util.Map;

/**
 * Feign 降级工厂集合。
 *
 * <p><b>面试点</b>：
 * <ul>
 *   <li><b>FallbackFactory vs Fallback</b>：Factory 能拿到异常原因（Throwable），
 *       便于日志定位是超时还是 5xx，因此生产推荐 Factory。</li>
 *   <li><b>降级要返回"合理默认值"而非一律抛异常</b>：
 *       查询类接口降级返回空/默认值，保证主流程不被阻断；
 *       写类接口降级必须返回失败，让上层走补偿。</li>
 *   <li><b>熔断触发条件</b>：Sentinel 按「慢调用比例 / 异常比例 / 异常数」触发，
 *       降级方法在熔断打开期间被调用，避免请求继续打满下游。</li>
 * </ul>
 *
 * <p><b>踩坑记录（真实根因）</b>：本类最初写成 {@code interface}，其成员类虽然
 * 在 Java 语法上是隐式 {@code public static}，但启动时 order / delivery 服务报：
 * <pre>
 * java.lang.IllegalStateException:
 *   No fallbackFactory instance of type
 *   class com.community.common.feign.FeignFallbacks$DeliveryClientFallbackFactory
 *   found for feign client deliveryClient
 *     at com.alibaba.cloud.sentinel.feign.SentinelFeign$Builder$1.getFromContext
 * </pre>
 * <p><b>根因不是 interface</b>（虽然同时改成了 {@code final class} 更规范），而是：
 * Spring Cloud Alibaba 的 Sentinel Feign 集成在创建代理时会走
 * {@code applicationContext.getBean(beanName)} <b>从 Spring 容器按 Bean 名取
 * fallbackFactory</b>，而原生 OpenFeign 是反射 {@code newInstance()}。
 * 因此 <b>配了 fallbackFactory 的 Feign 客户端，其工厂必须注册成 Spring Bean</b>
 * —— 见 {@link FeignFallbackConfig}。
 *
 * <p>这也解释了为什么 warehouse / pickup 服务能正常启动：它们的 Feign 客户端
 * 没有配 {@code fallbackFactory}，不触发这条查找逻辑。
 */
public final class FeignFallbacks {

    private FeignFallbacks() {
    }

    @Slf4j
    public static class PickupClientFallbackFactory implements FallbackFactory<FeignApi.PickupClient> {
        @Override
        public FeignApi.PickupClient create(Throwable cause) {
            return new FeignApi.PickupClient() {
                @Override
                public Result<Map<String, Object>> getById(Long id) {
                    log.warn("[降级] 自提点查询失败 id={} cause={}", id, msg(cause));
                    return Result.fail(ResultCode.PICKUP_NOT_FOUND);
                }

                @Override
                public Result<List<Map<String, Object>>> adjacent(Long id, Integer limit) {
                    log.warn("[降级] 相邻自提点查询失败 id={} cause={}", id, msg(cause));
                    return Result.ok(List.of());
                }

                @Override
                public Result<Integer> remainCapacity(Long id) {
                    // 降级策略：容量查不到按"充足"放行，不阻断建单主流程
                    log.warn("[降级] 容量查询失败 id={}，按容量充足处理 cause={}", id, msg(cause));
                    return Result.ok(Integer.MAX_VALUE);
                }
            };
        }
    }

    @Slf4j
    public static class WarehouseClientFallbackFactory implements FallbackFactory<FeignApi.WarehouseClient> {
        @Override
        public FeignApi.WarehouseClient create(Throwable cause) {
            return new FeignApi.WarehouseClient() {
                @Override
                public Result<Boolean> deduct(Integer skuId, Integer quantity, String bizNo) {
                    log.error("[降级] 库存扣减失败 skuId={} qty={} bizNo={} cause={}",
                            skuId, quantity, bizNo, msg(cause));
                    return Result.fail(ResultCode.STOCK_DEDUCT_FAIL);
                }

                @Override
                public Result<Boolean> rollback(Integer skuId, Integer quantity, String bizNo) {
                    log.error("[降级] 库存回滚失败 skuId={} bizNo={} cause={}", skuId, bizNo, msg(cause));
                    return Result.fail(ResultCode.STOCK_DEDUCT_FAIL);
                }

                @Override
                public Result<Integer> query(Integer skuId) {
                    log.warn("[降级] 库存查询失败 skuId={} cause={}", skuId, msg(cause));
                    return Result.ok(0);
                }
            };
        }
    }

    @Slf4j
    public static class DeliveryClientFallbackFactory implements FallbackFactory<FeignApi.DeliveryClient> {
        @Override
        public FeignApi.DeliveryClient create(Throwable cause) {
            return new FeignApi.DeliveryClient() {
                @Override
                public Result<Long> create(Map<String, Object> req) {
                    log.error("[降级] 创建配送单失败 req={} cause={}", req, msg(cause));
                    return Result.fail(ResultCode.FAIL.getCode(), "创建配送单失败（降级）");
                }

                @Override
                public Result<Map<String, Object>> getById(Long id) {
                    log.warn("[降级] 查询配送单失败 id={} cause={}", id, msg(cause));
                    return Result.fail(ResultCode.DELIVERY_NOT_FOUND);
                }
            };
        }
    }

    static String msg(Throwable t) {
        return t == null ? "unknown" : t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
