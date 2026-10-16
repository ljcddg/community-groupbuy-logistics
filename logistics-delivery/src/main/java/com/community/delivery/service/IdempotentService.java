package com.community.delivery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.delivery.entity.ConsumeRecord;
import com.community.delivery.mapper.ConsumeRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * MQ 消费幂等组件 —— <b>面试高频考点</b>。
 *
 * <h3>为什么要幂等？</h3>
 * RocketMQ 保证的是 <b>at-least-once（至少一次）</b>投递：网络抖动、消费超时重投、
 * 消费者重启都会导致同一条消息被消费多次。业务上必须保证"重复消费不产生副作用"。
 *
 * <h3>两道防线（Redis 挡性能 + DB 保正确）</h3>
 * <ol>
 *   <li><b>Redis SETNX 第一道</b>：{@code SET key value NX EX 3600}，
 *       返回 false 说明近期已处理过，直接丢弃。快，挡住 99% 的重复。</li>
 *   <li><b>DB 唯一索引第二道</b>：Redis 可能因重启/过期丢 key，
 *       故再往 {@code t_consume_record(msg_id 唯一索引)} 插一条，
 *       捕获 {@link DuplicateKeyException} 判定为重复。准，兜底正确性。</li>
 * </ol>
 *
 * <h3>为什么不用"先查再插"？</h3>
 * 查与插之间存在竞态（check-then-act），两个消费者可能同时查到"不存在"然后都插入。
 * 靠数据库唯一索引让插入本身就带原子性，是更可靠的写法。
 *
 * <h3>其他可选方案（面试可提，体现知识面）</h3>
 * MQ 自带的 MessageId 去重（不推荐，MessageId 可能变）、业务唯一键去重（最根本）、
 * 状态机幂等（如本项目的 status 相同则 no-op）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentService {

    private final StringRedisTemplate redisTemplate;
    private final ConsumeRecordMapper consumeRecordMapper;

    private static final String KEY_PREFIX = "mq:consumed:";
    private static final Duration TTL = Duration.ofHours(24);

    /**
     * 幂等校验：返回 true 表示"首次消费，可以处理"；false 表示"重复，应跳过"。
     */
    public boolean tryConsume(String msgId, String topic, String group) {
        if (msgId == null || msgId.isBlank()) {
            // 无 msgId 无法去重，放行并告警（生产应保证消息必带 msgId）
            log.warn("消息缺少 msgId，无法幂等去重 topic={}", topic);
            return true;
        }

        // 第一道：Redis SETNX
        String key = KEY_PREFIX + group + ":" + msgId;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", TTL);
        if (Boolean.FALSE.equals(first)) {
            log.info("Redis 判定重复消费，跳过 msgId={}", msgId);
            return false;
        }

        // 第二道：DB 唯一索引兜底
        try {
            ConsumeRecord record = new ConsumeRecord();
            record.setMsgId(msgId);
            record.setTopic(topic);
            record.setConsumerGroup(group);
            record.setCreateTime(LocalDateTime.now());
            consumeRecordMapper.insert(record);
            return true;
        } catch (DuplicateKeyException e) {
            log.info("DB 唯一索引判定重复消费，跳过 msgId={}", msgId);
            return false;
        } catch (Exception e) {
            // DB 异常不应阻塞业务，Redis 已放行则继续（宁可重复不可丢失，靠业务幂等兜底）
            log.error("消费记录落库异常，降级放行 msgId={}", msgId, e);
            return true;
        }
    }

    /** 演示/排错用：清理某条消息的幂等标记 */
    public void reset(String msgId, String group) {
        redisTemplate.delete(KEY_PREFIX + group + ":" + msgId);
        consumeRecordMapper.delete(new LambdaQueryWrapper<ConsumeRecord>()
                .eq(ConsumeRecord::getMsgId, msgId));
    }
}
