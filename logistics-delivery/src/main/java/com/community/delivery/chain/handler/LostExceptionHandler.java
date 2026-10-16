package com.community.delivery.chain.handler;

import com.community.common.enums.ExceptionTypeEnum;
import com.community.delivery.chain.ExceptionContext;
import com.community.delivery.chain.ExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 丢失处理：全额赔付 + 全额补发（客户体验最差，处理规格最高）。
 */
@Slf4j
@Component("LOST_HANDLER")
public class LostExceptionHandler implements ExceptionHandler {

    @Override
    public ExceptionTypeEnum supportType() {
        return ExceptionTypeEnum.LOST;
    }

    @Override
    public Map<String, Object> handle(ExceptionContext ctx) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("exceptionType", supportType().getDesc());
        plan.put("needCompensate", true);
        // 丢失比破损严重：整单全额赔付
        plan.put("compensateCount", ctx.getItemCount() == null ? 1 : ctx.getItemCount());
        plan.put("compensateAmountCent", ctx.getAmountCent() == null ? 0L : ctx.getAmountCent());
        plan.put("needReship", true);
        // 全额补发
        plan.put("reshipCount", ctx.getItemCount() == null ? 1 : ctx.getItemCount());
        plan.put("action", "全额赔付 + 全额补发");
        plan.put("priority", "HIGH");
        log.info("丢失处理方案生成 deliveryNo={} plan={}", ctx.getDeliveryNo(), plan);
        return plan;
    }
}
