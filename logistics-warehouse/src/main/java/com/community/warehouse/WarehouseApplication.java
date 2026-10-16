package com.community.warehouse;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 仓储服务启动类。
 */
@SpringBootApplication(scanBasePackages = {"com.community.warehouse", "com.community.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.community.common.feign")
@MapperScan("com.community.warehouse.mapper")
public class WarehouseApplication {

    public static void main(String[] args) {
        SpringApplication.run(WarehouseApplication.class, args);
    }
}
