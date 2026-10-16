package com.community.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单服务启动类。
 * 职责：接收下单/截单请求，截单后按自提点聚合订单 -> 调用物流服务生成配送单。
 */
@SpringBootApplication(scanBasePackages = {"com.community.order", "com.community.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.community.common.feign")
@EnableScheduling
@MapperScan("com.community.order.mapper")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
