package com.community.delivery.chain;

import com.community.common.enums.ExceptionTypeEnum;

import java.util.Map;

/**
 * 配送异常处理责任链的抽象处理器。
 *
 * <p><b>面试点</b>：
 * <ul>
 *   <li><b>为什么用责任链？</b> 破损/丢失/拒收/超时的处理方案差异大（是否赔付、是否补发、
 *       补发多少），若写 if-else 会随类型增加不断膨胀。责任链把每种类型的处理逻辑隔离到独立 Handler，
 *       新增类型 = 加枚举 + 加 Handler，符合开闭原则。</li>
 *   <li><b>这里的链与传统责任链的差别：</b> 传统责任链是"逐个尝试直到有一个处理"；
 *       本场景每种异常类型<b>只有一个</b>处理器，因此用枚举 code 直接路由到对应 Bean，
 *       既保留了解耦（各 Handler 独立），又避免无谓的链式遍历 —— 效率和可读性都更好。</li>
 *   <li><b>可扩展点</b>：若某类型需要多级审批（先客服、再主管），
 *       可把 {@code handlerBean} 换成 List&lt;Handler&gt;，退化为标准链式。</li>
 * </ul>
 */
public interface ExceptionHandler {

    /** 该处理器负责的异常类型 */
    ExceptionTypeEnum supportType();

    /**
     * 计算处理方案。
     *
     * @param ctx 异常上下文（配送单、类型、数量等）
     * @return 处理方案（含是否赔付、是否补发、补发数量等），将序列化存入异常单
     */
    Map<String, Object> handle(ExceptionContext ctx);
}
