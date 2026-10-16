package com.community.common.constant;

/**
 * 全局常量。
 */
public interface CommonConstants {

    /** 网关 / 服务间透传的用户信息请求头 */
    String HEADER_USER_ID = "X-User-Id";
    String HEADER_USER_NAME = "X-User-Name";
    String HEADER_AUTHORIZATION = "Authorization";
    String TOKEN_PREFIX = "Bearer ";

    /** 网关鉴权放行的白名单（网关 application.yml 里配置，此处仅作注释参考） */

    /** 物流轨迹 Redis Key 前缀 */
    String REDIS_TRACE_PREFIX = "delivery:trace:";
    /** 接口幂等 Redis Key 前缀 */
    String REDIS_IDEMPOTENT_PREFIX = "idempotent:";
    /** 配送单分布式锁前缀 */
    String LOCK_DELIVERY_PREFIX = "lock:delivery:";
}
