package com.taskflow.center.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskListItemVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String requestId;
    private String taskType;
    private Integer status;
    private Integer priority;
    private Integer retryCount;
    private Integer maxRetry;
    private String failReason;      // 列表里显示失败原因有用
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
