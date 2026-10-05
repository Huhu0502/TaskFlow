package com.taskflow.engine.local.stage;

import com.taskflow.common.enums.TaskStatus;
import com.taskflow.dao.entity.Task;
import com.taskflow.dao.mapper.TaskMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 质检阶段。状态流转：{@code TRANSCRIBED → QC_ING → SUCCESS}（失败则 {@code → FAILED}）。
 *
 * <p>与转写阶段同理，第一步必须 CAS 抢任务；不同的是它没有下游交接。
 *
 * <p>关于 {@code result}：质检收尾时<b>不覆盖</b> {@code result}，保留上游的转写产物
 * （真实项目里各阶段产物应落到独立的阶段结果表；当前只有一个 result 列，选择保留上游产物，
 * 质检结论由最终状态表达）。
 */
@Slf4j
public class QcStage {

    private final TaskMapper taskMapper;

    public QcStage(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public void handle(Long taskId) {
        // ① 抢任务：TRANSCRIBED -> QC_ING
        if (taskMapper.casStatus(taskId,
                TaskStatus.TRANSCRIBED.getCode(), TaskStatus.QC_ING.getCode()) == 0) {
            log.debug("抢质检任务失败（重复投递 / 已取消 / 已被处理），放弃: {}", taskId);
            return;
        }
        log.debug("状态流转: taskId={} {} -> {}", taskId, TaskStatus.TRANSCRIBED, TaskStatus.QC_ING);

        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务 {} 抢到后却查不到（可能被物理删除）", taskId);
            return;
        }

        // ② 干活 + 收尾
        try {
            doQc(task);
            taskMapper.casFinish(taskId, TaskStatus.QC_ING.getCode(),
                    TaskStatus.SUCCESS.getCode(), null, null);
            log.debug("状态流转: taskId={} {} -> {}", taskId, TaskStatus.QC_ING, TaskStatus.SUCCESS);
            log.info("任务完成: taskId={}, requestId={}", taskId, task.getRequestId());
        } catch (Exception e) {
            log.error("质检失败, taskId={}", taskId, e);
            taskMapper.casFinish(taskId, TaskStatus.QC_ING.getCode(),
                    TaskStatus.FAILED.getCode(), null, MockWork.truncate(e.getMessage()));
            log.debug("状态流转: taskId={} {} -> {}", taskId, TaskStatus.QC_ING, TaskStatus.FAILED);
        }
    }

    /** 真实项目里这里调用质检服务；当前为 mock（可用 requestId 前缀控制行为） */
    private void doQc(Task task) {
        if (MockWork.isQcFail(task)) {
            throw new IllegalStateException("模拟质检服务异常");
        }
        MockWork.sleepQuietly(MockWork.isSlow(task) ? 2000L : 50L);
    }
}
