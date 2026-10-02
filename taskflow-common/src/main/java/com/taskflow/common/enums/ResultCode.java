package com.taskflow.common.enums;


import lombok.Getter;

@Getter
public enum ResultCode {
    SUCCESS(0, "success"),
    PARAM_ERROR(40001, "参数校验失败"),
    TASK_TYPE_NOT_FOUND(40002, "任务类型不存在"),
    IDEMPOTENT_CONFLICT(40003, "幂等键冲突"),
    SYSTEM_ERROR(50000, "系统内部错误");

    private Integer code;
    private String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
