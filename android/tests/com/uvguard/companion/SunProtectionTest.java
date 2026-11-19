package com.uvguard.companion;
public final class SunProtectionTest {
    private static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
    public static void main(String[] args){
        Protocol.Frame f=new Protocol.Frame(true); f.flags=8;f.uviX10=0;
        check(SunProtection.current(f,true).contains("不等于无紫外线"),"zero not safe");
        f.uviX10=5;check(SunProtection.current(f,true).contains("避免直射阳光"),"low still protects");
        f.flags=24;check(SunProtection.current(f,true).contains("暂停UV"),"ALS not safe");
        f.flags=9;check(SunProtection.current(f,true).contains("异常"),"error unknown");
        check(SunProtection.current(f,false).contains("监测不可用"),"stale unknown");
        check(SunProtection.current(null,false).contains("继续既定防晒"),"missing data protects");
        check(SunProtection.LIMITS.contains("UVA/UVB"),"spectral limits");
        System.out.println("PASS: photosensitivity reminders, zero/low/ALS/error/stale/missing data");
    }
}
