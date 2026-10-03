package com.taskflow.center.vo;


import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TaskDetailVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String requestId;
    private Long batchId;
    private String taskType;
    private Map<String, Object> params;
    private Integer status;
    private Integer priority;
    private Integer retryCount;
    private Integer maxRetry;
    private String failReason;
    private String result;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
