package com.community.delivery.chain;

import com.community.common.enums.ExceptionTypeEnum;
import lombok.Builder;
import lombok.Data;

/**
 * 异常处理上下文 —— 责任链各 Handler 的输入。
 */
@Data
@Builder
public class ExceptionContext {

    private Long deliveryId;
    private String deliveryNo;
    private Long pickupId;
    private ExceptionTypeEnum type;
    /** 该单件数 */
    private Integer itemCount;
    /** 涉及金额（分），用于计算赔付 */
    private Long amountCent;
    /** 备注（司机上报的描述） */
    private String remark;
}
