package com.taskflow.common.dispatch;

import java.time.LocalDateTime;

/**
 * 执行引擎的「运行态视图」—— 供调度侧（center）判断系统是否繁忙、任务是否疑似丢失。
 *
 * <p>为什么单独一个接口（而不是塞进 {@link TaskDispatcher}）：
 * {@code TaskDispatcher} 管的是「投递动作」，这里管的是「状态判断」，职责不同。
 * 和 {@code TaskDispatcher} 一样，center 只依赖 common 里的接口，不依赖具体引擎实现。
 */
public interface EngineStatus {

    /**
     * 判断一个「未结束」的任务是否<b>疑似丢失</b>。
     *
     * <p>判定 = <b>长时间无进展</b> 且 <b>引擎并不繁忙</b>。
     *
     * <p>为什么不能只按时间判断：系统积压时，任务在队列里正常排队同样会"很久没动"，
     * 只看时间会把它误判成丢失（进而诱导用户重试，把队列塞满重复任务）。
     * 加上"引擎不繁忙"这个条件，正好把积压导致的误报滤掉：
     * <pre>
     *   队列很空 + 任务很久没动  → 大概率真丢了   → 疑似丢失 = true
     *   队列很满 + 任务很久没动  → 只是正常排队   → 疑似丢失 = false
     * </pre>
     *
     * @param lastUpdateTime 任务最后一次状态变更时间，null 视为无法判断
     */
    boolean isSuspectedLost(LocalDateTime lastUpdateTime);

    /** 内存队列总使用率 0~1（0 = 完全空闲） */
    double queueUsage();
}
