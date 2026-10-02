package com.taskflow.common.enums;

import lombok.Getter;

/**
 * 任务类型（可扩展，平台不解释其含义）
 *
 * <p>设计原则：封闭集合用数字，开放集合用字符串。
 * status 是平台定义的封闭状态集合（用 int）；task_type 是业务可扩展的开放集合（用字符串）。
 *
 * <p>新增任务类型：在此加一个枚举值 + 写一个对应 handler 即可，平台核心代码无需改动。
 */
@Getter
public enum TaskType {

    SEND_SMS("SEND_SMS", "发送短信"),
    MOCK_TASK("MOCK_TASK", "模拟任务");

    private final String code;
    private final String desc;

    TaskType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static TaskType of(String code) {
        for (TaskType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
