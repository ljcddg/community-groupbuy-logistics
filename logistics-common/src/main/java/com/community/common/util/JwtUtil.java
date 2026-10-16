package com.community.common.util;

import com.community.common.constant.CommonConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具。
 * 面试点：HS256 对称签名；Gateway 只做校验与透传（解析出的 userId 放到请求头），
 * 下游服务不再重复解析 Token；过期时间与时钟偏移容错。
 */
@Slf4j
public class JwtUtil {

    /** 演示用密钥，生产应放 Nacos 配置中心并定期轮换 */
    private static final String SECRET = "community-groupbuy-logistics-jwt-secret-key-2026-demo-only";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_USER_NAME = "userName";

    public static String generate(Long userId, String userName) {
        return generate(userId, userName, 24 * 60 * 60 * 1000L);
    }

    public static String generate(Long userId, String userName, long ttlMillis) {
        Date now = new Date();
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_USER_ID, userId);
        claims.put(CLAIM_USER_NAME, userName);
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(KEY)
                .compact();
    }

    /** 解析失败返回 null，不抛异常，交由调用方判断 */
    public static Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.startsWith(CommonConstants.TOKEN_PREFIX)
                ? token.substring(CommonConstants.TOKEN_PREFIX.length())
                : token;
        try {
            return Jwts.parser().verifyWith(KEY).build().parseSignedClaims(t).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public static Long getUserId(String token) {
        Claims c = parse(token);
        return c == null ? null : Long.valueOf(c.get(CLAIM_USER_ID).toString());
    }

    public static String getUserName(String token) {
        Claims c = parse(token);
        return c == null ? null : String.valueOf(c.get(CLAIM_USER_NAME));
    }
}
