package com.taskflow.engine.local.monitor;

import com.taskflow.engine.local.config.PipelineProperties;
import com.taskflow.engine.local.consumer.ManagedConsumer;
import com.taskflow.engine.local.engine.LocalPipelineEngine;
import com.taskflow.engine.local.queue.BoundedTaskQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 队列 / 消费者可观测性监控（<b>只告警，不自动恢复</b>）。
 *
 * <p>为什么必须有：DB 状态是「静止的」，它看不出「有没有人在干活」——
 * 消费者线程若死掉或卡死，任务状态仍然是 QUEUED，扫表发现不了。
 * <ul>
 *   <li><b>队列深度</b>：持续高位 = 下游处理不过来或已停摆；</li>
 *   <li><b>消费者活性</b>：{@code 待处理量 > 0 && 超过 stallMillis 没处理任务} = 停滞（能发现死锁、无限阻塞这类「没有异常」的故障）。</li>
 * </ul>
 */
@Slf4j
@Component
public class QueueMonitor {

    private final LocalPipelineEngine engine;
    private final PipelineProperties props;

    public QueueMonitor(LocalPipelineEngine engine, PipelineProperties props) {
        this.engine = engine;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${taskflow.pipeline.monitor-interval-millis:10000}")
    public void monitor() {
        monitorQueueDepth();
        monitorConsumerLiveness();
    }

    private void monitorQueueDepth() {
        for (BoundedTaskQueue q : engine.queues()) {
            if (q.usageRatio() >= props.getQueueWarnRatio()) {
                log.warn("⚠️ 队列 {} 使用率过高: {}", q.getName(), percent(q.usageRatio()));
            }
        }
    }

    private void monitorConsumerLiveness() {
        for (ManagedConsumer c : engine.consumers()) {
            if (!c.isAlive()) {
                log.error("⚠️ 消费者 {} 已死亡（待处理 {}, 空闲 {}s）—— 需要人工介入或按策略重启",
                        c.getName(), c.backlog(), c.idleMillis() / 1000);
                continue;
            }
            // 「有活却不干」= 停滞（死锁 / 无限阻塞 / 逻辑 bug）
            if (c.backlog() > 0 && c.idleMillis() > props.getStallMillis()) {
                log.error("⚠️ 消费者 {} 疑似停滞: 待处理 {}，已 {}s 未处理任务",
                        c.getName(), c.backlog(), c.idleMillis() / 1000);
            }
        }
    }

    private String percent(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }
}
