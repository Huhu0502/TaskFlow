package com.taskflow.center.service;


import com.taskflow.center.dto.TaskSubmitRequest;
import com.taskflow.center.dto.TaskSubmitResponse;

public interface TaskService {
    /**
     * 任务提交
     * @param request 任务参数
     * @return 提交结果
     */
    TaskSubmitResponse submit(TaskSubmitRequest request);
}
