package com.community.delivery.service;

import com.community.common.constant.MqConstants;
import com.community.common.enums.DeliveryStatusEnum;
import com.community.common.enums.ExceptionTypeEnum;
import com.community.common.exception.BizException;
import com.community.common.mq.MqMessages;
import com.community.common.result.ResultCode;
import com.community.delivery.chain.ExceptionContext;
import com.community.delivery.chain.ExceptionHandlerChain;
import com.community.delivery.entity.Delivery;
import com.community.delivery.entity.DeliveryException;
import com.community.delivery.mapper.DeliveryExceptionMapper;
import com.community.delivery.mapper.DeliveryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 配送异常处理。
 *
 * <p><b>面试点串联</b>：
 * <ol>
 *   <li><b>责任链匹配方案</b>：{@link ExceptionHandlerChain#dispatch} 按类型算出处理方案（赔付/补发/退回）</li>
 *   <li><b>分布式锁防重复</b>：同一配送单的异常只处理一次（司机可能重复上报）</li>
 *   <li><b>延迟消息升级</b>：异常单发出后同时在 MQ 投一条延迟消息，
 *       若 N 分钟后仍未处理（status 仍=待处理），则升级给主管。用 MQ 延迟消息而不是定时扫表，
 *       原因是延迟消息精度更高、不用轮询全表，且天然分布式。</li>
 *   <li><b>补发跨服务</b>：补发需"物流建单 + 仓储扣库存"。本 Demo 不上 Seata，
 *       走"本地事务 + MQ 异步扣库存 + 消费幂等"，保证最终一致（见 ReshipService）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryExceptionService {

    private final DeliveryMapper deliveryMapper;
    private final DeliveryExceptionMapper exceptionMapper;
    private final ExceptionHandlerChain chain;
    private final DeliveryStateMachineService stateMachine;
    private final RedissonClient redissonClient;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String LOCK_EXCEPTION = "lock:delivery:exception:";

    /**
     * 上报异常：责任链算方案 -> 落异常单 -> 配送单转异常态 -> 发延迟升级消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long reportException(Long deliveryId, Integer exceptionTypeCode, String remark) {
        RLock lock = redissonClient.getLock(LOCK_EXCEPTION + deliveryId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 30, java.util.concurrent.TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ResultCode.DELIVERY_CONCURRENT_MODIFY);
            }

            Delivery d = deliveryMapper.selectById(deliveryId);
            if (d == null) {
                throw new BizException(ResultCode.DELIVERY_NOT_FOUND);
            }
            ExceptionTypeEnum type = ExceptionTypeEnum.of(exceptionTypeCode);
            if (type == null) {
                throw new BizException(ResultCode.PARAM_ERROR, "未知异常类型: " + exceptionTypeCode);
            }

            // 1) 责任链算出处理方案
            ExceptionContext ctx = ExceptionContext.builder()
                    .deliveryId(deliveryId)
                    .deliveryNo(d.getDeliveryNo())
                    .pickupId(d.getPickupId())
                    .type(type)
                    .itemCount(d.getItemCount())
                    .amountCent(0L)
                    .remark(remark)
                    .build();
            Map<String, Object> plan = chain.dispatch(ctx);

            boolean needReship = Boolean.TRUE.equals(plan.get("needReship"));
            int reshipCount = plan.get("reshipCount") instanceof Number n ? n.intValue() : 0;

            // 2) 落异常单
            DeliveryException de = new DeliveryException();
            de.setDeliveryId(deliveryId);
            de.setDeliveryNo(d.getDeliveryNo());
            de.setPickupId(d.getPickupId());
            de.setExceptionType(exceptionTypeCode);
            de.setHandlePlan(toJson(plan));
            de.setStatus(0);
            de.setNeedReship(needReship);
            de.setReshipCount(reshipCount);
            de.setEscalateRound(0);
            de.setRemark(remark);
            de.setCreateTime(LocalDateTime.now());
            de.setUpdateTime(LocalDateTime.now());
            exceptionMapper.insert(de);

            // 3) 配送单转异常态（走状态机，复用合法性校验与轨迹）
            stateMachine.transfer(deliveryId, DeliveryStatusEnum.EXCEPTION, "driver", "异常上报：" + type.getDesc());

            // 4) 发延迟消息用于超时升级
            sendEscalateDelayMessage(de);

            log.info("异常上报完成 deliveryNo={} type={} needReship={} reshipCount={}",
                    d.getDeliveryNo(), type.getDesc(), needReship, reshipCount);
            return de.getId();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("获取异常锁被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 发送延迟消息：30 秒后检查异常单是否已处理，未处理则升级。
     * RocketMQ 延迟级别：1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
     */
    public void sendEscalateDelayMessage(DeliveryException de) {
        try {
            MqMessages.DeliveryExceptionMsg msg = MqMessages.DeliveryExceptionMsg.builder()
                    .msgId(UUID.randomUUID().toString().replace("-", ""))
                    .timestamp(System.currentTimeMillis())
                    .deliveryId(de.getDeliveryId())
                    .deliveryNo(de.getDeliveryNo())
                    .pickupId(de.getPickupId())
                    .exceptionType(de.getExceptionType())
                    .needReship(de.getNeedReship())
                    .reshipCount(de.getReshipCount())
                    .escalateRound(de.getEscalateRound() == null ? 0 : de.getEscalateRound())
                    .remark("escalate-check")
                    .build();

            org.springframework.messaging.Message<String> message =
                    MessageBuilder.withPayload(toJson(msg)).setHeader("KEYS", msg.getMsgId()).build();
            String dest = MqConstants.TOPIC_DELIVERY_EXCEPTION + ":" + MqConstants.TAG_EXCEPTION_ESCALATE;

            // delayLevel = 4 (30s) —— 演示用；生产可设 5 (1m) 或更长
            rocketMQTemplate.syncSend(dest, message, 3000, MqConstants.DELAY_LEVEL_30S);
            log.info("异常升级延迟消息已投递 deliveryNo={} delayLevel={}", de.getDeliveryNo(), MqConstants.DELAY_LEVEL_30S);
        } catch (Exception e) {
            log.error("延迟升级消息投递失败 deliveryNo={}", de.getDeliveryNo(), e);
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException("序列化失败: " + e.getMessage());
        }
    }
}
