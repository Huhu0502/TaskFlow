package com.taskflow.engine.local.queue;

import lombok.Getter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 有界任务队列：队列元素是 <b>taskId（Long）</b>，任务数据始终以 DB 为事实来源。
 *
 * <p><b>中断语义（重要）</b>：
 * <ul>
 *   <li>{@link #put}：阻塞入队。中断视为「异常情况」（给搬运工用），恢复中断标志并抛运行时异常。</li>
 *   <li>{@link #offer}：非阻塞入队，满则返回 false（给 HTTP 提交 / 阶段交接用，绝不阻塞用户线程）。</li>
 *   <li>{@link #takeOrNull}：阻塞出队。被中断时恢复标志并返回 {@code null}，调用方 {@code if (id == null) break;} 即可优雅退出。</li>
 * </ul>
 *
 * <p>{@code takeOrNull} 返回 null 无歧义：{@link ArrayBlockingQueue} 不允许 null 元素，
 * 所以 null 只可能表示「被中断」。
 */
public class BoundedTaskQueue {

    @Getter
    private final String name;

    private final int capacity;

    private final BlockingQueue<Long> queue;

    public BoundedTaskQueue(String name, int capacity) {
        this.name = name;
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** 阻塞入队（给搬运工用）。中断 → 恢复标志 + 抛运行时异常。 */
    public void put(Long taskId) {
        try {
            queue.put(taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " put 被中断", e);
        }
    }

    /** 非阻塞入队（给 HTTP 提交 / 阶段交接用）。满了返回 false，由调用方交给补偿兜底。 */
    public boolean offer(Long taskId) {
        return queue.offer(taskId);
    }

    /** 阻塞出队（给 worker / 搬运工用）。被中断返回 null。 */
    public Long takeOrNull() {
        try {
            return queue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 非阻塞出队。空则返回 null。 */
    public Long pollNow() {
        return queue.poll();
    }

    /** 带超时出队。被中断或超时返回 null。 */
    public Long poll(long timeoutMillis) {
        try {
            return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 清空队列。
     *
     * <p>仅用于「引擎重建」：队列里只是 taskId，清掉不会丢数据（DB 里有完整事实），重建时从 DB 重新投递。
     */
    public void clear() {
        queue.clear();
    }

    public int size() {
        return queue.size();
    }

    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** 使用率 0~1，用于队列深度监控 */
    public double usageRatio() {
        return capacity == 0 ? 0D : (double) queue.size() / capacity;
    }

    @Override
    public String toString() {
        return name + "[" + queue.size() + "/" + capacity + "]";
    }
}
