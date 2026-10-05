package com.taskflow.engine.local.recover;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskflow.common.dispatch.TaskDispatcher;
import com.taskflow.common.enums.TaskPriority;
import com.taskflow.common.enums.TaskStatus;
import com.taskflow.dao.entity.Task;
import com.taskflow.dao.mapper.TaskMapper;
import com.taskflow.engine.local.config.PipelineProperties;
import com.taskflow.engine.local.engine.LocalPipelineEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 补偿扫描 —— 处理「运行中、且<b>可以明确判断</b>」的停滞任务。
 *
 * <p>只扫两种状态，因为它们没有歧义：
 * <ul>
 *   <li>{@code CREATED} 超时：只可能是"offer 失败"或"CAS 后进程中断"，必然没进队列；</li>
 *   <li>{@code TRANSCRIBED} 超时：只可能是"投质检入口桶失败"，必然不在质检队列里。</li>
 * </ul>
 *
 * <p><b>刻意不扫 {@code QUEUED}</b>：它同时表示"正在正常排队"和"可能已丢失"，
 * 系统积压时扫它会造成大面积误判，把队列塞满重复任务（越忙越乱）。
 * QUEUED 的兜底交给：启动恢复（确定性）+ 用户主动重试（产品层）。
 *
 * <p>扫描以 {@code update_time} 为「停滞时长」依据，并用 DB 时钟（{@code NOW()}）比较，
 * 避免应用与数据库时区不一致带来的误判。
 */
@Slf4j
@Component
public class CompensateJob {

    private final TaskMapper taskMapper;

    private final LocalPipelineEngine engine;

    private final TaskDispatcher dispatcher;

    private final PipelineProperties props;

    public CompensateJob(TaskMapper taskMapper,
                         LocalPipelineEngine engine,
                         TaskDispatcher dispatcher,
                         PipelineProperties props) {
        this.taskMapper = taskMapper;
        this.engine = engine;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${taskflow.pipeline.compensate-interval-millis:30000}")
    public void compensate() {
        try {
            compensateCreated();
            compensateTranscribed();
        } catch (Exception e) {
            // 定时任务自己兜住异常：不让一轮失败影响后续轮次
            log.error("补偿扫描异常（不影响下一轮）", e);
        }
    }

    /** CREATED 停留过久 → 重新投递转写入口桶 */
    private void compensateCreated() {
        List<Task> tasks = selectStuck(TaskStatus.CREATED, props.getCreatedStuckSeconds());
        if (tasks.isEmpty()) {
            return;
        }
        int ok = 0;
        for (Task task : tasks) {
            if (dispatcher.dispatch(task.getId(), task.getTaskType(), priorityOf(task))) {
                ok++;
            }
        }
        log.warn("补偿 CREATED：扫描 {} 条，重投 {} 条", tasks.size(), ok);
    }

    /** TRANSCRIBED 停留过久 → 重新投递质检入口桶（无需重跑转写） */
    private void compensateTranscribed() {
        List<Task> tasks = selectStuck(TaskStatus.TRANSCRIBED, props.getTranscribedStuckSeconds());
        if (tasks.isEmpty()) {
            return;
        }
        int ok = 0;
        for (Task task : tasks) {
            if (engine.enqueueForQc(task.getId(), priorityOf(task))) {
                // ⭐ 状态不变（仍是 TRANSCRIBED），但必须刷新 update_time：
                //    否则下一轮补偿会把同一条又扫出来，反复投递。
                taskMapper.touch(task.getId());
                ok++;
            }
        }
        log.warn("补偿 TRANSCRIBED：扫描 {} 条，重投 {} 条", tasks.size(), ok);
    }

    private List<Task> selectStuck(TaskStatus status, long stuckSeconds) {
        return taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getStatus, status.getCode())
                // 用 DB 时钟比较，避免应用与数据库时区不一致
                .apply("update_time < DATE_SUB(NOW(), INTERVAL {0} SECOND)", stuckSeconds)
                .orderByDesc(Task::getPriority)
                .orderByAsc(Task::getCreateTime)
                .last("LIMIT " + props.getCompensateBatchSize()));
    }

    private int priorityOf(Task task) {
        return task.getPriority() == null ? TaskPriority.NORMAL.getCode() : task.getPriority();
    }
}
