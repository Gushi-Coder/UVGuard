package com.uvguard.companion;

/** Pure checks shared by UI and tests. No Android/network dependency. */
public final class AiDataPolicy {
    public static String blocked(boolean demo, boolean ready, boolean ota,
                                 Protocol.Frame frame, long ageMs) {
        if (demo) return "请退出演示模式；模拟读数不会发送给AI。";
        if (ota) return "固件升级期间暂停AI数据解读。";
        if (!ready || frame == null || ageMs < 0 || ageMs > 10000)
            return "请先连接手环并等待10秒内的新数据，旧数据不会用于当前解读。";
        if ((frame.flags & 1) != 0 || (!frame.als() && !frame.validUv()))
            return "传感器报错或正在等待新样本，请先检查诊断；不能用AI判断当前紫外线。";
        return null;
    }

    public static boolean validToken(String token) {
        // DeepSeek provider secrets are never accepted by the phone field.
        return token != null && !token.startsWith("sk-") && token.matches("[A-Za-z0-9_-]{32,128}");
    }
    private AiDataPolicy() {}
}
