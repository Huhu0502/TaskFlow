package com.taskflow.common.exception;

import com.taskflow.common.enums.ResultCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException{
    private final int code;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
