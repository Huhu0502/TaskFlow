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

    // ---------------- 以下两个是「运行态」字段，不来自 DB，由引擎视图实时判断 ----------------

    /** 距上次状态变更过去多少秒（前端可用来展示"已排队多久"）*/
    private Long stuckSeconds;

    /**
     * 是否疑似丢失：<b>长时间无进展 且 引擎并不繁忙</b>。
     *
     * <p>前端可据此把"重试"按钮亮起来；为 false 时说明任务只是在正常排队。
     */
    private Boolean suspectedLost;
}
