package com.taskflow.center.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.center.dto.TaskSubmitRequest;
import com.taskflow.center.dto.TaskSubmitResponse;
import com.taskflow.center.entity.Task;
import com.taskflow.center.mapper.TaskMapper;
import com.taskflow.center.service.TaskService;
import com.taskflow.common.enums.ResultCode;
import com.taskflow.common.enums.TaskStatus;
import com.taskflow.common.enums.TaskType;
import com.taskflow.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements TaskService {

    private final ObjectMapper objectMapper;

    public TaskServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskSubmitResponse submit(TaskSubmitRequest request) {
        //验证任务类型
        if(TaskType.of(request.getTaskType()) == null) {
            throw new BizException(ResultCode.TASK_TYPE_NOT_FOUND);
        }
        //幂等
        Task existing = getByRequestId(request.getRequestId());
        if(existing != null) {
            log.info("幂等命中,requestId={}, taskId={}", existing.getRequestId(), existing.getId());
            return new TaskSubmitResponse(existing.getId(), existing.getStatus(), true);
        }
        //组装
        String paramsJson = map2Json(request.getParams());
        Task task = new Task();
        task.setBatchId(task.getBatchId());
        task.setRequestId(request.getRequestId());
        task.setTaskType(request.getTaskType());
        task.setParams(paramsJson);
        task.setStatus(TaskStatus.CREATED.getCode());
        task.setPriority(request.getPriority()==null ? 0 : request.getPriority());
        task.setRetryCount(request.getMaxRetry()==null ? 0 : request.getMaxRetry());

        //插入
        try {
            this.save(task);
        } catch (DuplicateKeyException e) {
            log.warn("唯一索引冲突，requestId={}",request.getRequestId());
            Task dup = getByRequestId(request.getRequestId());
            return new TaskSubmitResponse(dup.getId(), dup.getStatus(), true);
        }
        log.info("任务创建成功, taskId={}, requestId={}", task.getId(), task.getRequestId());
        return new TaskSubmitResponse(task.getId(), task.getStatus(), false);
    }

    private String map2Json(Map<String, Object> params) {
        if(params == null || params.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.PARAM_ERROR.getCode(), "params序列化失败");
        }
    }

    private Task getByRequestId(String requestId) {
        //走唯一索引
        return this.getOne(new LambdaQueryWrapper<Task>().eq(Task::getRequestId, requestId));
    }
}
