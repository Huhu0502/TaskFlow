package com.taskflow.engine.local.recover;

import com.taskflow.common.enums.TaskStatus;
import com.taskflow.dao.mapper.TaskMapper;
import com.taskflow.engine.local.config.PipelineProperties;
import com.taskflow.engine.local.engine.LocalPipelineEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 引擎重建 —— 把「内存队列」整份丢掉，从 DB 重建一遍。
 *
 * <p><b>两个触发点共用同一段逻辑</b>：
 * <ul>
 *   <li><b>启动时</b>（{@link ApplicationRunner}）：<b>确定性</b>重建 —— 进程刚起，内存必空，
 *       所有非终态任务都是"假的"，所以不需要任何判断；</li>
 *   <li><b>低峰期</b>（{@code @Scheduled} cron）：兜住"运行中在途丢失"这种极小概率情况。
 *       用"定时低峰"绕过了"何时该重建"这个最难判断的问题 —— 天然限频、零运行时代价、
 *       只暂停消费（HTTP 提交/查询照常）。</li>
 * </ul>
 *
 * <p><b>重建四步</b>：
 * <pre>
 *   ① 停消费者（interrupt + join）
 *   ② 清空内存队列       ← 安全：队列里只是 taskId，DB 才是事实来源
 *   ③ 从 DB 重读非终态任务并重投
 *   ④ 起消费者
 * </pre>
 * 顺序很重要：先清空再重读，保证"清空瞬间并发提交的任务"也能被步骤 ③ 重新读到。
 *
 * <p><b>为什么重建前先对账</b>：重建会<b>掩盖问题</b>。如果每天都在丢任务，
 * 光靠重建只会让系统"看起来正常"。对账是唯一能留下证据的手段，所以先算一次并告警。
 */
@Slf4j
@Component
public class EngineRebuilder implements ApplicationRunner {

    private final PipelineRecovery recovery;

    private final LocalPipelineEngine engine;

    private final TaskMapper taskMapper;

    private final PipelineProperties props;

    /** 重建互斥：防止启动重建与定时重建叠加 */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    public EngineRebuilder(PipelineRecovery recovery,
                           LocalPipelineEngine engine,
                           TaskMapper taskMapper,
                           PipelineProperties props) {
        this.recovery = recovery;
        this.engine = engine;
        this.taskMapper = taskMapper;
        this.props = props;
    }

    /** 启动时：确定性重建（此时内存必然为空，对账没有意义，跳过） */
    @Override
    public void run(ApplicationArguments args) {
        rebuild("启动", false);
    }

    /** 低峰期：全量重建，兜住运行中的在途丢失（cron 填 "-" 可关闭） */
    @Scheduled(cron = "${taskflow.pipeline.rebuild-cron:0 0 3 * * ?}")
    public void scheduledRebuild() {
        rebuild("低峰期定时", true);
    }

    /**
     * 执行一次引擎重建。
     *
     * @param trigger       触发来源（仅用于日志）
     * @param withReconcile 是否在重建前做一次轻量对账（启动时无意义，跳过）
     * @return true = 本次真的执行了重建
     */
    public boolean rebuild(String trigger, boolean withReconcile) {
        if (!rebuilding.compareAndSet(false, true)) {
            log.warn("已有引擎重建在进行中，跳过本次（{}）", trigger);
            return false;
        }

        long startAt = System.currentTimeMillis();
        try {
            log.warn("=== 引擎重建开始（{}）===", trigger);

            if (withReconcile) {
                reconcile();
            }

            engine.pause();                          // ① 停消费者
            int cleared = engine.clearAllQueues();   // ② 清空内存队列（只是缓存）
            int requeued = recovery.rebuild();       // ③ 从 DB 重读并重投
            engine.resume();                         // ④ 起消费者

            log.warn("=== 引擎重建完成（{}）：清空 {} 个 taskId，重投 {} 条，耗时 {}ms ===",
                    trigger, cleared, requeued, System.currentTimeMillis() - startAt);
            return true;
        } catch (Exception e) {
            log.error("引擎重建失败（{}）", trigger, e);
            return false;
        } finally {
            // ⭐ 无论成功失败，都必须把消费者拉起来，否则引擎就"死"在那了
            engine.resume();
            rebuilding.set(false);
        }
    }

    /**
     * 重建前轻量对账。
     *
     * <p>不变量：<b>DB 里 QUEUED 的数量 ≤ 内存队列元素总数</b>。
     * 任何 QUEUED 任务必然在内存的某个队列里（入口桶 / 搬运工手里 / 工作队列 / worker 手里）。
     *
     * <p>为什么<b>不</b>把「消费者数」当成在途容忍值：在途窗口只是「take 到 CAS 之间」的
     * 微秒级区间，某一瞬间真落在窗口里的任务几乎为 0；而把它算进去会把容忍值撑到「消费者数」，
     * 从而丧失对小规模丢失的检测能力——得不偿失（偶尔的误报只是一条 ERROR 日志，无副作用）。
     *
     * <p>误差方向只会是「内存偏多」（重复投递、已失效的 taskId），
     * 所以这个判断<b>只会漏报、不会误报</b>。
     */
    private void reconcile() {
        long dbQueued = taskMapper.countByStatus(TaskStatus.QUEUED.getCode());
        int inMemory = engine.totalQueueSize();
        long allowed = (long) inMemory + props.getReconcileTolerance();

        if (dbQueued > allowed) {
            log.error("⚠️ 对账异常：DB 中 QUEUED={} 条 > 内存队列 {} + 容忍 {} ⇒ 确认存在任务丢失，约 {} 条",
                    dbQueued, inMemory, props.getReconcileTolerance(), dbQueued - inMemory);
        } else {
            log.info("对账正常：DB 中 QUEUED={} 条（内存队列 {} 条，在途上限 {}）",
                    dbQueued, inMemory, engine.consumerCount());
        }
    }
}
