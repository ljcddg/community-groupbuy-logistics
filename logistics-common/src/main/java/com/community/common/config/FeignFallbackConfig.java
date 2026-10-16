package com.community.common.config;

import com.community.common.feign.FeignFallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 降级工厂的 Bean 注册。
 *
 * <h3>为什么必须显式注册成 Bean（踩坑记录）</h3>
 * <p>原生 Spring Cloud OpenFeign 在创建代理时，会用反射直接
 * {@code newInstance()} 实例化 {@code fallbackFactory} 指定的类。
 * <b>但本项目用了 Spring Cloud Alibaba 的 Sentinel 集成，
 * 行为不一样</b>：{@code com.alibaba.cloud.sentinel.feign.SentinelFeign$Builder}
 * 会调用 {@code applicationContext.getBeanDefinition(beanName)} /
 * {@code applicationContext.getBean(beanName)} 按 <b>Bean 名称</b>从容器里取，
 * 取不到就抛：
 * <pre>
 * java.lang.IllegalStateException:
 *   No fallbackFactory instance of type
 *   class com.community.common.feign.FeignFallbacks$DeliveryClientFallbackFactory
 *   found for feign client deliveryClient
 * </pre>
 *
 * <p>换句话说：<b>用 Sentinel 的 Feign 集成时，fallbackFactory 必须是一个 Spring Bean。</b>
 * 于是这里把三个工厂显式注册进容器。Bean 名称沿用 Sentinel 查找时使用的约定
 * （即 {@code fallbackFactoryClass.getSimpleName()} 首字母小写的形态，
 * 与 OpenFeign 的默认命名保持一致）。
 *
 * <p><b>面试点</b>：这类"框架版本差异导致的隐式约定变化"是排查线上启动失败的高频坑。
 * 定位手段是看异常堆栈里<b>真正抛错的类</b>（本例是 SentinelFeign 而不是 OpenFeign），
 * 再反查它取 Bean 的方式，而不是想当然认为"原生怎么用这里就怎么用"。
 */
@Configuration
public class FeignFallbackConfig {

    /**
     * 自提点服务降级工厂。
     *
     * <p>Bean 名 {@code pickupClientFallbackFactory} <b>必须精确匹配</b>：
     * {@code SentinelFeign$Builder$1.getFromContext} 用
     * {@code ClassUtils.getShortNameAsProperty(fallbackFactoryClass)} 生成
     * Bean 名（即简单类名首字母小写）后从容器查找，名字不对就仍会报
     * "No fallbackFactory instance ... found"。
     * 这里显式写 name 属性而不是依赖方法名推断，避免重构时意外改名踩坑。
     */
    @Bean("pickupClientFallbackFactory")
    public FeignFallbacks.PickupClientFallbackFactory pickupClientFallbackFactory() {
        return new FeignFallbacks.PickupClientFallbackFactory();
    }

    /** 仓储服务降级工厂，Bean 名 {@code warehouseClientFallbackFactory} */
    @Bean("warehouseClientFallbackFactory")
    public FeignFallbacks.WarehouseClientFallbackFactory warehouseClientFallbackFactory() {
        return new FeignFallbacks.WarehouseClientFallbackFactory();
    }

    /** 物流服务降级工厂，Bean 名 {@code deliveryClientFallbackFactory} */
    @Bean("deliveryClientFallbackFactory")
    public FeignFallbacks.DeliveryClientFallbackFactory deliveryClientFallbackFactory() {
        return new FeignFallbacks.DeliveryClientFallbackFactory();
    }
}
