package com.community.delivery.mq;

import com.community.common.constant.CommonConstants;
import com.community.common.constant.MqConstants;
import com.community.common.mq.MqMessages;
import com.community.delivery.service.IdempotentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 配送状态变更消费者 —— 负责"更新物流轨迹"。
 *
 * <p>面试点：状态变更由物流服务自己发出、自己消费，看似绕，
 * 实则把"轨迹持久化 + 缓存刷新 + 后续推送（短信/App）"从主流程剥离为异步，
 * 缩短状态机事务的持有时间，提升吞吐。生产上这个消费者还会驱动
 * 用户端推送、司机端同步等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_DELIVERY_STATUS,
        consumerGroup = MqConstants.GROUP_DELIVERY_TRACE
)
public class DeliveryStatusConsumer implements RocketMQListener<String> {

    private final IdempotentService idempotentService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(String body) {
        MqMessages.DeliveryStatusMsg msg;
        try {
            msg = objectMapper.readValue(body, MqMessages.DeliveryStatusMsg.class);
        } catch (Exception e) {
            log.error("状态变更消息反序列化失败 body={}", body, e);
            return;
        }

        if (!idempotentService.tryConsume(msg.getMsgId(), MqConstants.TOPIC_DELIVERY_STATUS, MqConstants.GROUP_DELIVERY_TRACE)) {
            return;
        }

        // ① 刷新轨迹缓存（Redis List，供 App 端快速拉取）
        String key = CommonConstants.REDIS_TRACE_PREFIX + msg.getDeliveryId() + ":list";
        String item = String.format("%s->%s @%d", msg.getFromStatus(), msg.getToStatus(), System.currentTimeMillis());
        redisTemplate.opsForList().rightPush(key, item);
        redisTemplate.expire(key, Duration.ofHours(24));

        // ② 维护最近一次状态
        redisTemplate.opsForValue().set(
                CommonConstants.REDIS_TRACE_PREFIX + msg.getDeliveryId() + ":last",
                String.valueOf(msg.getToStatus()),
                Duration.ofHours(24));

        log.info("轨迹已更新 deliveryNo={} {}->{}（异步）",
                msg.getDeliveryNo(), msg.getFromStatus(), msg.getToStatus());

        // ③ 生产可在此扩展：App 推送、司机端通知、统计埋点
    }
}
