package com.community.delivery.chain.handler;

import com.community.common.enums.ExceptionTypeEnum;
import com.community.delivery.chain.ExceptionContext;
import com.community.delivery.chain.ExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 破损处理：按件赔付 + 补发。
 * 规则：破损按 1 件赔付，同时补发 1 件（保证客户最终收到完好商品）。
 */
@Slf4j
@Component("DAMAGED_HANDLER")
public class DamagedExceptionHandler implements ExceptionHandler {

    @Override
    public ExceptionTypeEnum supportType() {
        return ExceptionTypeEnum.DAMAGED;
    }

    @Override
    public Map<String, Object> handle(ExceptionContext ctx) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("exceptionType", supportType().getDesc());
        plan.put("needCompensate", true);
        // 破损件数默认按 1 件计（实际应由司机上报具体件数，此处演示简化）
        plan.put("compensateCount", 1);
        plan.put("needReship", true);
        plan.put("reshipCount", 1);
        plan.put("action", "补发 1 件 + 按件赔付");
        log.info("破损处理方案生成 deliveryNo={} plan={}", ctx.getDeliveryNo(), plan);
        return plan;
    }
}
