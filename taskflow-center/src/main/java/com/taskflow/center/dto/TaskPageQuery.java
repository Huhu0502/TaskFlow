package com.taskflow.center.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Data
public class TaskPageQuery {
    @Min(value = 1, message = "pageNo不能小于1")
    private Long pageNo = 1L;
    @Min(value = 1, message = "pageSize不能小于1")
    private Long pageSize = 10L;
    private Integer status;
    private String taskType;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
