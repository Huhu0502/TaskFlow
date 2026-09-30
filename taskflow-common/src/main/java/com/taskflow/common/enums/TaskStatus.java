package com.taskflow.common.enums;

import lombok.Getter;

@Getter
public enum TaskStatus {
    CREATED(0, "创建成功等待入队"),
    QUEUED(1, "已经入队，待处理"),
    RUNNING(2, "执行中"),
    SUCCESS(3, "成功"),
    FAILED(4, "失败"),
    TIMEOUT(5, "超时"),
    CANCELED(6, "已取消");

    private final int code;

    private final String desc;

    TaskStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static TaskStatus of(int code) {
        for (TaskStatus status : values()) {
            if(status.code == code) {
                return status;
            }
        }
        return null;
    }

}
