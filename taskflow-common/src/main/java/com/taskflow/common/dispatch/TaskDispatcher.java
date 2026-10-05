package com.taskflow.common.dispatch;

/**
 * 任务分发器 —— 调度（center）与执行（engine）之间的桥梁
 *
 * <p>center 只依赖这个接口，不关心底层是"内存队列"还是"消息队列"。
 * 具体实现由各执行引擎模块提供（LocalTaskDispatcher / MqTaskDispatcher），
 * Spring 按配置注入。
 *
 * <p>注意：这里刻意只传 taskId + taskType + priority（而不是 Task 实体），
 * 这样 common 不依赖 dao 模块，保持依赖方向干净。
 */
public interface TaskDispatcher {

    /**
     * 把一个已落库（status = CREATED）的任务分发出去。
     *
     * <p><b>调用方要求</b>：必须在【事务提交之后】调用，否则事务回滚会留下"幽灵任务"
     * （DB 里没有，队列里却有）。
     *
     * <p><b>实现方要求</b>：必须是【非阻塞】的。分发动作发生在 HTTP 线程上，
     * 一旦阻塞会耗尽 Web 容器线程池，拖垮整个服务；队列满时应当直接放弃并交由补偿扫描兜底。
     *
     * @param taskId   任务 ID
     * @param taskType 任务类型（用于路由到对应队列）
     * @param priority 任务优先级（数值越大越优先，用于路由到对应的优先级桶）
     * @return true = 已成功投递；false = 队列已满或状态被其他流程改走，任务保持原状态交由补偿扫描兜底
     */
    boolean dispatch(Long taskId, String taskType, int priority);
}
