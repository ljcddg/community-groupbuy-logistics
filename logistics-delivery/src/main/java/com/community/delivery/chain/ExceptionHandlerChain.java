package com.community.delivery.chain;

import com.community.common.enums.ExceptionTypeEnum;
import com.community.common.exception.BizException;
import com.community.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 责任链调度器（Handler 注册表）。
 *
 * <p><b>面试点</b>：
 * <ul>
 *   <li>Spring 启动时把所有 {@link ExceptionHandler} 注入到 List，
 *       构造器里按 {@link ExceptionHandler#supportType()} 建索引，
 *       运行期 O(1) 路由 —— 避免每次都遍历 List 做匹配。</li>
 *   <li>用 {@code EnumMap} 而非 HashMap，枚举作 key 时内存更省、遍历有序。</li>
 *   <li>新增异常类型：加枚举项 + 新 Handler 类（加 {@code @Component}），
 *       注册表自动收编，<b>零改动</b> 已有代码 —— 这就是开闭原则的落地。</li>
 * </ul>
 */
@Slf4j
@Component
public class ExceptionHandlerChain {

    private final Map<ExceptionTypeEnum, ExceptionHandler> registry = new EnumMap<>(ExceptionTypeEnum.class);

    public ExceptionHandlerChain(List<ExceptionHandler> handlers) {
        for (ExceptionHandler h : handlers) {
            registry.put(h.supportType(), h);
        }
        log.info("异常处理责任链已装配 {} 个处理器: {}", registry.size(), registry.keySet());
    }

    /** 按异常类型路由到对应处理器 */
    public Map<String, Object> dispatch(ExceptionContext ctx) {
        ExceptionTypeEnum type = ctx.getType();
        if (type == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "异常类型不能为空");
        }
        ExceptionHandler handler = registry.get(type);
        if (handler == null) {
            log.warn("未找到异常类型 {} 的处理器，使用兜底方案", type);
            return Map.of("exceptionType", type.getDesc(),
                    "action", "转人工处理",
                    "needReship", false);
        }
        return handler.handle(ctx);
    }

    /** 暴露已注册的处理器（用于健康检查 / 排错） */
    public java.util.Set<ExceptionTypeEnum> supportedTypes() {
        return java.util.Collections.unmodifiableSet(registry.keySet());
    }
}
