package com.uvguard.companion;

public final class AiDataPolicyTest {
    static void check(boolean x, String message){if(!x)throw new AssertionError(message);}
    public static void main(String[] args){
        Protocol.Frame f=new Protocol.Frame(true);f.uviX10=0;f.flags=8;
        check(AiDataPolicy.blocked(false,true,false,f,10000)==null,"valid UV zero at freshness boundary");
        check(AiDataPolicy.blocked(false,true,false,f,10001)!=null,"stale");
        check(AiDataPolicy.blocked(true,true,false,f,0)!=null,"demo");
        check(AiDataPolicy.blocked(false,false,false,f,0)!=null,"disconnected");
        check(AiDataPolicy.blocked(false,true,true,f,0)!=null,"OTA");
        check(AiDataPolicy.blocked(false,true,false,null,0)!=null,"missing");
        f.flags=9;check(AiDataPolicy.blocked(false,true,false,f,0)!=null,"sensor error");
        f.flags=8;f.uviX10=65535;check(AiDataPolicy.blocked(false,true,false,f,0)!=null,"sentinel");
        f.flags=24;check(AiDataPolicy.blocked(false,true,false,f,0)==null,"ALS permitted without UV");
        check(AiDataPolicy.validToken("abcdefghijklmnopqrstuvwxyz0123456789_ABC"),"local token");
        check(!AiDataPolicy.validToken(("sk-" + "00000000000000000000000000000000")),"provider key refused");
        check(!AiDataPolicy.validToken("short"),"short token");
        System.out.println("PASS: AI freshness/demo/OTA/UV-zero/ALS/token guards");
    }
}
