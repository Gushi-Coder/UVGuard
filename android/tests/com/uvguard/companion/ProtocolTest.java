package com.uvguard.companion;
import java.util.*;
public final class ProtocolTest {
    private static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
    private static byte[] frame(int type){byte[] b=new byte[type==1?14:20];b[0]=(byte)165;b[1]=(byte)type;b[2]=(byte)(type==1?10:16);return b;}
    private static void seal(byte[] b){b[b.length-1]=(byte)Protocol.crc8(b,b.length-1);}
    public static void main(String[] args){
        check(Protocol.crc8("123456789".getBytes(),9)==0xf4,"CRC-8/ATM标准向量");
        byte[] b=frame(1);b[3]=(byte)255;b[4]=(byte)255;b[5]=(byte)255;b[6]=(byte)255;b[7]=35;b[9]=120;b[11]=75;b[12]=8;seal(b);
        Protocol.Frame f=Protocol.parse(b);check(f!=null&&f.sample==4294967295L&&f.uviX10==35&&f.validUv(),"FF01与无符号32位");
        b[7]^=1;check(Protocol.parse(b)==null,"CRC错误");b[7]^=1;
        b[2]=16;seal(b);check(Protocol.parse(b)==null,"长度字段错误");
        b[2]=10;b[11]=101;seal(b);check(Protocol.parse(b)==null,"电量越界");
        b[11]=75;b[12]=24;seal(b);f=Protocol.parse(b);check(f.als()&&!f.validUv(),"ALS不能当UV");
        b[12]=9;seal(b);check(!Protocol.parse(b).validUv(),"传感器错误");
        b[12]=8;b[7]=(byte)255;b[8]=(byte)255;seal(b);check(!Protocol.parse(b).validUv(),"UVI哨兵");
        byte[] d=frame(2);d[5]=2;d[6]=0x22;d[7]=1;d[8]=(byte)0xb2;d[10]=100;d[14]=(byte)255;d[15]=(byte)255;d[16]=(byte)0xa0;d[17]=15;d[18]=8;seal(d);
        f=Protocol.parse(d);check(f!=null&&f.adc==-1&&f.mv==4000&&f.raw==100,"FF03与有符号ADC");
        check(Math.abs(Protocol.estimatedLux(f)-20)<1e-9,"ALS照度");
        check(Arrays.equals(Protocol.threshold(30),new byte[]{16,30,0}),"阈值小端");
        boolean threw=false;try{Protocol.threshold(151);}catch(IllegalArgumentException e){threw=true;}check(threw,"阈值上限");
        for(int n=0;n<14;n++)check(Protocol.parse(new byte[n])==null,"短帧");
        RetryPolicy r=new RetryPolicy();for(long n:new long[]{2000,4000,8000,16000,30000,30000})check(r.nextDelayMs()==n,"退避");
        r.ready();check(r.nextDelayMs()==2000,"退避复位");
        System.out.println("PASS: CRC/FF01/FF03/边界/ALS隔离/命令/重连退避");
    }
}
