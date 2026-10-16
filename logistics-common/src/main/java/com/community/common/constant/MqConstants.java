package com.community.common.constant;

/**
 * RocketMQ Topic / Tag / 消费者组常量，统一收口，避免散落各处写死字符串。
 */
public interface MqConstants {

    // ============ Topic ============
    /** 订单截单事件：订单服务 -> 物流服务 */
    String TOPIC_ORDER_CUTOFF = "TOPIC_ORDER_CUTOFF";
    /** 配送单状态变更：物流服务 -> 通知下游（轨迹/推送） */
    String TOPIC_DELIVERY_STATUS = "TOPIC_DELIVERY_STATUS";
    /** 配送异常事件 */
    String TOPIC_DELIVERY_EXCEPTION = "TOPIC_DELIVERY_EXCEPTION";
    /** 补发事件：物流服务 -> 仓储服务（扣库存） */
    String TOPIC_RESHIP = "TOPIC_RESHIP";

    // ============ Tag ============
    String TAG_CUTOFF = "CUTOFF";
    String TAG_STATUS_CHANGED = "STATUS_CHANGED";
    String TAG_EXCEPTION_CREATED = "EXCEPTION_CREATED";
    String TAG_EXCEPTION_ESCALATE = "EXCEPTION_ESCALATE";
    String TAG_RESHIP_DEDUCT = "RESHIP_DEDUCT";
    String TAG_RESHIP_DEDUCT_RESULT = "RESHIP_DEDUCT_RESULT";

    // ============ ConsumerGroup ============
    String GROUP_ORDER = "GID_ORDER";
    String GROUP_DELIVERY = "GID_DELIVERY";
    String GROUP_DELIVERY_TRACE = "GID_DELIVERY_TRACE";
    String GROUP_WAREHOUSE = "GID_WAREHOUSE";

    /** 异常单超时升级延迟级别（RocketMQ 默认 1s 5s 10s 30s 1m... 这里用 level 5 = 1 分钟，演示用） */
    int DELAY_LEVEL_1M = 5;
    /** 配送单异常后 30s 未处理则升级（演示用，可改大） */
    int DELAY_LEVEL_30S = 4;
}
