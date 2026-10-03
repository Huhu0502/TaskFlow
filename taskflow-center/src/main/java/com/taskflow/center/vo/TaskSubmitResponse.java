package com.taskflow.center.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskSubmitResponse {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    private Integer status;
    private boolean duplicated;
}
