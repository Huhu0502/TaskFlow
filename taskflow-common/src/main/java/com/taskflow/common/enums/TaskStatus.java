package com.taskflow.common.enums;

import lombok.Getter;

@Getter
public enum TaskStatus {
    CREATED(0, "创建成功等待入队"),
    QUEUED(1, "已经入队，待处理"),
    TRANSCRIBING(2, "转写中"),
    TRANSCRIBED(3, "转写完成待质检"),
    QC_ING(4, "质检中"),
    SUCCESS(5, "成功"),
    FAILED(6, "失败"),
    TIMEOUT(7, "超时"),
    CANCELED(8, "已取消");

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

    public static boolean isTerminal(int code) {
        return code == SUCCESS.getCode() || code == FAILED.getCode() || code == CANCELED.getCode() || code == TIMEOUT.getCode();
    }

    public static boolean isStageRunning(int code) {
        return code == TRANSCRIBING.getCode() || code == QC_ING.getCode();
    }
}
