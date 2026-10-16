package com.community.common.result;

import lombok.Getter;

/**
 * 业务码。分段：
 * 1xxx 通用 / 2xxx 订单 / 3xxx 物流 / 4xxx 仓储 / 5xxx 自提点 / 401x 鉴权
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "成功"),
    FAIL(500, "系统繁忙，请稍后再试"),

    PARAM_ERROR(1001, "参数校验失败"),
    UNAUTHORIZED(4010, "未登录或 Token 已失效"),
    FORBIDDEN(4030, "无访问权限"),

    // 订单
    ORDER_NOT_FOUND(2001, "订单不存在"),
    ORDER_CUTOFF_CLOSED(2002, "当前批次已截单，无法下单"),
    ORDER_ALREADY_GROUPED(2003, "订单已生成配送单，请勿重复提交"),

    // 物流
    DELIVERY_NOT_FOUND(3001, "配送单不存在"),
    DELIVERY_STATUS_ILLEGAL(3002, "配送单状态流转非法"),
    DELIVERY_CONCURRENT_MODIFY(3003, "配送单正被处理，请稍后重试"),
    DELIVERY_ALREADY_EXCEPTION(3004, "该配送单已标记异常"),

    // 仓储
    STOCK_NOT_ENOUGH(4001, "库存不足"),
    STOCK_DEDUCT_FAIL(4002, "库存扣减失败"),

    // 自提点
    PICKUP_NOT_FOUND(5001, "自提点不存在"),
    PICKUP_CAPACITY_EXCEEDED(5002, "自提点容量已超限"),
    PICKUP_NO_ADJACENT(5003, "未找到可承接的相邻自提点");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
