package com.community.order.service;

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
 * 截单的<b>事务态</b>实现，独立成 Bean 是为了让 {@code @Transactional} 通过 Spring AOP 代理生效
 * （同类内部方法自调用不会走代理，事务会失效 —— 这是高频面试陷阱）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCutoffTxService {

    private final OrderMapper orderMapper;
    private final LocalMessageMapper localMessageMapper;
    private final FeignApi.DeliveryClient deliveryClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Transactional(rollbackFor = Exception.class)
    public int cutoffInTransaction() {
        String batchNo = "BATCH-" + LocalDateTime.now().format(BATCH_FMT);

        List<OrderMapper.PickupAggregate> aggregates = orderMapper.aggregateByPickup();
        if (aggregates.isEmpty()) {
            log.info("批次 {} 无待截单订单", batchNo);
            return 0;
        }

        int created = 0;
        List<MqMessages.OrderItem> allItems = new ArrayList<>();

        for (OrderMapper.PickupAggregate agg : aggregates) {
            // CAS：只更新 status=0 的订单，防并发重复截单
            int affected = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                    .eq(Order::getPickupId, agg.getPickupId())
                    .eq(Order::getStatus, 0)
                    .set(Order::getStatus, 1)
                    .set(Order::getUpdateTime, LocalDateTime.now()));

            if (affected == 0) {
                log.warn("自提点 {} 的订单已被其他实例截单，跳过", agg.getPickupId());
                continue;
            }

            Long deliveryId = createDelivery(batchNo, agg);
            if (deliveryId != null) {
                created++;
                orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                        .eq(Order::getPickupId, agg.getPickupId())
                        .eq(Order::getStatus, 1)
                        .set(Order::getStatus, 2)
                        .set(Order::getUpdateTime, LocalDateTime.now()));
            }

            allItems.add(MqMessages.OrderItem.builder()
                    .pickupId(agg.getPickupId())
                    .pickupName(agg.getPickupName())
                    .itemCount(agg.getItemCount())
                    .weightGram(agg.getWeightGram())
                    .build());
        }

        if (!allItems.isEmpty()) {
            saveLocalMessage(batchNo, allItems);
        }

        log.info("批次 {} 截单完成，生成配送单 {} 张", batchNo, created);
        return created;
    }

    private Long createDelivery(String batchNo, OrderMapper.PickupAggregate agg) {
        try {
            Map<String, Object> req = new HashMap<>();
            req.put("batchNo", batchNo);
            req.put("pickupId", agg.getPickupId());
            req.put("pickupName", agg.getPickupName());
            req.put("itemCount", agg.getItemCount());
            req.put("weightGram", agg.getWeightGram());

            Result<Long> resp = deliveryClient.create(req);
            if (resp != null && resp.isSuccess()) {
                return resp.getData();
            }
            log.error("创建配送单失败 pickupId={} resp={}", agg.getPickupId(), resp);
        } catch (Exception e) {
            log.error("创建配送单异常 pickupId={}", agg.getPickupId(), e);
        }
        return null;
    }

    private void saveLocalMessage(String batchNo, List<MqMessages.OrderItem> items) {
        MqMessages.OrderCutoffMsg msg = MqMessages.OrderCutoffMsg.builder()
                .msgId(UUID.randomUUID().toString().replace("-", ""))
                .timestamp(System.currentTimeMillis())
                .batchNo(batchNo)
                .orders(items)
                .build();
        try {
            LocalMessage lm = new LocalMessage();
            lm.setMsgId(msg.getMsgId());
            lm.setTopic("TOPIC_ORDER_CUTOFF");
            lm.setTag("CUTOFF");
            lm.setBizKey(batchNo);
            lm.setPayload(objectMapper.writeValueAsString(msg));
            lm.setStatus(0);
            lm.setRetryCount(0);
            lm.setNextRetryTime(LocalDateTime.now());
            lm.setCreateTime(LocalDateTime.now());
            lm.setUpdateTime(LocalDateTime.now());
            localMessageMapper.insert(lm);
        } catch (Exception e) {
            throw new BizException("本地消息落库失败: " + e.getMessage());
        }
    }
}
