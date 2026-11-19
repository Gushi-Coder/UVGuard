package com.uvguard.companion;

/** 独立可测试的退避策略，避免每两秒永久重试耗电和触发扫描限流。 */
public final class RetryPolicy {
    private int failures;
    public long nextDelayMs() { return Math.min(30000L, 2000L << Math.min(failures++, 4)); }
    public void ready() { failures = 0; }
}
