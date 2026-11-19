package com.uvguard.companion;

/** Non-diagnostic reminders: no clinical threshold or safe exposure duration. */
public final class SunProtection {
    public static final String DAILY = "光敏防护提醒：避免直射阳光，优先遮阴，使用遮阳衣物、宽檐帽和其他防护；按医生建议使用广谱UVA/UVB防晒产品。阴天、窗边或没有报警，也不能视为安全。";
    public static final String LIMITS = "针对系统性红斑狼疮等光敏人群，本设备仅作环境趋势参考。UVI不是个人病情或安全暴露阈值，不能全面代表相关UVA/UVB暴露；读数0不代表没有紫外线。手腕佩戴角度、遮挡和未标定均影响结果。";
    public static String current(Protocol.Frame f, boolean fresh) {
        if (!fresh || f == null) return "监测不可用：无法判断当前暴露，请继续既定防晒，不要等待设备提醒才防护。";
        if (f.als()) return "ALS测试期间暂停UV监测；可见光读数不能判断紫外线安全，请继续防护。";
        if (!f.validUv()) return "传感器异常或样本无效，不能判断当前暴露，请继续既定防晒。";
        if (f.uviX10 == 0) return "当前估算UVI为0，不等于无紫外线，也不是光敏患者安全信号；继续按医嘱防护。";
        return "已测得紫外线信号：避免直射阳光，优先进入遮阴处；数值不是个人安全阈值。";
    }
    private SunProtection() {}
}
