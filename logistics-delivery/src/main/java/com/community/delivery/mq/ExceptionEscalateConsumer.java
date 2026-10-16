package com.community.delivery.mq;

import com.community.common.constant.MqConstants;
import com.community.common.mq.MqMessages;
import com.community.delivery.entity.DeliveryException;
import com.community.delivery.mapper.DeliveryExceptionMapper;
import com.community.delivery.service.IdempotentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 异常单超时升级消费者 —— 处理 <b>RocketMQ 延迟消息</b>。
 *
 * <p><b>面试点（延迟消息 + 超时升级）</b>：
 * <ol>
 *   <li><b>为什么用延迟消息而不是定时扫表？</b>
 *       扫表要轮询全表、有精度损失（5 分钟一轮就是 5 分钟误差）、且多实例要向分布式锁；
 *       延迟消息由 Broker 精确调度，到点才投递，无空转、精度到秒级。</li>
 *   <li><b>RocketMQ 延迟消息的限制</b>：开源版只支持 18 个固定延迟级别
 *       (1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h)，
 *       不支持任意时刻。RocketMQ 5.x 支持任意时间延迟（定时消息）。
 *       本 Demo 用固定级别 4（30s）演示。</li>
 *   <li><b>升级逻辑</b>：到点后查异常单状态 ——
 *       仍为"待处理"(0) 说明无人跟进，标记为已升级(3) 并通知主管；
 *       已处理则什么都不做（幂等）。</li>
 *   <li><b>幂等</b>：延迟消息可能重复投递，用 msgId 去重 + 状态 CAS 双重保证。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_DELIVERY_EXCEPTION,
        consumerGroup = MqConstants.GROUP_DELIVERY + "_ESCALATE",
        selectorExpression = MqConstants.TAG_EXCEPTION_ESCALATE
)
public class ExceptionEscalateConsumer implements RocketMQListener<String> {

    private final IdempotentService idempotentService;
    private final DeliveryExceptionMapper exceptionMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 最大升级轮次，防止无限升级 */
    private static final int MAX_ESCALATE_ROUND = 3;

    @Override
    public void onMessage(String body) {
        MqMessages.DeliveryExceptionMsg msg;
        try {
            msg = objectMapper.readValue(body, MqMessages.DeliveryExceptionMsg.class);
        } catch (Exception e) {
            log.error("升级消息反序列化失败 body={}", body, e);
            return;
        }

        if (!idempotentService.tryConsume(msg.getMsgId(),
                MqConstants.TOPIC_DELIVERY_EXCEPTION, MqConstants.GROUP_DELIVERY + "_ESCALATE")) {
            return;
        }

        DeliveryException de = exceptionMapper.selectLatestByDeliveryId(msg.getDeliveryId());
        if (de == null) {
            log.warn("升级检查：未找到异常单 deliveryId={}", msg.getDeliveryId());
            return;
        }

        // 已处理/已升级则跳过（幂等）
        if (de.getStatus() != null && de.getStatus() != 0) {
            log.info("异常单已处理(status={})，无需升级 exceptionId={}", de.getStatus(), de.getId());
            return;
        }

        int round = de.getEscalateRound() == null ? 0 : de.getEscalateRound();
        if (round >= MAX_ESCALATE_ROUND) {
            log.warn("异常单已达最大升级轮次 {}，转人工 exceptionId={}", MAX_ESCALATE_ROUND, de.getId());
            exceptionMapper.casStatus(de.getId(), 0, 3);
            return;
        }

        // CAS 升级：status 0 -> 3，并发安全
        int affected = exceptionMapper.casStatus(de.getId(), 0, 3);
        if (affected > 0) {
            log.warn("【异常升级】配送单 {} 异常超时未处理（第 {} 轮），已升级至主管，异常类型={}",
                    de.getDeliveryNo(), round + 1, de.getExceptionType());
            // 生产可在此发通知（企微/短信）+ 投递下一轮延迟消息
        } else {
            log.info("异常单状态已被并发修改，本次升级跳过 exceptionId={}", de.getId());
        }
    }
}
