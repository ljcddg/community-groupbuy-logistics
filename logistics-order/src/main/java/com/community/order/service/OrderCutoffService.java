package com.community.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.community.common.exception.BizException;
import com.community.common.feign.FeignApi;
import com.community.common.mq.MqMessages;
import com.community.common.result.Result;
import com.community.order.entity.LocalMessage;
import com.community.order.entity.Order;
import com.community.order.mapper.LocalMessageMapper;
import com.community.order.mapper.OrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 截单 + 生成配送单。
 *
 * <p><b>面试点串讲（重点）</b>：
 * <ol>
 *   <li><b>截单触发方式：</b> 截单是"到点关门"的时间驱动行为。生产上用 RocketMQ 延迟消息
 *       能到秒级且天然分布式；本 Demo 用 {@link org.springframework.scheduling.annotation.Scheduled}
 *       + Redisson 分布式锁保证多实例只跑一次，语义等价且便于演示。</li>
 *   <li><b>聚合模型：</b> 同一批次内按 <code>pickup_id</code> 分组，一个自提点一张配送单，
 *       减少车辆启停 —— 这是社区团购"以自提点为单位"的配送模型。</li>
 *   <li><b>并发安全：</b> 分布式锁防重复截单；订单状态用 <code>UPDATE ... WHERE status=0</code>
 *       做 CAS，返回受影响行数为 0 说明已被处理，直接跳过。</li>
 *   <li><b>最终一致（替代 Seata）：</b> 本地事务内同时写「业务数据 + 本地消息表」，
 *       提交后再投递 MQ；投递失败由补偿任务重投。至少一次投递 + 消费端幂等 = 最终一致。</li>
 * </ol>
 *
 * <p><b>注意</b>：{@link #cutoff()} 负责加锁，事务方法由外部调用以走 Spring 代理，
 * 避免 self-invocation 导致 @Transactional 失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCutoffService {

    private final OrderMapper orderMapper;
    private final LocalMessageMapper localMessageMapper;
    private final FeignApi.DeliveryClient deliveryClient;
    private final RedissonClient redissonClient;
    private final OrderCutoffTxService txService;

    private static final String LOCK_CUTOFF = "lock:order:cutoff";
    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 执行截单：加分布式锁，多实例安全 */
    public int cutoff() {
        RLock lock = redissonClient.getLock(LOCK_CUTOFF);
        boolean locked = false;
        try {
            // 不等待、持有 30s：拿不到锁说明已有实例在截单，直接返回
            locked = lock.tryLock(0, 30, java.util.concurrent.TimeUnit.SECONDS);
            if (!locked) {
                log.info("截单任务已被其他实例持有，本次跳过");
                return 0;
            }
            return doCutoff();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("截单任务被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 真正的截单逻辑，委托给独立的 {@link OrderCutoffTxService}，
     * 以便 @Transactional 通过 Spring 代理生效。
     */
    int doCutoff() {
        return txService.cutoffInTransaction();
    }

    /** 手动下单（演示用） */
    @Transactional(rollbackFor = Exception.class)
    public Long createOrder(Long userId, Long pickupId, String pickupName, Integer itemCount, Integer weightGram) {
        Order o = new Order();
        o.setOrderNo("OD" + System.currentTimeMillis());
        o.setUserId(userId);
        o.setPickupId(pickupId);
        o.setPickupName(pickupName);
        o.setItemCount(itemCount);
        o.setWeightGram(weightGram);
        o.setStatus(0);
        o.setCreateTime(LocalDateTime.now());
        o.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(o);
        return o.getId();
    }

    /** 演示用：订单列表 */
    public List<Map<String, Object>> listAllAsMap() {
        return orderMapper.selectList(new LambdaQueryWrapper<Order>().orderByDesc(Order::getId).last("LIMIT 100"))
                .stream()
                .map(o -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", o.getId());
                    m.put("orderNo", o.getOrderNo());
                    m.put("pickupId", o.getPickupId());
                    m.put("pickupName", o.getPickupName());
                    m.put("itemCount", o.getItemCount());
                    m.put("weightGram", o.getWeightGram());
                    m.put("status", o.getStatus());
                    m.put("statusDesc", orderStatusDesc(o.getStatus()));
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private String orderStatusDesc(Integer s) {
        if (s == null) return "未知";
        return switch (s) {
            case 0 -> "待截单";
            case 1 -> "已截单";
            case 2 -> "已生成配送单";
            case 3 -> "已取消";
            default -> "未知";
        };
    }
}
