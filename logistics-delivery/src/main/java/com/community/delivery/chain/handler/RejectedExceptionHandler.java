package com.community.delivery.chain.handler;

import com.community.common.enums.ExceptionTypeEnum;
import com.community.delivery.chain.ExceptionContext;
import com.community.delivery.chain.ExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 拒收处理：客户原因，不赔付、不补发，商品退回仓储重新入库。
 * 这是"负向"分支——链条只需给出方案，执行由调用方按 needReship=false 走退回流程。
 */
@Slf4j
@Component("REJECTED_HANDLER")
public class RejectedExceptionHandler implements ExceptionHandler {

    @Override
    public ExceptionTypeEnum supportType() {
        return ExceptionTypeEnum.REJECTED;
    }

    @Override
    public Map<String, Object> handle(ExceptionContext ctx) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("exceptionType", supportType().getDesc());
        plan.put("needCompensate", false);
        plan.put("needReship", false);
        plan.put("reshipCount", 0);
        plan.put("needReturnToWarehouse", true);
        plan.put("action", "退回仓储重新入库，不赔付不补发");
        log.info("拒收处理方案生成 deliveryNo={} plan={}", ctx.getDeliveryNo(), plan);
        return plan;
    }
}
