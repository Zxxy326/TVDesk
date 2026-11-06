package com.tvdesk.desk;

/**
 * 守护在本进程里的运行状态。
 *
 * <p>为什么要单独一个类：原来「服务开没开」只有一个布尔值，而且是从系统设置里读的。
 * 那个值只说明「配置里写了」，说明不了「现在真的连上了」。更要命的是它跨进程重启还会残留——
 * 上一代进程「绑定成功」的结论会被新进程当成现状。
 *
 * <p>这里只放<b>本进程</b>的事实：代次、恢复计数、最近一次判定。进程重启就归零。
 */
public final class GuardRuntimeState {

    /** 用户意愿或配置变化时递增；异步回调执行前对不上就作废 */
    private static int generation;

    /** 这一轮恢复已经试了几次 */
    private static int recoverAttempts;

    /** 这一轮恢复窗口是什么时候开始的 */
    private static long recoverWindowStart;

    /** 最近一次判定的原因，给状态页和日志用 */
    private static String lastReason = "";

    private static long lastDecisionAt;

    /** 本进程里 {#onServiceConnected} 真的回调过 */
    private static boolean connected;

    private GuardRuntimeState() {
    }

    public static synchronized int generation() {
        return generation;
    }

    /** 意愿/配置变了：作废所有在飞的异步任务 */
    public static synchronized int bumpGeneration() {
        generation++;
        recoverAttempts = 0;
        recoverWindowStart = 0;
        return generation;
    }

    public static synchronized boolean isCurrent(int gen) {
        return gen == generation;
    }

    public static synchronized void noteConnected() {
        connected = true;
        recoverAttempts = 0;
        recoverWindowStart = 0;
    }

    public static synchronized void noteDisconnected() {
        connected = false;
    }

    /**
     * 本进程里连接是否有效。
     *
     * <p>注意这不是「系统配置里有没有这个名字」，后者用
     * {@link GuardSetup#isServiceEnabled}。
     */
    public static synchronized boolean connected() {
        return connected;
    }

    /**
     * 开一轮恢复窗口。
     *
     * <p>方案里的节奏是 2/5/15/30 秒四次，仍失败就进 DEGRADED 等用户处理。
     * 这里只负责计数，定时由调用方用 Handler 排。
     */
    public static synchronized boolean beginRecoverIfNeeded() {
        if (recoverAttempts >= HomeTakeoverPolicy.maxRecoverAttempts()) {
            return false;
        }
        if (recoverAttempts == 0) {
            recoverWindowStart = System.currentTimeMillis();
        }
        recoverAttempts++;
        return true;
    }

    public static synchronized int recoverAttempts() {
        return recoverAttempts;
    }

    public static synchronized void endRecover() {
        recoverAttempts = 0;
        recoverWindowStart = 0;
    }

    public static synchronized void noteDecision(String reason) {
        lastReason = reason == null ? "" : reason;
        lastDecisionAt = System.currentTimeMillis();
    }

    public static synchronized String lastReason() {
        return lastReason;
    }

    public static synchronized long lastDecisionAt() {
        return lastDecisionAt;
    }

    /** 状态页要显示的一行摘要 */
    public static synchronized String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(connected ? "已连接" : "未连接");
        if (recoverAttempts > 0) {
            sb.append(" · 恢复 ").append(recoverAttempts).append(" 次");
        }
        if (!lastReason.isEmpty()) {
            sb.append(" · 最近：").append(lastReason);
        }
        return sb.toString();
    }
}
