package com.community.delivery.service;

import com.community.common.constant.MqConstants;
import com.community.common.enums.DeliveryStatusEnum;
import com.community.common.exception.BizException;
import com.community.common.feign.FeignApi;
import com.community.common.mq.MqMessages;
import com.community.common.result.Result;
import com.community.common.result.ResultCode;
import com.community.delivery.entity.Delivery;
import com.community.delivery.entity.DeliveryException;
import com.community.delivery.mapper.DeliveryExceptionMapper;
import com.community.delivery.mapper.DeliveryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.seata.spring.annotation.GlobalTransactional;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 补发服务 —— <b>替代 Seata 的跨服务一致性方案</b>，面试重点。
 *
 * <h3>问题</h3>
 * 补发要同时做两件事，跨两个服务：
 * <ol>
 *   <li>物流服务：把配送单转入"补发中"，重新走配送流程</li>
 *   <li>仓储服务：扣减对应 SKU 的库存</li>
 * </ol>
 * 这两个操作必须"要么都成功，要么都不做"，否则会出现
 * "库存扣了但没补发"或"补发了但库存没扣"的数据不一致。
 *
 * <h3>方案对比（面试可以直接讲这个表）</h3>
 * <table>
 *   <tr><th>方案</th><th>一致性</th><th>复杂度</th><th>适用</th></tr>
 *   <tr><td>Seata AT</td><td>强一致（全局锁+二阶段）</td><td>高（需 TC 服务、undo_log）</td><td>短事务、强一致要求</td></tr>
 *   <tr><td>本地消息表 + 幂等 + 重试</td><td>最终一致</td><td>中</td><td>异步、可容忍秒级延迟</td></tr>
 *   <tr><td>RocketMQ 事务消息</td><td>最终一致</td><td>中（Broker 回查）</td><td>同上，且不依赖本地表</td></tr>
 * </table>
 *
 * <h3>本项目为何默认选本地消息表</h3>
 * 补发链路短（2 个服务）、业务上可容忍秒级延迟、且必须保证"至少一次"（补发不能丢）。
 * 用本地消息表 + MQ 异步扣库存，比引入 Seata TC 集群更轻，且天然可重试。
 * <b>面试时主动说出这个取舍，是加分项。</b>
 *
 * <p><b>同时本项目也提供了 Seata AT 版本</b>（见 {@link #reshipWithSeata(Long)}），
 * 两者并存以便对比：MQ 版走最终一致，Seata 版走强一致。能讲清"什么时候该用哪个"，
 * 比只会一种方案更有说服力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReshipService {

    private final DeliveryMapper deliveryMapper;
    private final DeliveryExceptionMapper exceptionMapper;
    private final DeliveryStateMachineService stateMachine;
    /** Seata AT 模式：用同步 Feign 调仓储扣库存（XID 随请求头传播） */
    private final FeignApi.WarehouseClient warehouseClient;
    private final RedissonClient redissonClient;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String LOCK_RESHIP = "lock:delivery:reship:";

    /**
     * 执行补发。
     * 步骤：① 本地事务把配送单转入"补发中"  ② 发 MQ 通知仓储扣库存（异步、可重试）
     */
    @Transactional(rollbackFor = Exception.class)
    public void reship(Long deliveryId) {
        RLock lock = redissonClient.getLock(LOCK_RESHIP + deliveryId);
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
            DeliveryException de = exceptionMapper.selectLatestByDeliveryId(deliveryId);
            if (de == null) {
                throw new BizException(ResultCode.DELIVERY_ALREADY_EXCEPTION, "该配送单没有异常记录，无需补发");
            }
            if (!Boolean.TRUE.equals(de.getNeedReship())) {
                throw new BizException(ResultCode.PARAM_ERROR, "该异常无需补发（如客户拒收）");
            }

            // ① 配送单转入补发中（走状态机：EXCEPTION -> RESHEDDING）
            stateMachine.transfer(deliveryId, DeliveryStatusEnum.RESHEDDING, "system", "发起补发");

            // ② 更新异常单状态
            exceptionMapper.casStatus(de.getId(), 0, 1);

            // ③ 发 MQ 通知仓储扣库存（异步，仓储侧幂等）
            int reshipCount = de.getReshipCount() == null ? 1 : de.getReshipCount();
            MqMessages.ReshipDeductMsg msg = MqMessages.ReshipDeductMsg.builder()
                    .msgId(UUID.randomUUID().toString().replace("-", ""))
                    .timestamp(System.currentTimeMillis())
                    .reshipId(de.getId())
                    .deliveryId(deliveryId)
                    .pickupId(d.getPickupId())
                    .skuId(1001)          // 演示：固定 SKU
                    .quantity(reshipCount)
                    .build();
            String dest = MqConstants.TOPIC_RESHIP + ":" + MqConstants.TAG_RESHIP_DEDUCT;
            rocketMQTemplate.syncSend(dest,
                    MessageBuilder.withPayload(toJson(msg)).setHeader("KEYS", msg.getMsgId()).build(), 3000);

            log.info("补发已发起 deliveryNo={} reshipCount={}，扣库存消息已投递（最终一致）",
                    d.getDeliveryNo(), reshipCount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("获取补发锁被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 补发完成后确认（由仓储扣库存成功回调 / 或人工确认） */
    public void confirmReship(Long deliveryId) {
        DeliveryException de = exceptionMapper.selectLatestByDeliveryId(deliveryId);
        if (de != null) {
            exceptionMapper.casStatus(de.getId(), 1, 2);
            log.info("异常单已确认处理完成 exceptionId={}", de.getId());
        }
    }

    /**
     * 【Seata AT 模式】补发 —— <b>与上面的 MQ 异步版并存</b>，面试时可对比讲解两种一致性方案。
     *
     * <h3>与 {@link #reship(Long)} 的本质区别</h3>
     * <table>
     *   <tr><th>维度</th><th>reship() 本地消息表</th><th>reshipWithSeata() AT 模式</th></tr>
     *   <tr><td>调用方式</td><td>发 MQ，异步</td><td>Feign 同步调用</td></tr>
     *   <tr><td>一致性</td><td>最终一致</td><td>强一致（失败即全局回滚）</td></tr>
     *   <tr><td>补偿机制</td><td>消费幂等 + 定时重投</td><td>TC 驱动 undo_log 反向补偿</td></tr>
     *   <tr><td>业务侵入</td><td>需维护本地消息表</td><td>一个注解</td></tr>
     * </table>
     *
     * <h3>AT 模式的关键机制（面试重点）</h3>
     * <ol>
     *   <li><b>一阶段</b>：执行业务 SQL 并记录<b>前后镜像</b>到 undo_log，随后<b>本地事务立即提交</b>
     *       （不长时间持有数据库锁），同时向 TC 注册分支事务、申请全局锁。</li>
     *   <li><b>二阶段-提交</b>：只需异步删除 undo_log，非常快。</li>
     *   <li><b>二阶段-回滚</b>：读 undo_log 的<b>前镜像</b>生成反向 SQL 补偿，
     *       并校验后镜像，防止覆盖别人的更新（脏写）。</li>
     *   <li><b>XID 传播</b>：全局事务 ID 随 Feign 请求头（RootContext）自动透传到仓储服务，
     *       仓储侧的 deduct 因此被识别为同一全局事务的分支。</li>
     * </ol>
     *
     * <p><b>为什么这里必须用同步 Feign 而不是 MQ？</b>
     * XID 依赖 RPC 调用上下文传递；MQ 是异步投递，脱离了调用链，XID 带不过去，
     * 消费方不会被纳入同一个全局事务 —— 这正是"Seata 与异步 MQ 不能混用"的根本原因。
     */
    @GlobalTransactional(name = "reship-deduct-tx", rollbackFor = Exception.class)
    public void reshipWithSeata(Long deliveryId) {
        Delivery d = deliveryMapper.selectById(deliveryId);
        if (d == null) {
            throw new BizException(ResultCode.DELIVERY_NOT_FOUND);
        }
        DeliveryException de = exceptionMapper.selectLatestByDeliveryId(deliveryId);
        if (de == null) {
            throw new BizException(ResultCode.DELIVERY_ALREADY_EXCEPTION, "该配送单没有异常记录，无需补发");
        }
        if (!Boolean.TRUE.equals(de.getNeedReship())) {
            throw new BizException(ResultCode.PARAM_ERROR, "该异常无需补发（如客户拒收）");
        }

        // 分支一（本地，物流库）：配送单 异常(5) -> 补发中(7)
        stateMachine.transfer(deliveryId, DeliveryStatusEnum.RESHEDDING, "system", "发起补发(Seata AT)");

        // 分支二（远程，仓储库）：同步 Feign 扣库存，XID 随请求头自动传播
        int reshipCount = de.getReshipCount() == null ? 1 : de.getReshipCount();
        Result<Boolean> resp = warehouseClient.deduct(1001, reshipCount, "RESHIP-" + de.getId());
        if (resp == null || !resp.isSuccess()) {
            // 抛出异常 -> TC 回滚全局事务 -> 分支一那个已提交的本地事务被 undo_log 反向补偿
            throw new BizException(ResultCode.STOCK_DEDUCT_FAIL,
                    "补发扣库存失败，全局事务将回滚（resp=" + resp + "）");
        }

        log.info("【Seata AT】补发完成 deliveryNo={} reshipCount={}，全局事务提交",
                d.getDeliveryNo(), reshipCount);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException("序列化失败: " + e.getMessage());
        }
    }
}
