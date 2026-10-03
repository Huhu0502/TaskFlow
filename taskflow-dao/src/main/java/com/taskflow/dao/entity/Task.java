package com.taskflow.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("task")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Task {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String requestId;
    private Long batchId;
    private String taskType;
    private String params;
    private Integer status;
    private Integer priority;
    private Integer retryCount;
    private Integer maxRetry;
    private String failReason;
    private String result;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
