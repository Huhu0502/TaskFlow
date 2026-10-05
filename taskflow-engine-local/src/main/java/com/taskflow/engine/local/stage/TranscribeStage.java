package com.taskflow.engine.local.stage;

import com.taskflow.common.enums.TaskPriority;
import com.taskflow.common.enums.TaskStatus;
import com.taskflow.dao.entity.Task;
import com.taskflow.dao.mapper.TaskMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiPredicate;

/**
 * 转写阶段。状态流转：{@code QUEUED → TRANSCRIBING → TRANSCRIBED}（失败则 {@code → FAILED}）。
 *
 * <p><b>为什么第一步一定要 CAS</b>：worker 从队列里拿到的只是 taskId，
 * 而队列里可能有重复（补偿重投、启动恢复重投）。CAS 是唯一的"抢锁"手段：
 * 抢到的人处理，没抢到的直接放弃。这就是"至少一次投递 + 幂等消费"的落点。
 *
 * <p>与引擎之间用 {@link BiPredicate} 回调交接，避免 Stage 与引擎互相依赖。
 */
@Slf4j
public class TranscribeStage {

    private final TaskMapper taskMapper;

    /** 交给下一阶段的方式：入参 (taskId, priority)，返回是否入队成功 */
    private final BiPredicate<Long, Integer> enqueueForQc;

    public TranscribeStage(TaskMapper taskMapper, BiPredicate<Long, Integer> enqueueForQc) {
        this.taskMapper = taskMapper;
        this.enqueueForQc = enqueueForQc;
    }

    public void handle(Long taskId) {
        // ① 抢任务：QUEUED -> TRANSCRIBING
        if (taskMapper.casStatus(taskId,
                TaskStatus.QUEUED.getCode(), TaskStatus.TRANSCRIBING.getCode()) == 0) {
            log.debug("抢转写任务失败（重复投递 / 已取消 / 已被处理），放弃: {}", taskId);
            return;
        }
        log.debug("状态流转: taskId={} {} -> {}", taskId, TaskStatus.QUEUED, TaskStatus.TRANSCRIBING);

        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务 {} 抢到后却查不到（可能被物理删除）", taskId);
            return;
        }

        // ② 干活
        String transcript;
        try {
            transcript = doTranscribe(task);
        } catch (Exception e) {
            log.error("转写失败, taskId={}", taskId, e);
            taskMapper.casFinish(taskId, TaskStatus.TRANSCRIBING.getCode(),
                    TaskStatus.FAILED.getCode(), null, MockWork.truncate(e.getMessage()));
            log.debug("状态流转: taskId={} {} -> {}", taskId, TaskStatus.TRANSCRIBING, TaskStatus.FAILED);
            return;
        }

        // ③ 收尾：写结果 + 推进 TRANSCRIBED
        if (taskMapper.casFinish(taskId, TaskStatus.TRANSCRIBING.getCode(),
                TaskStatus.TRANSCRIBED.getCode(), transcript, null) == 0) {
            log.warn("转写完成但状态推进失败（可能已被取消 / 超时重置），本次结果作废: {}", taskId);
            return;
        }
        log.debug("状态流转: taskId={} {} -> {}", taskId, TaskStatus.TRANSCRIBING, TaskStatus.TRANSCRIBED);

        // ④ 交接给质检阶段（非阻塞：桶满就保持 TRANSCRIBED，交给补偿扫描）
        int priority = task.getPriority() == null ? TaskPriority.NORMAL.getCode() : task.getPriority();
        if (!enqueueForQc.test(taskId, priority)) {
            log.warn("质检入口桶已满，任务 {} 保持 TRANSCRIBED，交给补偿扫描", taskId);
        }
    }

    /** 真实项目里这里调用转写服务；当前为 mock（可用 requestId 前缀控制行为） */
    private String doTranscribe(Task task) {
        if (MockWork.isTranscribeFail(task)) {
            throw new IllegalStateException("模拟转写服务异常");
        }
        MockWork.sleepQuietly(MockWork.isSlow(task) ? 3000L : 200L);
        return MockWork.mockTranscript(task);
    }
}
