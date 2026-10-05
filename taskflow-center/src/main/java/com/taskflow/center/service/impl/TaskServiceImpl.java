package com.taskflow.center.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.center.convert.TaskConverter;
import com.taskflow.center.dto.TaskPageQuery;
import com.taskflow.center.dto.TaskSubmitRequest;
import com.taskflow.center.vo.TaskListItemVO;
import com.taskflow.center.vo.TaskSubmitResponse;
import com.taskflow.dao.entity.Task;
import com.taskflow.dao.mapper.TaskMapper;
import com.taskflow.center.service.TaskService;
import com.taskflow.center.vo.TaskDetailVO;
import com.taskflow.common.dispatch.TaskDispatcher;
import com.taskflow.common.enums.ResultCode;
import com.taskflow.common.enums.TaskPriority;
import com.taskflow.common.enums.TaskStatus;
import com.taskflow.common.enums.TaskType;
import com.taskflow.common.exception.BizException;
import com.taskflow.common.result.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements TaskService {

    private final ObjectMapper objectMapper;

    private final TaskConverter taskConverter;

    private final TaskDispatcher taskDispatcher;

    public TaskServiceImpl(ObjectMapper objectMapper, TaskConverter taskConverter, TaskDispatcher taskDispatcher) {
        this.objectMapper = objectMapper;
        this.taskConverter = taskConverter;
        this.taskDispatcher = taskDispatcher;
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
        task.setBatchId(request.getBatchId());
        task.setRequestId(request.getRequestId());
        task.setTaskType(request.getTaskType());
        task.setParams(paramsJson);
        task.setStatus(TaskStatus.CREATED.getCode());
        task.setPriority(request.getPriority() == null ? TaskPriority.NORMAL.getCode() : request.getPriority());
        task.setMaxRetry(request.getMaxRetry()==null ? 0 : request.getMaxRetry());
        task.setRetryCount(0);

        //插入
        try {
            this.save(task);
        } catch (DuplicateKeyException e) {
            log.warn("唯一索引冲突，requestId={}",request.getRequestId());
            Task dup = getByRequestId(request.getRequestId());
            return new TaskSubmitResponse(dup.getId(), dup.getStatus(), true);
        }
        log.info("任务创建成功, taskId={}, requestId={}", task.getId(), task.getRequestId());

        // ⭐ 分发必须在【落库之后】调用。submit() 未加 @Transactional（save 立即提交），
        //    所以此处已经是"事务后"，不会产生"事务回滚但队列里已有任务"的幽灵任务。
        //    dispatch 是【非阻塞】的：桶满只记 WARN，任务保持 CREATED 交给补偿扫描。
        taskDispatcher.dispatch(task.getId(), task.getTaskType(), task.getPriority());

        return new TaskSubmitResponse(task.getId(), task.getStatus(), false);
    }

    @Override
    public TaskDetailVO getDetail(Long id) {
        Task task = this.getById(id);
        if(task == null) {
            log.warn("任务不存在，taskId={}", id);
            throw new BizException(ResultCode.TASK_NOT_FOUND);
        }
        log.info("查询成功, taskId={}", id);
        return taskConverter.toDetailVO(task);
    }

    @Override
    public PageResult<TaskListItemVO> queryPage(TaskPageQuery query) {
        //防止校验绕过和pageSize拖库
        if(query.getPageSize() != null && query.getPageSize() > 100 ) {
            log.warn("pageQuery pageSize={}过大, 已调整至默认值", query.getPageSize());
            query.setPageSize(100L);
        }
        Page<Task> mPage = new Page<>(query.getPageNo(), query.getPageSize());
        LambdaQueryWrapper<Task> queryWrapper = new LambdaQueryWrapper<Task>()
                .eq(query.getStatus() != null, Task::getStatus, query.getStatus())
                .eq(StringUtils.hasText(query.getTaskType()), Task::getTaskType, query.getTaskType())
                .ge(query.getStartTime() != null, Task::getCreateTime, query.getStartTime())
                .le(query.getEndTime() != null, Task::getCreateTime, query.getEndTime())
                .orderByDesc(Task::getCreateTime);
        Page<Task> taskPage = this.page(mPage, queryWrapper);
        List<TaskListItemVO> list = taskPage.getRecords().stream()
                .map(taskConverter::toListItemVO)
                .toList();
        return PageResult.of(query.getPageNo(), query.getPageSize(), taskPage.getTotal(), list);
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
