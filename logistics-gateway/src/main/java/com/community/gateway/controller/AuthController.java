package com.community.gateway.controller;

import com.community.common.result.Result;
import com.community.common.util.JwtUtil;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 演示用的登录接口（网关本地实现，不转发）。
 * 生产应由独立的认证服务（或对接 SSO）负责。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    /** 演示账号：任意用户名，密码固定 123456 */
    @PostMapping("/login")
    public Mono<Result<Map<String, Object>>> login(@RequestBody LoginReq req) {
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            return Mono.just(Result.fail("用户名不能为空"));
        }
        if (!"123456".equals(req.getPassword())) {
            return Mono.just(Result.fail("用户名或密码错误（演示密码：123456）"));
        }
        Long userId = 1001L;
        String token = JwtUtil.generate(userId, req.getUsername());
        return Mono.just(Result.ok(Map.of(
                "token", token,
                "userId", userId,
                "username", req.getUsername(),
                "expiresIn", 86400
        )));
    }

    @Data
    public static class LoginReq {
        private String username;
        private String password;
    }
}
