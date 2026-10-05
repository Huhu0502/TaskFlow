package com.taskflow.engine.local.engine;

import com.taskflow.common.enums.TaskPriority;
import com.taskflow.dao.mapper.TaskMapper;
import com.taskflow.engine.local.config.PipelineProperties;
import com.taskflow.engine.local.consumer.ManagedConsumer;
import com.taskflow.engine.local.queue.BoundedTaskQueue;
import com.taskflow.engine.local.stage.QcStage;
import com.taskflow.engine.local.stage.TranscribeStage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地内存多级流水线引擎（SEDA 风格）。
 *
 * <pre>
 *  提交 ──offer──▶ pendingVip / pendingNormal        （入口层，消费者只有搬运工）
 *                        │
 *                    【搬运工 A】按配额取（VIP 优先，NORMAL 保底）
 *                        │ put（可阻塞）
 *                        ▼
 *                 transcribeQueue（小 FIFO）
 *                        │
 *                  转写 worker × N   ──CAS QUEUED→TRANSCRIBING→TRANSCRIBED──▶ 存 result
 *                        │ offer
 *                        ▼
 *                 bufferVip / bufferNormal            （质检入口层）
 *                        │
 *                    【搬运工 B】按配额取
 *                        │ put
 *                        ▼
 *                    qcQueue（小 FIFO）
 *                        │
 *                  质检 worker × N   ──CAS TRANSCRIBED→QC_ING→SUCCESS/FAILED
 * </pre>
 *
 * <p><b>设计要点</b>：
 * <ul>
 *   <li>队列元素只有 taskId，任务数据以 DB 为事实来源；</li>
 *   <li>优先级桶的消费者只有搬运工（单线程），所以「按配额取」天然精确、无需加锁；</li>
 *   <li>worker 只面对一个 FIFO 工作队列，不需要懂优先级；</li>
 *   <li>搬运工「填工作队列的顺序」就是「执行顺序」；</li>
 *   <li>HTTP 线程只 {@code offer}（绝不阻塞），阻塞式 {@code put} 只出现在搬运工线程里；</li>
 *   <li>状态推进一律走 CAS，所以「重复投递 / 重复消费」永远不会导致重复执行。</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalPipelineEngine {

    /** 搬运工在两个源桶都空时的阻塞等待时长（毫秒），避免空转 */
    private static final long TRANSFER_WAIT_MILLIS = 100L;

    private final PipelineProperties props;

    // ---------------- 队列 ----------------

    private final BoundedTaskQueue pendingVip;
    private final BoundedTaskQueue pendingNormal;

    private final BoundedTaskQueue transcribeQueue;
    private final BoundedTaskQueue qcQueue;

    private final BoundedTaskQueue bufferVip;
    private final BoundedTaskQueue bufferNormal;

    /** 全部队列（统一监控 / 重建用） */
    private final List<BoundedTaskQueue> allQueues;

    /** 全部消费者 */
    private final List<ManagedConsumer> consumers = new CopyOnWriteArrayList<>();

    /** 消费者是否已创建（创建一次即可，之后由 pause/resume 控制启停） */
    private boolean consumersCreated = false;

    // ---------------- 阶段 ----------------

    private final TranscribeStage transcribeStage;

    private final QcStage qcStage;

    public LocalPipelineEngine(PipelineProperties props, TaskMapper taskMapper) {
        this.props = props;

        // ① 队列（必须在创建 Stage 之前完成，因为 Stage 的回调会用到这些队列）
        this.pendingVip = new BoundedTaskQueue("pendingVip", props.getPendingVipCapacity());
        this.pendingNormal = new BoundedTaskQueue("pendingNormal", props.getPendingNormalCapacity());

        this.transcribeQueue = new BoundedTaskQueue("transcribeQueue", props.getTranscribeQueueCapacity());
        this.qcQueue = new BoundedTaskQueue("qcQueue", props.getQcQueueCapacity());

        this.bufferVip = new BoundedTaskQueue("bufferVip", props.getBufferVipCapacity());
        this.bufferNormal = new BoundedTaskQueue("bufferNormal", props.getBufferNormalCapacity());

        List<BoundedTaskQueue> queues = new ArrayList<>(6);
        Collections.addAll(queues,
                pendingVip, pendingNormal,
                transcribeQueue,
                bufferVip, bufferNormal,
                qcQueue);
        this.allQueues = Collections.unmodifiableList(queues);

        // ② 阶段：转写阶段通过回调交接给质检入口层，避免与引擎互相依赖
        this.transcribeStage = new TranscribeStage(taskMapper, this::enqueueForQc);
        this.qcStage = new QcStage(taskMapper);
    }

    // ================================================================
    // 生命周期
    // ================================================================

    @PostConstruct
    public void start() {
        log.info("本地引擎启动中（队列: {} {} {} {} {} {}）",
                pendingVip, pendingNormal, transcribeQueue, bufferVip, bufferNormal, qcQueue);

        createConsumers();
        resume();

        log.info("本地引擎已启动，消费者 {} 个：{}", consumers.size(), consumerNames());
    }

    @PreDestroy
    public void stop() {
        log.info("本地引擎停止中...");
        pause();
        log.info("本地引擎已停止");
    }

    /** 创建全部消费者（只执行一次；启停由 {@link #pause()} / {@link #resume()} 控制） */
    private synchronized void createConsumers() {
        if (consumersCreated) {
            return;
        }

        // 转写 worker：从工作队列取 → 交给转写阶段
        for (int i = 1; i <= props.getTranscribeThreads(); i++) {
            addConsumer(new ManagedConsumer("transcribe-worker-" + i,
                    transcribeQueue::takeOrNull,
                    transcribeStage::handle)
                    .watching(transcribeQueue::size));
        }

        // 质检 worker
        for (int i = 1; i <= props.getQcThreads(); i++) {
            addConsumer(new ManagedConsumer("qc-worker-" + i,
                    qcQueue::takeOrNull,
                    qcStage::handle)
                    .watching(qcQueue::size));
        }

        // 搬运工 A：入口桶 → 转写工作队列
        addConsumer(new ManagedConsumer("transfer-A",
                self -> transferLoop(self, pendingVip, pendingNormal, transcribeQueue))
                .watching(() -> pendingVip.size() + pendingNormal.size()));

        // 搬运工 B：质检入口桶 → 质检工作队列
        addConsumer(new ManagedConsumer("transfer-B",
                self -> transferLoop(self, bufferVip, bufferNormal, qcQueue))
                .watching(() -> bufferVip.size() + bufferNormal.size()));

        consumersCreated = true;
    }

    /**
     * 暂停全部消费者：中断 + 等待退出。
     *
     * <p>用于「引擎重建」：必须先停掉消费者，才能安全地清空队列、从 DB 重建。
     */
    public void pause() {
        consumers.forEach(c -> c.interruptAndJoin(3000L));
    }

    /** 恢复全部消费者（幂等：已在跑的不会被重复启动） */
    public void resume() {
        consumers.forEach(ManagedConsumer::start);
    }

    // ================================================================
    // 对外接口
    // ================================================================

    /**
     * 提交任务进入流水线（<b>非阻塞</b>，给 HTTP 线程用）。
     *
     * @return true = 已入队；false = 桶已满，调用方应让任务保持 CREATED，交给补偿扫描兜底
     */
    public boolean submit(Long taskId, int priority) {
        return route(taskId, priority, pendingVip, pendingNormal);
    }

    /**
     * 转写完成 → 投递到质检入口层（<b>非阻塞</b>，给转写 worker 用）。
     *
     * @return true = 已入队；false = 桶已满，任务保持 TRANSCRIBED，交给补偿扫描兜底
     */
    public boolean enqueueForQc(Long taskId, int priority) {
        return route(taskId, priority, bufferVip, bufferNormal);
    }

    /** 所有队列（只读），供监控 / 重建使用 */
    public List<BoundedTaskQueue> queues() {
        return allQueues;
    }

    /** 所有消费者（只读），供监控使用 */
    public List<ManagedConsumer> consumers() {
        return Collections.unmodifiableList(consumers);
    }

    /** 队列里待执行的 taskId 总数（用于「数量对账」） */
    public int totalQueueSize() {
        int total = 0;
        for (BoundedTaskQueue q : allQueues) {
            total += q.size();
        }
        return total;
    }

    /** 全部队列容量之和 */
    public int totalQueueCapacity() {
        int total = 0;
        for (BoundedTaskQueue q : allQueues) {
            total += q.capacity();
        }
        return total;
    }

    /** 内存队列总使用率 0~1（供「系统是否繁忙」判断与监控） */
    public double queueUsage() {
        int capacity = totalQueueCapacity();
        return capacity == 0 ? 0D : (double) totalQueueSize() / capacity;
    }

    /** 消费者数量（即「在途任务」的上限：每人手里最多 1 个） */
    public int consumerCount() {
        return consumers.size();
    }

    /**
     * 清空全部内存队列，返回被清掉的 taskId 数量。
     *
     * <p>队列元素只是 taskId，DB 才是事实来源，所以清空不会丢数据 ——
     * 重建时会从 DB 重新投递回来。仅供「引擎重建」使用。
     */
    public int clearAllQueues() {
        int cleared = totalQueueSize();
        allQueues.forEach(BoundedTaskQueue::clear);
        return cleared;
    }

    // ================================================================
    // 内部：路由 / 搬运
    // ================================================================

    private boolean route(Long taskId, int priority, BoundedTaskQueue vip, BoundedTaskQueue normal) {
        return TaskPriority.isVip(priority) ? vip.offer(taskId) : normal.offer(taskId);
    }

    private void addConsumer(ManagedConsumer consumer) {
        consumers.add(consumer);
    }

    /**
     * 搬运工循环：按配额从两个源桶取任务，投到下游工作队列。
     *
     * <p>配额保证 NORMAL 不会被 VIP 饿死：每轮先取 {@code quotaVip} 个 VIP，再取 {@code quotaNormal} 个 NORMAL。
     * 这是「单线程顺序决策」，所以配额天然精确，不需要任何锁。
     */
    private void transferLoop(ManagedConsumer self,
                              BoundedTaskQueue vip,
                              BoundedTaskQueue normal,
                              BoundedTaskQueue dest) {
        while (!Thread.currentThread().isInterrupted()) {
            int moved = 0;

            for (int i = 0; i < props.getQuotaVip(); i++) {
                if (!drainOne(vip, dest)) {
                    break;
                }
                moved++;
            }
            for (int i = 0; i < props.getQuotaNormal(); i++) {
                if (!drainOne(normal, dest)) {
                    break;
                }
                moved++;
            }

            if (moved > 0) {
                self.markActive();
                continue;
            }

            // 两个源桶都空 → 阻塞等待（VIP 优先），避免空转烧 CPU
            Long taskId = vip.poll(TRANSFER_WAIT_MILLIS);
            if (taskId == null) {
                taskId = normal.poll(TRANSFER_WAIT_MILLIS);
            }
            if (taskId == null) {
                continue;
            }
            // 搬运工允许阻塞：下游满 → 阻塞 → 源桶自然积压 → 背压逐级上传
            dest.put(taskId);
            self.markActive();
        }
    }

    /** 从源队列非阻塞取一个、阻塞放进目标队列。返回 false = 源队列已空。 */
    private boolean drainOne(BoundedTaskQueue src, BoundedTaskQueue dest) {
        Long taskId = src.pollNow();
        if (taskId == null) {
            return false;
        }
        dest.put(taskId);
        return true;
    }

    private String consumerNames() {
        StringBuilder sb = new StringBuilder();
        for (ManagedConsumer c : consumers) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(c.getName());
        }
        return sb.toString();
    }
}
