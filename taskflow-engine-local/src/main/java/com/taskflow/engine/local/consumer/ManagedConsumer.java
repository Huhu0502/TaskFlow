package com.taskflow.engine.local.consumer;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 可管理的消费者：<b>一个对象 = 一个常驻线程 + 一个消费循环</b>。
 *
 * <p><b>为什么不用线程池</b>：
 * <ol>
 *   <li>消费循环是「永久循环」，而线程池是为「大量短任务」设计的，生命周期完全相反；</li>
 *   <li>池线线程拿不到引用，无法 {@code interrupt()} / 判定存活 / 重启；</li>
 *   <li>若循环因异常终止，池只会补一个「空闲线程」，<b>不会重启循环</b>，看起来线程还活着其实已停摆。</li>
 * </ol>
 *
 * <p><b>异常兜底（关键）</b>：标准循环里 {@code try} 只包「单个任务」，
 * 所以一个任务抛异常<b>不会杀死消费循环</b>——这是最便宜、最有效的「防丢」手段。
 *
 * <p>本类只做「线程生命周期 + 活跃打点」，不含任何业务逻辑。
 */
@Slf4j
public class ManagedConsumer {

    /** 自定义循环（搬运工：按配额从多个桶取，再投到工作队列） */
    @FunctionalInterface
    public interface ConsumerLoop {
        void run(ManagedConsumer self);
    }

    private final String name;

    /** 取一个任务（可阻塞；返回 null 表示「暂时没活」，不代表结束） */
    private final Supplier<Long> take;

    /** 处理一个任务 */
    private final Consumer<Long> handler;

    /** 自定义循环，与 take/handler 二选一 */
    private final ConsumerLoop customLoop;

    /** 存活标志：true 表示「有一个循环正在跑」。由 start() 置位、循环的 finally 释放。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 最后一次成功处理任务的时间，供活性监控判断「停滞」 */
    private final AtomicLong lastActiveAt = new AtomicLong(System.currentTimeMillis());

    /** 积压量来源（供活性监控判断「有活却不干」），默认 0 */
    private volatile Supplier<Integer> backlogSupplier = () -> 0;

    private volatile Thread thread;

    /** 标准形态：取一个 → 处理一个（worker 用） */
    public ManagedConsumer(String name, Supplier<Long> take, Consumer<Long> handler) {
        this.name = name;
        this.take = take;
        this.handler = handler;
        this.customLoop = null;
    }

    /** 自定义循环形态（搬运工用） */
    public ManagedConsumer(String name, ConsumerLoop customLoop) {
        this.name = name;
        this.take = null;
        this.handler = null;
        this.customLoop = customLoop;
    }

    /** 关联「待处理量」来源，供活性监控判断停滞 */
    public ManagedConsumer watching(Supplier<Integer> backlogSupplier) {
        this.backlogSupplier = backlogSupplier;
        return this;
    }

    /**
     * 启动（<b>幂等</b>）：已经在跑就什么都不做。
     *
     * <p>先 CAS 置位、再创建线程，保证「最多只有一个消费循环」。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::loop, name);
        thread.setDaemon(false);
        thread.setUncaughtExceptionHandler((t, e) ->
                log.error("⚠️ 消费者 {} 未捕获异常，线程即将退出", t.getName(), e));
        thread.start();
        lastActiveAt.set(System.currentTimeMillis());
        log.info("消费者 {} 已启动", name);
    }

    private void loop() {
        log.info("消费循环 {} 开始", name);
        try {
            if (customLoop != null) {
                customLoop.run(this);
            } else {
                standardLoop();
            }
        } catch (Throwable e) {
            log.error("⚠️ 消费循环 {} 异常终止", name, e);
        } finally {
            // ⭐ 必须释放！否则 running 永远为 true，start() 的 CAS 永远失败，自愈能力永久失效
            running.set(false);
            log.warn("消费循环 {} 已退出", name);
        }
    }

    private void standardLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            Long taskId;
            try {
                taskId = take.get();
            } catch (Throwable e) {
                log.error("消费者 {} 取任务失败", name, e);
                break;
            }

            if (taskId == null) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                continue;                       // 暂时没活，继续等
            }

            markActive();
            try {
                handler.accept(taskId);          // ⭐ try 只包「单个任务」
            } catch (Throwable e) {
                // 不往外抛：线程不死，循环继续取下一个任务
                log.error("消费者 {} 处理任务 {} 失败", name, taskId, e);
            }
        }
    }

    /** 打一次活跃时间戳 */
    public void markActive() {
        lastActiveAt.set(System.currentTimeMillis());
    }

    /** 距上次处理任务过去了多久（毫秒） */
    public long idleMillis() {
        return System.currentTimeMillis() - lastActiveAt.get();
    }

    /** 待处理量（队列还有活吗），0 表示空闲是正常的 */
    public int backlog() {
        return backlogSupplier.get();
    }

    /** 是否存活：running 标志 + 线程状态 双条件 */
    public boolean isAlive() {
        Thread t = thread;
        return running.get() && t != null && t.isAlive();
    }

    public String getName() {
        return name;
    }

    /** 优雅停止：interrupt → 最多等 timeoutMillis → 仍未退出则告警（疑似死锁） */
    public void interruptAndJoin(long timeoutMillis) {
        Thread t = thread;
        if (t == null) {
            return;
        }
        t.interrupt();
        try {
            t.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            log.error("⚠️ 消费者 {} 被中断后仍未退出（疑似死锁），放弃等待", name);
        }
    }
}
