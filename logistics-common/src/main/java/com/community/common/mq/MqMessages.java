package com.community.common.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;
import java.util.List;

/**
 * MQ 消息体集合。统一带 bizId + msgId 便于幂等去重。
 *
 * <p>注意：继承场景下用 {@code @SuperBuilder} 而非 {@code @Builder}，
 * 否则子类 builder 拿不到父类字段（这是 Lombok 的经典坑）。
 */
public interface MqMessages {

    /** 所有消息的公共字段 */
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    class BaseMsg implements Serializable {
        /** 业务唯一 ID（用于消费端幂等） */
        private String msgId;
        private Long timestamp;
    }

    /**
     * 订单截单事件 —— 订单服务在截单后发出，物流服务消费后按自提点聚合生成配送单。
     * 面试点：截单是"批次概念"，一个截单批次内同一自提点的所有订单聚合成一张配送单。
     */
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    class OrderCutoffMsg extends BaseMsg {
        /** 截单批次号，如 20260301-A */
        private String batchNo;
        /** 供聚合的订单快照 */
        private List<OrderItem> orders;
    }

    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    class OrderItem implements Serializable {
        private Long orderId;
        private Long pickupId;
        private String pickupName;
        private Integer itemCount;
        private Integer weightGram;
    }

    /**
     * 配送单状态变更事件 —— 状态机流转成功后发出，驱动轨迹更新与下游通知。
     */
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    class DeliveryStatusMsg extends BaseMsg {
        private Long deliveryId;
        private String deliveryNo;
        private Long pickupId;
        private Integer fromStatus;
        private Integer toStatus;
        private String operator;
    }

    /**
     * 配送异常事件 —— 标记异常后发出，同时发一条延迟消息用于超时升级。
     */
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    class DeliveryExceptionMsg extends BaseMsg {
        private Long deliveryId;
        private String deliveryNo;
        private Long pickupId;
        private Integer exceptionType;
        /** 是否补发 */
        private Boolean needReship;
        private Integer reshipCount;
        private String remark;
        /** 升级轮次，防止无限升级 */
        private Integer escalateRound;
    }

    /**
     * 补发扣库存事件 —— 物流服务发出，仓储服务消费后扣减库存。
     * 面试点（替代 Seata 的方案）：本地消息表 + 消费幂等 + 失败重试保证最终一致。
     */
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    class ReshipDeductMsg extends BaseMsg {
        private Long reshipId;
        private Long deliveryId;
        private Long pickupId;
        private Integer skuId;
        private Integer quantity;
    }
}
