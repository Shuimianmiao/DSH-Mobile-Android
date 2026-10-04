package com.dshmobile.probe;

import android.os.Build;

import java.util.concurrent.TimeUnit;

/**
 * {@link Process} 在 Android 7.x 上的兼容包装。
 *
 * <h3>为什么必须有这个类</h3>
 * 本 App 的 {@code minSdk = 24}（Android 7.0），但这三个方法都是
 * **API 26（Android 8.0）** 才加进 {@code java.lang.Process} 的：
 *
 * <ul>
 *   <li>{@link Process#isAlive()}</li>
 *   <li>{@link Process#waitFor(long, TimeUnit)}</li>
 *   <li>{@link Process#destroyForcibly()}</li>
 * </ul>
 *
 * 在 Android 7.0 / 7.1 上调用它们会抛 {@code NoSuchMethodError}。
 * 而这几处调用正好都在**主链路**上（装容器 / 跑 dsh / 命令看门狗），
 * 于是老机器上的表现非常难查：
 *
 * <ul>
 *   <li>{@code MainActivity.execStreaming} 的看门狗线程一上来就死 ——
 *       它捕的是 {@code Throwable}，所以**不崩溃、也不报错**，
 *       只是从此刻起"卡死判定"永久失效：下载卡住、npm 卡住都不会再有超时，
 *       用户看到的就是"首次加载一直进不去"；</li>
 *   <li>{@code Env} 里的两处 {@code waitFor(timeout)} 抛错后被外层
 *       {@code catch (Throwable)} 吞掉，函数返回 null —— 调用方把它当成"命令失败"。</li>
 * </ul>
 *
 * <p>lint 一直把这几处报成 {@code NewApi} **Error**，但 {@code build.gradle} 里
 * {@code abortOnError false}（为了 targetSdk 28 那条），所以被静默放过了很久。
 * 0.2.8 补上这个包装类，并在修复说明里记下这件事。
 *
 * <p>另外：{@code Process} 没有跨平台的"等进程结束"以外的能力，
 * API 26 以下拿不到 SIGKILL，{@link #killForcibly} 只能退化成 {@code destroy()}。
 */
public final class Proc {

    private Proc() { }

    /** 进程还在不在。 */
    public static boolean isAlive(Process p) {
        if (p == null) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return p.isAlive();
            } catch (Throwable ignore) { /* 落到下面的兜底 */ }
        }
        // API 24/25：exitValue() 在进程还活着时抛 IllegalThreadStateException
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 等进程结束，最多等 {@code timeoutMs}。
     *
     * @return true = 已结束；false = 超时（调用方该考虑强杀）
     */
    public static boolean waitFor(Process p, long timeoutMs) {
        if (p == null) return true;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Throwable ignore) { /* 落到轮询兜底 */ }
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException alive) {
                // 还没结束，继续等
            } catch (Throwable t) {
                return true;                     // 问不出来就当它结束了，别把主流程卡死
            }
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** 强杀进程。API 26 以下没有 SIGKILL，退化为 {@code destroy()}。 */
    public static void killForcibly(Process p) {
        if (p == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                p.destroyForcibly();
                return;
            } catch (Throwable ignore) { /* 落到下面的兜底 */ }
        }
        try {
            p.destroy();
        } catch (Throwable ignore) { }
    }
}
