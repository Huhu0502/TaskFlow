package com.taskflow.center.controller;

import com.taskflow.center.dto.TaskPageQuery;
import com.taskflow.center.dto.TaskSubmitRequest;
import com.taskflow.center.vo.TaskListItemVO;
import com.taskflow.center.vo.TaskSubmitResponse;
import com.taskflow.center.service.TaskService;
import com.taskflow.center.vo.TaskDetailVO;
import com.taskflow.common.result.PageResult;
import com.taskflow.common.result.Result;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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

    /**
     * 查询任务详情
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Result<TaskDetailVO> getDetail(@PathVariable("id") Long id) {
        log.info("查询任务详情, taskId={}", id);
        return Result.ok(taskService.getDetail(id));
    }

    /**
     * 重试一个「疑似丢失」的任务
     *
     * <p>幂等性由 service 里的 CAS 保证：并发点两次只会成功一次，
     * 第二次会返回"任务状态刚刚发生变化"。
     *
     * @param id 任务 ID
     */
    @PostMapping("/{id}/retry")
    public Result<Void> retry(@PathVariable("id") Long id) {
        log.warn("用户请求重试任务, taskId={}", id);
        taskService.retry(id);
        return Result.ok();
    }

    /**
     * 分页查询任务
     * @param query
     * @return
     */
    @GetMapping("/page")
    public Result<PageResult<TaskListItemVO>> pageQuery(@Valid TaskPageQuery query) {
        log.info("分页查询任务，pageNo={}, pageSize={}, status={}, taskType={}, startTime={}, endTime={}",
                query.getPageNo(), query.getPageSize(), query.getStatus(), query.getTaskType(), query.getStartTime(), query.getEndTime());
        return Result.ok(taskService.queryPage(query));
    }
}
