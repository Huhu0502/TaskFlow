package com.taskflow.common.enums;

import lombok.Getter;

@Getter
public enum TaskType {
    CONVERT(0, "转写任务"),
    CHECK(1, "质检任务"),
    EXPORT(2, "导出任务");

    private final int code;

    private final String desc;

    TaskType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static TaskType of(int code) {
        for (TaskType type : values()) {
            if(type.code == code) {
                return type;
            }
        }
        return null;
    }
}
