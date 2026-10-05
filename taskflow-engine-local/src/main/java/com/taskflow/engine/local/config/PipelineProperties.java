package com.taskflow.engine.local.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地流水线配置（前缀 {@code taskflow.pipeline}）。
 *
 * <p>本模块是「库」，不提供 application.yml；默认值写在这里，由宿主（taskflow-center）按需覆盖。
 */
@Data
@ConfigurationProperties(prefix = "taskflow.pipeline")
public class PipelineProperties {

    // ---------------- 入口层（2 桶：消费者只有搬运工） ----------------

    /** VIP 入口桶容量 */
    private int pendingVipCapacity = 2000;

    /** 普通入口桶容量 */
    private int pendingNormalCapacity = 10000;

    // ---------------- 质检入口层（2 桶） ----------------

    /** VIP 质检入口桶容量 */
    private int bufferVipCapacity = 1000;

    /** 普通质检入口桶容量 */
    private int bufferNormalCapacity = 8000;

    // ---------------- 工作队列（要小：限制在途窗口、让 backlog 可见） ----------------

    /** 转写工作队列容量 */
    private int transcribeQueueCapacity = 500;

    /** 质检工作队列容量 */
    private int qcQueueCapacity = 500;

    // ---------------- 消费者线程数 ----------------

    /** 转写 worker 线程数 */
    private int transcribeThreads = 4;

    /** 质检 worker 线程数 */
    private int qcThreads = 4;

    // ---------------- 搬运工配额（每轮各取几个，保证 NORMAL 不被饿死） ----------------

    /** 每轮从 VIP 桶搬几个 */
    private int quotaVip = 10;

    /** 每轮从普通桶搬几个 */
    private int quotaNormal = 10;

    // ---------------- 监控 ----------------

    /** 消费者「停滞」判定阈值（毫秒）：队列有活却超过这么久没处理 */
    private long stallMillis = 60_000;

    /** 队列深度告警阈值（使用率 0~1） */
    private double queueWarnRatio = 0.8;

    // ---------------- 自测 ----------------

    /**
     * 骨架自测：启动时灌入若干假任务，验证「入口桶 → 搬运工 → 工作队列 → worker」的流转。
     *
     * <p>⚠️ 仅在阶段 1（业务逻辑未接入）时开启；接入真实转写/质检后请务必关闭。
     */
    private boolean selfTest = false;
}
