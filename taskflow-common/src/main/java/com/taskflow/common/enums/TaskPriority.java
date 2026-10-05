package com.taskflow.common.enums;

import lombok.Getter;

@Getter
public enum TaskPriority {
    NORMAL(1, "普通级别"),
    VIP(10, "VIP级别");

    private final int code;

    private final String desc;

    TaskPriority(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static TaskPriority of(int code) {
        for (TaskPriority priority : values()) {
            if (priority.code == code) {
                return priority;
            }
        }
        return null;
    }

    /** 路由用：是否为 VIP 优先级 */
    public static boolean isVip(int code) {
        return code == VIP.getCode();
    }
}
