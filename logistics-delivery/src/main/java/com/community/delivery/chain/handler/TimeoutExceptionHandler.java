package com.community.delivery.chain.handler;

import com.community.common.enums.ExceptionTypeEnum;
import com.community.delivery.chain.ExceptionContext;
import com.community.delivery.chain.ExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 超时处理：不赔付商品，但催办 + 减免配送费（服务体验补偿）。
 */
@Slf4j
@Component("TIMEOUT_HANDLER")
public class TimeoutExceptionHandler implements ExceptionHandler {

    @Override
    public ExceptionTypeEnum supportType() {
        return ExceptionTypeEnum.TIMEOUT;
    }

    @Override
    public Map<String, Object> handle(ExceptionContext ctx) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("exceptionType", supportType().getDesc());
        plan.put("needCompensate", false);
        plan.put("needReship", false);
        plan.put("needUrge", true);
        plan.put("waiveDeliveryFee", true);
        plan.put("action", "催办 + 减免配送费");
        log.info("超时处理方案生成 deliveryNo={} plan={}", ctx.getDeliveryNo(), plan);
        return plan;
    }
}
