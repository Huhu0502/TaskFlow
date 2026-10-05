package com.taskflow.common.enums;


import lombok.Getter;

@Getter
public enum ResultCode {
    SUCCESS(0, "success"),
    PARAM_ERROR(40001, "参数校验失败"),
    TASK_TYPE_NOT_FOUND(40002, "任务类型不存在"),
    IDEMPOTENT_CONFLICT(40003, "幂等键冲突"),
    TASK_STILL_QUEUEING(40004, "任务仍在正常排队，请稍后再试"),
    TASK_ALREADY_FINISHED(40005, "任务已结束，无需重试"),
    TASK_RETRY_CONFLICT(40006, "任务状态刚刚发生变化，请刷新后重试"),
    TASK_NOT_FOUND(40401, "任务不存在"),
    SYSTEM_ERROR(50000, "系统内部错误");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
