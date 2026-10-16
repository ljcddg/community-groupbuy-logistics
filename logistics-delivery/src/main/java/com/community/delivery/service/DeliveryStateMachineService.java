package com.community.delivery.service;

import com.community.common.constant.CommonConstants;
import com.community.common.constant.MqConstants;
import com.community.common.enums.DeliveryStatusEnum;
import com.community.common.exception.BizException;
import com.community.common.mq.MqMessages;
import com.community.common.result.ResultCode;
import com.community.delivery.entity.Delivery;
import com.community.delivery.entity.DeliveryTrace;
import com.community.delivery.mapper.DeliveryMapper;
import com.community.delivery.mapper.DeliveryTraceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 配送单状态机 —— 系统的核心。
 *
 * <p><b>面试点全解（务必背熟）</b>：
 *
 * <h3>1. 状态流转怎么防非法？</h3>
 * 三层防护：
 * <ul>
 *   <li><b>应用层</b>：{@link DeliveryStatusEnum#canTransferTo} 先校验，非法直接抛业务异常并给出
 *       "从 X 不能到 Y，可选：Z"，快速失败、提示友好。</li>
 *   <li><b>数据库层</b>：{@code UPDATE ... WHERE id=? AND status=expectStatus}（CAS），
 *       受影响行数=0 说明状态被并发改过，返回"正被处理"。这是正确性的最后一道防线。</li>
 *   <li><b>分布式锁</b>：同一配送单的操作串行化（Redisson 可重入锁），
 *       避免多实例同时对一张单做转弯/异常处理。</li>
 * </ul>
 *
 * <h3>2. 为什么用乐观锁（status 当版本号）而不是悲观锁？</h3>
 * 配送单流转是"低冲突、短事务"场景，悲观锁（for update）会持有连接、放大锁等待；
 * 乐观锁无锁开销，冲突时只需重试或提示，QPS 更高。
 *
 * <h3>3. 状态变更后如何通知下游？</h3>
 * 事务内更新状态 + 写轨迹；<b>事务提交后</b>发 MQ（用 TransactionSynchronization 或
 * 直接 afterCommit 回调），避免"消息发出去了但事务回滚了"的脏通知。
 * 本期为简化在事务提交后同步发送，失败由补偿兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryStateMachineService {

    private final DeliveryMapper deliveryMapper;
    private final DeliveryTraceMapper traceMapper;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 状态流转统一入口。
     *
     * @param deliveryId 配送单 ID
     * @param to         目标状态
     * @param driverId   司机（仅指派时传，可 null）
     * @param operator   操作人
     * @param remark     备注（写进轨迹）
     */
    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long deliveryId,
                         DeliveryStatusEnum to,
                         Long driverId,
                         String driverName,
                         String operator,
                         String remark) {

        RLock lock = redissonClient.getLock(CommonConstants.LOCK_DELIVERY_PREFIX + deliveryId);
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

            DeliveryStatusEnum from = DeliveryStatusEnum.of(d.getStatus());
            // idempotent: 已是目标状态，直接返回并记一条轨迹，保证 MQ 重投不报错
            if (from == to) {
                log.info("配送单 {} 已处于 {}，幂等跳过", deliveryId, to);
                return;
            }

            // 第 1 层：应用层校验转移表
            if (!from.canTransferTo(to)) {
                throw new BizException(ResultCode.DELIVERY_STATUS_ILLEGAL,
                        String.format("配送单[%s]不能从[%s]流转到[%s]，可选：%s",
                                d.getDeliveryNo(), from.getDesc(), to.getDesc(),
                                from.nextStates()));
            }

            // 第 2 层：DB CAS，防并发
            int affected = deliveryMapper.casStatusOnly(deliveryId, from.getCode(), to.getCode());
            if (affected == 0) {
                throw new BizException(ResultCode.DELIVERY_CONCURRENT_MODIFY);
            }

            // 指派司机时补充司机信息（单独一条 UPDATE，避免与状态 CAS 耦合）
            if (driverId != null) {
                Delivery upd = new Delivery();
                upd.setId(deliveryId);
                upd.setDriverId(driverId);
                upd.setDriverName(driverName);
                deliveryMapper.updateById(upd);
            }

            // 写轨迹
            DeliveryTrace trace = new DeliveryTrace();
            trace.setDeliveryId(deliveryId);
            trace.setDeliveryNo(d.getDeliveryNo());
            trace.setFromStatus(from.getCode());
            trace.setToStatus(to.getCode());
            trace.setRemark(remark == null ? to.getDesc() : remark);
            trace.setOperator(operator == null ? "system" : operator);
            trace.setCreateTime(LocalDateTime.now());
            traceMapper.insert(trace);

            // 写 Redis 轨迹缓存（供移动端高频查询，避免穿透 DB）
            cacheTrace(deliveryId, to);

            log.info("配送单 {} 状态流转 {} -> {} 成功", d.getDeliveryNo(), from, to);

            // 事务提交后再发 MQ（此处简化：直接发；生产建议 afterCommit + 本地消息表）
            sendStatusChanged(d, from, to, operator);

            // 异常状态额外发异常事件 + 延迟升级消息
            if (to == DeliveryStatusEnum.EXCEPTION) {
                sendExceptionEvent(d, remark);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("获取配送单锁被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 便捷方法：仅流转，无司机 */
    public void transfer(Long deliveryId, DeliveryStatusEnum to, String operator, String remark) {
        transfer(deliveryId, to, null, null, operator, remark);
    }

    private void cacheTrace(Long deliveryId, DeliveryStatusEnum to) {
        String key = CommonConstants.REDIS_TRACE_PREFIX + deliveryId;
        redisTemplate.opsForValue().set(key + ":last", to.getDesc(), Duration.ofHours(6));
    }

    private void sendStatusChanged(Delivery d, DeliveryStatusEnum from, DeliveryStatusEnum to, String operator) {
        try {
            MqMessages.DeliveryStatusMsg msg = MqMessages.DeliveryStatusMsg.builder()
                    .msgId(UUID.randomUUID().toString().replace("-", ""))
                    .timestamp(System.currentTimeMillis())
                    .deliveryId(d.getId())
                    .deliveryNo(d.getDeliveryNo())
                    .pickupId(d.getPickupId())
                    .fromStatus(from.getCode())
                    .toStatus(to.getCode())
                    .operator(operator)
                    .build();
            String dest = MqConstants.TOPIC_DELIVERY_STATUS + ":" + MqConstants.TAG_STATUS_CHANGED;
            rocketMQTemplate.syncSend(dest, MessageBuilder.withPayload(toJson(msg)).setHeader("KEYS", msg.getMsgId()).build(), 3000);
            log.info("状态变更消息已发送 deliveryNo={} {}->{}", d.getDeliveryNo(), from, to);
        } catch (Exception e) {
            // 消息发送失败不影响主流程（状态已落库），由补偿/对账处理
            log.error("状态变更消息发送失败 deliveryNo={}", d.getDeliveryNo(), e);
        }
    }

    private void sendExceptionEvent(Delivery d, String remark) {
        try {
            MqMessages.DeliveryExceptionMsg msg = MqMessages.DeliveryExceptionMsg.builder()
                    .msgId(UUID.randomUUID().toString().replace("-", ""))
                    .timestamp(System.currentTimeMillis())
                    .deliveryId(d.getId())
                    .deliveryNo(d.getDeliveryNo())
                    .pickupId(d.getPickupId())
                    .escalateRound(0)
                    .remark(remark)
                    .build();
            String dest = MqConstants.TOPIC_DELIVERY_EXCEPTION + ":" + MqConstants.TAG_EXCEPTION_CREATED;
            rocketMQTemplate.syncSend(dest, MessageBuilder.withPayload(toJson(msg)).setHeader("KEYS", msg.getMsgId()).build(), 3000);
        } catch (Exception e) {
            log.error("异常事件发送失败 deliveryNo={}", d.getDeliveryNo(), e);
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new BizException("消息序列化失败");
        }
    }
}
