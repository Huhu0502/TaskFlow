package com.taskflow.engine.local.stage;

import com.taskflow.dao.entity.Task;

/**
 * 模拟业务 + 测试约定（真实项目里会换成调用转写/质检服务）。
 *
 * <p>用 requestId 前缀控制行为，便于在冒烟测试里覆盖成功 / 失败 / 慢任务三条链路：
 * <table border="1">
 *   <tr><th>requestId 前缀</th><th>行为</th></tr>
 *   <tr><td>slow-</td><td>转写 3s、质检 2s，便于观察中间状态</td></tr>
 *   <tr><td>fail-</td><td>转写阶段抛异常 → FAILED</td></tr>
 *   <tr><td>qcfail-</td><td>质检阶段抛异常 → FAILED</td></tr>
 *   <tr><td>其它</td><td>正常成功</td></tr>
 * </table>
 */
final class MockWork {

    private MockWork() {
    }

    static boolean isSlow(Task task) {
        return hasPrefix(task, "slow-");
    }

    static boolean isTranscribeFail(Task task) {
        return hasPrefix(task, "fail-");
    }

    static boolean isQcFail(Task task) {
        return hasPrefix(task, "qcfail-");
    }

    static String mockTranscript(Task task) {
        return "{\"transcript\":\"mock-transcript-" + task.getId() + "\"}";
    }

    static String mockQcReport(Task task) {
        return "{\"qc\":\"pass\",\"score\":100}";
    }

    /** fail_reason 列只有 255 字节，截断防止写入失败 */
    static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }

    static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean hasPrefix(Task task, String prefix) {
        return task.getRequestId() != null && task.getRequestId().startsWith(prefix);
    }
}
