package com.community.order.mq;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.community.order.entity.LocalMessage;
import com.community.order.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表投递器（补偿兜底）。
 *
 * <p>面试点（这是"最终一致性"的关键闭环，务必能讲）：
 * <ol>
 *   <li><b>为什么需要它？</b> 业务事务提交后、MQ 投递前如果进程崩溃，消息就丢了。
 *       把消息先落库，事务与业务原子提交；再异步投递，投递失败可重投。</li>
 *   <li><b>投递语义是"至少一次"</b>，所以消费端必须幂等（用 msgId 去重），
 *       两者配合才能得到"恰好一次"的业务效果。</li>
 *   <li><b>为什么没用 RocketMQ 事务消息？</b> 事务消息是更优解（半消息 + 回查），
 *       但本地消息表更直观、可审计（能查历史消息），且不依赖 Broker 版本。
 *       面试时两条路都能讲清楚即可。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageSender {

    private final LocalMessageMapper localMessageMapper;
    private final RocketMQTemplate rocketMQTemplate;

    /** 定时补偿：扫描未成功投递的消息并重投 */
    @Scheduled(fixedDelay = 10_000L, initialDelay = 15_000L)
    public void resend() {
        List<LocalMessage> pending = localMessageMapper.selectPendingMessages();
        if (pending.isEmpty()) {
            return;
        }
        log.info("本地消息补偿：待投递 {} 条", pending.size());
        for (LocalMessage lm : pending) {
            sendOne(lm);
        }
    }

    /** 立即投递一条（正常路径调用；失败则留待补偿） */
    public void sendOne(LocalMessage lm) {
        try {
            Message<String> message = MessageBuilder.withPayload(lm.getPayload())
                    .setHeader("KEYS", lm.getMsgId())
                    .build();
            String destination = lm.getTopic() + ":" + lm.getTag();
            SendResult sr = rocketMQTemplate.syncSend(destination, message, 3000);
            log.info("消息投递成功 msgId={} dest={} result={}", lm.getMsgId(), destination, sr.getSendStatus());

            localMessageMapper.update(null, new LambdaUpdateWrapper<LocalMessage>()
                    .eq(LocalMessage::getId, lm.getId())
                    .set(LocalMessage::getStatus, 1)
                    .set(LocalMessage::getUpdateTime, LocalDateTime.now()));
        } catch (Exception e) {
            int retry = lm.getRetryCount() == null ? 0 : lm.getRetryCount();
            log.error("消息投递失败 msgId={} retry={}", lm.getMsgId(), retry, e);
            // 指数退避：10s * 2^retry，最大 5 次
            long delaySec = Math.min(10L * (1L << Math.min(retry, 5)), 300L);
            localMessageMapper.update(null, new LambdaUpdateWrapper<LocalMessage>()
                    .eq(LocalMessage::getId, lm.getId())
                    .set(LocalMessage::getStatus, 2)
                    .set(LocalMessage::getRetryCount, retry + 1)
                    .set(LocalMessage::getNextRetryTime, LocalDateTime.now().plusSeconds(delaySec))
                    .set(LocalMessage::getUpdateTime, LocalDateTime.now()));
        }
    }
}
