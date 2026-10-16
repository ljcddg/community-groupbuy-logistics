package com.community.pickup;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 自提点服务启动类。
 * 职责：自提点容量管理；向 Neo4j 同步图数据；提供相邻自提点查询（供超容拆分）。
 */
@SpringBootApplication(scanBasePackages = {"com.community.pickup", "com.community.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.community.common.feign")
@EnableScheduling
@MapperScan("com.community.pickup.mapper")
public class PickupApplication {

    public static void main(String[] args) {
        SpringApplication.run(PickupApplication.class, args);
    }
}
