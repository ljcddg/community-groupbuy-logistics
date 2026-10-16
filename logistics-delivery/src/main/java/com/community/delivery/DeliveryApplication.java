package com.community.delivery;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 物流服务启动类。
 * 核心：配送单状态机、Neo4j 路线规划、RocketMQ 轨迹通知、责任链异常处理、分布式锁。
 */
@SpringBootApplication(scanBasePackages = {"com.community.delivery", "com.community.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.community.common.feign")
@EnableScheduling
@MapperScan("com.community.delivery.mapper")
public class DeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryApplication.class, args);
    }
}
