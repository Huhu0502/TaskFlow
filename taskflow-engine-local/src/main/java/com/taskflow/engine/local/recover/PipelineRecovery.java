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
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动恢复 —— 「应用级（大面积）丢失」的唯一负责方，也是可靠性中<b>确定性最强</b>的一道防线。
 *
 * <p><b>为什么它不需要任何"判断"</b>：进程刚启动 ⇒ 内存队列必然为空 ⇒
 * 数据库里所有【非终态】任务"还在队列里"这件事，<b>逻辑上一定是假的</b>。
 * 所以不需要对账、不需要超时阈值，全量重建即可。
 *
 * <p>重建策略（统一"归一化"到可分发状态，再交给 dispatcher）：
 * <pre>
 *   CREATED      → 直接分发                    （未入队）
 *   QUEUED       → 重置为 CREATED → 分发        （内存已空，原 QUEUED 是假的）
 *   TRANSCRIBING → 重置为 CREATED → 分发        （执行被中断，必须重跑转写）
 *   TRANSCRIBED  → 直接投质检入口桶             （不用重跑转写）
 *   QC_ING       → 重置为 TRANSCRIBED → 投质检入口桶
 * </pre>
 *
 * <p>重复投递天然安全：每个阶段的第一步都是 CAS 抢任务，抢不到的直接放弃。
 *
 * <p>本类只负责「从 DB 重读并重投」这一步；完整的引擎重建
 * （停消费者 → 清空队列 → 重建 → 起消费者）由 {@code EngineRebuilder} 编排，
 * 启动时与低峰期定时共用同一条路径。
 */
@Slf4j
@Component
public class PipelineRecovery {

    /** 需要重建的非终态（终态任务不再处理） */
    private static final List<Integer> RECOVERABLE = List.of(
            TaskStatus.CREATED.getCode(),
            TaskStatus.QUEUED.getCode(),
            TaskStatus.TRANSCRIBING.getCode(),
            TaskStatus.TRANSCRIBED.getCode(),
            TaskStatus.QC_ING.getCode());

    private final TaskMapper taskMapper;

    private final LocalPipelineEngine engine;

    private final TaskDispatcher dispatcher;

    private final PipelineProperties props;

    public PipelineRecovery(TaskMapper taskMapper,
                            LocalPipelineEngine engine,
                            TaskDispatcher dispatcher,
                            PipelineProperties props) {
        this.taskMapper = taskMapper;
        this.engine = engine;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    /**
     * 从 DB 重读所有非终态任务并重新投递。
     *
     * @return 成功重投的条数（调用方用于日志与告警）
     */
    public int rebuild() {
        long startAt = System.currentTimeMillis();
        int scanned = 0;
        int redispatched = 0;
        int skipped = 0;

        // keyset 分页（id > lastId）：比 OFFSET 高效，且不受"重建过程中状态变化"影响
        long lastId = 0L;
        while (true) {
            List<Task> batch = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                    .gt(Task::getId, lastId)
                    .in(Task::getStatus, RECOVERABLE)
                    .orderByAsc(Task::getId)
                    .last("LIMIT " + props.getRecoveryBatchSize()));
            if (batch.isEmpty()) {
                break;
            }
            for (Task task : batch) {
                scanned++;
                if (recoverOne(task)) {
                    redispatched++;
                } else {
                    skipped++;
                }
            }
            lastId = batch.get(batch.size() - 1).getId();
        }

        if (scanned == 0) {
            log.info("重建扫描完成：无待重建任务");
        } else {
            log.warn("重建扫描完成：扫描 {} 条非终态任务，重投 {} 条，跳过 {} 条，耗时 {}ms",
                    scanned, redispatched, skipped, System.currentTimeMillis() - startAt);
        }
        return redispatched;
    }

    private boolean recoverOne(Task task) {
        Long id = task.getId();
        int status = task.getStatus();
        int priority = priorityOf(task);

        // ---- 质检侧 ----
        // 已完成转写 → 直接重投质检入口桶（不需要重跑转写）
        if (status == TaskStatus.TRANSCRIBED.getCode()) {
            return engine.enqueueForQc(id, priority);
        }
        // 质检被中断 → 回退到 TRANSCRIBED 再重投（质检可重跑）
        if (status == TaskStatus.QC_ING.getCode()) {
            if (taskMapper.casStatus(id, status, TaskStatus.TRANSCRIBED.getCode()) == 0) {
                return false;
            }
            return engine.enqueueForQc(id, priority);
        }

        // ---- 转写侧 ----
        // CREATED / QUEUED / TRANSCRIBING 统一归一化为 CREATED，再走正常分发
        if (status != TaskStatus.CREATED.getCode()
                && taskMapper.casStatus(id, status, TaskStatus.CREATED.getCode()) == 0) {
            return false;                       // 已被其他流程改走（例如已被取消）
        }
        return dispatcher.dispatch(id, task.getTaskType(), priority);
    }

    private int priorityOf(Task task) {
        return task.getPriority() == null ? TaskPriority.NORMAL.getCode() : task.getPriority();
    }
}
