package com.taskflow.center.controller;

import com.taskflow.center.dto.TaskSubmitRequest;
import com.taskflow.center.dto.TaskSubmitResponse;
import com.taskflow.center.service.TaskService;
import com.taskflow.common.result.Result;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/api/task")
public class TaskController {
    @Autowired
    private TaskService taskService;

    /**
     * 提交任务
     * @param request
     * @return
     */
    @PostMapping("/submit")
    public Result<TaskSubmitResponse> submit(@Valid @RequestBody TaskSubmitRequest request) {
        log.info("提交任务，requestId={}, taskType={}", request.getRequestId(), request.getTaskType());
        return Result.ok(taskService.submit(request));
    }
}
