package com.taskflow.engine.local.dispatcher;

import com.taskflow.common.dispatch.TaskDispatcher;
import com.taskflow.common.enums.TaskStatus;
import com.taskflow.dao.mapper.TaskMapper;
import com.taskflow.engine.local.engine.LocalPipelineEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 本地引擎的分发实现：把任务投进内存优先级桶。
 *
 * <p><b>它很薄</b>：不查库（优先级由调用方传入）、不做业务、不阻塞。
 *
 * <p><b>为什么不能阻塞</b>：{@code dispatch} 跑在 HTTP 线程上，
 * 桶满时必须立刻放弃（任务保持 CREATED，交给补偿扫描），绝不能 {@code put} 阻塞，
 * 否则会耗尽 Web 容器线程池、把整个服务拖垮。
 *
 * <p><b>顺序为什么是「先占状态、再入队」</b>：
 * <pre>
 * ❌ 先 offer 再改状态：
 *      offer 后任务立刻可被 worker 取走 → 此时 DB 状态还是 CREATED
 *      → worker 的 CAS(QUEUED→TRANSCRIBING) 返回 0 → worker 放弃 → 任务静默丢失
 * ✅ 先 CAS(CREATED→QUEUED) 再 offer：
 *      状态先就位，无论 worker 何时取走都能抢到；offer 失败再回退，保持不变量
 * </pre>
 */
@Slf4j
@Component
public class LocalTaskDispatcher implements TaskDispatcher {

    private final LocalPipelineEngine engine;

    private final TaskMapper taskMapper;

    public LocalTaskDispatcher(LocalPipelineEngine engine, TaskMapper taskMapper) {
        this.engine = engine;
        this.taskMapper = taskMapper;
    }

    @Override
    public void dispatch(Long taskId, String taskType, int priority) {
        // ① 先占状态：CREATED -> QUEUED
        //    CAS 返回 0 说明已被其他流程改走（如已被取消），直接放弃分发。
        int claimed = taskMapper.casStatus(taskId,
                TaskStatus.CREATED.getCode(), TaskStatus.QUEUED.getCode());
        if (claimed == 0) {
            log.debug("任务 {} 状态推进失败（已被其他流程改走），跳过分发", taskId);
            return;
        }

        // ② 非阻塞入队（offer）：桶满只返回 false，绝不阻塞 HTTP 线程
        if (engine.submit(taskId, priority)) {
            log.info("任务已入队, taskId={}, taskType={}, priority={}", taskId, taskType, priority);
            return;
        }

        // ③ 桶满 → 回退状态，保持「QUEUED ⇔ 已在内存队列里」这个不变量，
        //    任务回到 CREATED 后由补偿扫描下一轮再投递。
        int rolledBack = taskMapper.casStatus(taskId,
                TaskStatus.QUEUED.getCode(), TaskStatus.CREATED.getCode());
        log.warn("入口桶已满，任务 {} 已回退为 CREATED（回退={}），等待补偿投递",
                taskId, rolledBack == 1 ? "成功" : "跳过(状态已被worker改走)");
    }
}
