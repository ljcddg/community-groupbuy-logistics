package com.community.gateway.filter;

import com.community.common.constant.CommonConstants;
import com.community.common.result.Result;
import com.community.common.result.ResultCode;
import com.community.common.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 全局 JWT 鉴权过滤器。
 *
 * <p><b>面试点（务必讲清）</b>：
 * <ol>
 *   <li><b>为什么鉴权放网关？</b> 一处校验、全站生效，避免每个服务重复写拦截器；
 *       且网关能统一处理白名单、限流、日志。</li>
 *   <li><b>校验后怎么传用户信息？</b> 解析出的 userId/userName 放进请求头
 *       （{@code X-User-Id}），下游服务直接用，<b>不需要再解析 Token</b>。
 *       注意：这些头必须由网关<b>覆写</b>（先删掉客户端伪造的），否则存在越权风险。</li>
 *   <li><b>为什么用 GlobalFilter 而不是 GatewayFilter？</b> 全局过滤器对所有路由生效，
 *       无需在每条路由上配置。</li>
 *   <li><b>响应式写法</b>：网关基于 WebFlux，返回 {@code Mono<Void>}，
 *       不能阻塞（禁止在过滤器里做同步 DB/远程调用）。</li>
 * </ol>
 */
@Slf4j
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 白名单：不需要登录即可访问 */
    @Value("${gateway.auth.whitelist:/auth/**,/actuator/**}")
    private List<String> whitelist;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 白名单直接放行
        if (isWhitelisted(path)) {
            log.debug("白名单放行 path={}", path);
            return chain.filter(exchange);
        }

        String token = request.getHeaders().getFirst(CommonConstants.HEADER_AUTHORIZATION);
        Claims claims = JwtUtil.parse(token);
        if (claims == null) {
            log.warn("鉴权失败：Token 缺失或无效 path={}", path);
            return unauthorized(exchange, ResultCode.UNAUTHORIZED);
        }

        Long userId = Long.valueOf(String.valueOf(claims.get(JwtUtil.CLAIM_USER_ID)));
        String userName = String.valueOf(claims.get(JwtUtil.CLAIM_USER_NAME));

        // 关键：先移除客户端可能伪造的同名头，再写入可信值
        ServerHttpRequest mutated = request.mutate()
                .headers(h -> {
                    h.remove(CommonConstants.HEADER_USER_ID);
                    h.remove(CommonConstants.HEADER_USER_NAME);
                })
                .header(CommonConstants.HEADER_USER_ID, String.valueOf(userId))
                .header(CommonConstants.HEADER_USER_NAME, userName)
                .build();

        log.debug("鉴权通过 userId={} path={}", userId, path);
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isWhitelisted(String path) {
        if (whitelist == null) {
            return false;
        }
        return whitelist.stream().anyMatch(p -> MATCHER.match(p.trim(), path));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, ResultCode rc) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);   // 业务码表达鉴权失败，HTTP 仍 200（按团队约定可改 401）
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = objectMapper.writeValueAsString(Result.fail(rc)).getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        // 优先级高于路由转发，且尽早执行
        return -100;
    }
}
