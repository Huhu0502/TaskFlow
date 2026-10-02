package com.taskflow.center.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Data
public class TaskSubmitRequest {
    @NotBlank(message = "requestId 不能为空")
    @Size(max = 64, message = "requestId 不能超过64")
    private String requestId;
    @NotBlank(message = "taskType 不能为空")
    private String taskType;
    private Integer priority;
    private Integer maxRetry;
    private Long batchId;
    private Map<String, Object> params;
}
