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

    /**
     * 重试一个「疑似丢失」的任务 —— 产品层的可靠性兜底手段。
     *
     * <p>只在【长时间无进展 且 引擎并不繁忙】时才允许；
     * 任务正常排队时会被拒绝，避免用户反复点重试把队列塞满重复任务。
     *
     * @param id 任务 ID
     */
    void retry(Long id);
}
