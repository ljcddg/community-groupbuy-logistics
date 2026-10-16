package com.community.common.exception;

import com.community.common.result.Result;
import com.community.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 * 面试点：@RestControllerAdvice + @ExceptionHandler 统一收敛异常，
 * 业务异常走 200 + 业务码，系统异常打 error 日志并返回兜底文案，避免把堆栈暴露给前端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e, HttpServletRequest req) {
        log.warn("业务异常 uri={} code={} msg={}", req.getRequestURI(), e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValid(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArg(IllegalArgumentException e) {
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e, HttpServletRequest req) {
        // Seata 的 AdapterInvocationWrapper.proceed() 会把业务方法抛出的原始异常
        // 包装成 RuntimeException("try to proceed invocation error")，
        // 于是上面的 BizException 分支匹配不到，业务码会被降级成"系统繁忙"。
        // 这里沿 cause 链向下找 BizException，找到就按业务异常返回，
        // 保证「库存不足」这类可预期失败仍返回精确业务码。
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof BizException be) {
                log.warn("业务异常(被 Seata 包装) uri={} code={} msg={}",
                        req.getRequestURI(), be.getCode(), be.getMessage());
                return Result.fail(be.getCode(), be.getMessage());
            }
        }
        log.error("系统异常 uri={}", req.getRequestURI(), e);
        return Result.fail(ResultCode.FAIL);
    }
}
