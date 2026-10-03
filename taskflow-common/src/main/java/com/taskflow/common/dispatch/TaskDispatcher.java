package com.taskflow.common.dispatch;

/**
 * 任务分发器 —— 调度（center）与执行（engine）之间的桥梁
 *
 * <p>center 只依赖这个接口，不关心底层是"内存队列"还是"消息队列"。
 * 具体实现由各执行引擎模块提供（LocalTaskDispatcher / MqTaskDispatcher），
 * Spring 按配置注入。
 *
 * <p>注意：这里刻意只传 taskId + taskType（而不是 Task 实体），
 * 这样 common 不依赖 dao 模块，保持依赖方向干净。
 */
public interface TaskDispatcher {

    /**
     * 把一个已落库（status = CREATED）的任务分发出去
     *
     * @param taskId   任务 ID
     * @param taskType 任务类型（用于路由到对应队列）
     */
    void dispatch(Long taskId, String taskType);
}
