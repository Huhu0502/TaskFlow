package com.taskflow.center.service;


import com.taskflow.center.dto.TaskPageQuery;
import com.taskflow.center.dto.TaskSubmitRequest;
import com.taskflow.center.vo.TaskListItemVO;
import com.taskflow.center.vo.TaskSubmitResponse;
import com.taskflow.center.vo.TaskDetailVO;
import com.taskflow.common.result.PageResult;

public interface TaskService {
    /**
     * 任务提交
     * @param request 任务参数
     * @return 提交结果
     */
    TaskSubmitResponse submit(TaskSubmitRequest request);

    /**
     * 查询任务详情
     * @param id
     * @return
     */
    TaskDetailVO getDetail(Long id);

    /**
     * 分页查询任务
     * @param query
     * @return
     */
    PageResult<TaskListItemVO> queryPage(TaskPageQuery query);
}
