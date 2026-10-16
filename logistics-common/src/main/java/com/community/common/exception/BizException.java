package com.community.common.exception;

import com.community.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。全局异常处理器捕获后转成统一 Result。
 */
@Getter
public class BizException extends RuntimeException {

    private final Integer code;

    public BizException(String message) {
        super(message);
        this.code = ResultCode.FAIL.getCode();
    }

    public BizException(ResultCode rc) {
        super(rc.getMessage());
        this.code = rc.getCode();
    }

    public BizException(ResultCode rc, String message) {
        super(message);
        this.code = rc.getCode();
    }

    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
