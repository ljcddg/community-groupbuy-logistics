package com.community.common.enums;

import lombok.Getter;

/**
 * 配送异常类型。
 * 面试点：与「责任链」一一对应——每种类型有独立的 Handler 计算处理方案，
 * 新增类型只需加枚举 + 加一个 Handler，不改原有代码（开闭原则）。
 */
@Getter
public enum ExceptionTypeEnum {

    /** 破损：按破损比例赔付 + 整单或部分补发 */
    DAMAGED(1, "商品破损", "DAMAGED_HANDLER"),
    /** 丢失：全额赔付 + 全额补发 */
    LOST(2, "商品丢失", "LOST_HANDLER"),
    /** 拒收：客户原因拒收，退回仓储，不补发 */
    REJECTED(3, "客户拒收", "REJECTED_HANDLER"),
    /** 超时：配送超时，催办 + 减免配送费 */
    TIMEOUT(4, "配送超时", "TIMEOUT_HANDLER");

    private final Integer code;
    private final String desc;
    /** 对应责任链 Handler 的 Spring Bean 名称 */
    private final String handlerBean;

    ExceptionTypeEnum(Integer code, String desc, String handlerBean) {
        this.code = code;
        this.desc = desc;
        this.handlerBean = handlerBean;
    }

    public static ExceptionTypeEnum of(Integer code) {
        if (code == null) {
            return null;
        }
        for (ExceptionTypeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }
}
