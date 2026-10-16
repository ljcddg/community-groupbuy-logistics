package com.community.warehouse.mq;

import com.community.common.constant.MqConstants;
import com.community.common.mq.MqMessages;
import com.community.warehouse.service.StockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 补发扣库存消费者 —— <b>跨服务最终一致的落地点</b>。
 *
 * <p><b>面试点（务必能讲清整条链路）</b>：
 * <ol>
 *   <li>物流服务在本地事务里改配送单状态 -> 发 MQ（<b>不是</b>同步调仓储扣库存）。</li>
 *   <li>仓储消费 MQ 后扣库存，用 bizNo（补发单 ID）做<b>业务幂等</b>。</li>
 *   <li>扣库存失败（库存不足）时<b>不抛出异常</b>，而是记录后再投一条 MQ 或走人工，
 *       避免无限重投把队列打满。</li>
 *   <li>整体效果：<b>最终一致</b>。若中途宕机，MQ 会重投，幂等保证不会重复扣。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_RESHIP,
        consumerGroup = MqConstants.GROUP_WAREHOUSE,
        selectorExpression = MqConstants.TAG_RESHIP_DEDUCT
)
public class ReshipDeductConsumer implements RocketMQListener<String> {

    private final StockService stockService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String KEY_PREFIX = "mq:consumed:" + MqConstants.GROUP_WAREHOUSE + ":";

    @Override
    public void onMessage(String body) {
        MqMessages.ReshipDeductMsg msg;
        try {
            msg = objectMapper.readValue(body, MqMessages.ReshipDeductMsg.class);
        } catch (Exception e) {
            log.error("补发扣库存消息反序列化失败 body={}", body, e);
            return;
        }

        // Redis 幂等第一道
        String key = KEY_PREFIX + msg.getMsgId();
        Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofHours(24));
        if (Boolean.FALSE.equals(first)) {
            log.info("补发扣库存消息重复，跳过 msgId={}", msg.getMsgId());
            return;
        }

        log.info("收到补发扣库存消息 reshipId={} skuId={} qty={}",
                msg.getReshipId(), msg.getSkuId(), msg.getQuantity());

        try {
            // bizNo 用 reshipId，保证同一补发单只扣一次
            stockService.deduct(msg.getSkuId(), msg.getQuantity(), "RESHIP-" + msg.getReshipId());
        } catch (Exception e) {
            // 库存不足等业务失败：记录告警，不再抛出（避免无限重投）
            log.error("补发扣库存失败，转人工处理 reshipId={}", msg.getReshipId(), e);
        }
    }
}
