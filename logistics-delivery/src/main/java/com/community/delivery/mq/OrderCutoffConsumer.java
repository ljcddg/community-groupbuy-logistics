package com.community.delivery.mq;

import com.community.common.constant.MqConstants;
import com.community.common.mq.MqMessages;
import com.community.delivery.service.DeliveryService;
import com.community.delivery.service.IdempotentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 订单截单事件消费者。
 *
 * <p><b>面试点</b>：
 * <ul>
 *   <li><b>消费者组</b>：同一 group 内多实例自动负载均衡（集群消费）；
 *       不同 group 各收一份（广播场景）。</li>
 *   <li><b>幂等</b>：先用 {@link IdempotentService} 去重，
 *       重复消息直接 ack（返回正常）而不是抛异常 —— 抛异常会触发无限重投。</li>
 *   <li><b>异常处理</b>：若业务处理失败，抛出异常让 MQ 重投（最多 16 次），
 *       超过后进死信队列（%DLQ%+group）人工介入。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_ORDER_CUTOFF,
        consumerGroup = MqConstants.GROUP_DELIVERY
)
public class OrderCutoffConsumer implements RocketMQListener<String> {

    private final IdempotentService idempotentService;
    private final DeliveryService deliveryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(String body) {
        MqMessages.OrderCutoffMsg msg;
        try {
            msg = objectMapper.readValue(body, MqMessages.OrderCutoffMsg.class);
        } catch (Exception e) {
            log.error("截单消息反序列化失败，丢弃 body={}", body, e);
            return;
        }

        // 幂等：重复消息直接返回
        if (!idempotentService.tryConsume(msg.getMsgId(), MqConstants.TOPIC_ORDER_CUTOFF, MqConstants.GROUP_DELIVERY)) {
            return;
        }

        log.info("收到截单事件 batchNo={} 自提点数={}", msg.getBatchNo(),
                msg.getOrders() == null ? 0 : msg.getOrders().size());
        if (msg.getOrders() == null) {
            return;
        }

        for (MqMessages.OrderItem item : msg.getOrders()) {
            try {
                deliveryService.create(msg.getBatchNo(), item.getPickupId(), item.getPickupName(),
                        item.getItemCount(), item.getWeightGram());
            } catch (Exception e) {
                // 单点失败不应阻塞整批；记录并继续，由对账补偿
                log.error("生成配送单失败 pickupId={}", item.getPickupId(), e);
            }
        }
    }
}
