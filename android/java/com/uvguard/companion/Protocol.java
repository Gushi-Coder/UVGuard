package com.uvguard.companion;

import java.util.Locale;
import java.util.UUID;

/** 与板端FF01/FF03严格对应；错误帧绝不更新仪表和报警。 */
public final class Protocol {
    public static final UUID SERVICE = uuid(0xff00), REALTIME = uuid(0xff01),
        COMMAND = uuid(0xff02), DIAG = uuid(0xff03),
        CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static UUID uuid(int n) {
        return UUID.fromString(String.format(Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", n));
    }
    public static final class Frame {
        public final boolean realtime;
        public long sample, raw;
        public int uviX10, battery, flags, ctrl, rate, gain, id, status, adc, mv, diagFlags;
        Frame(boolean rt) { realtime = rt; }
        public boolean als() { return realtime ? (flags & 16) != 0 : (diagFlags & 8) != 0; }
        public boolean validUv() { return realtime && !als() && uviX10 != 65535 && (flags & 1) == 0; }
    }
    public static int crc8(byte[] b, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            crc ^= b[i] & 255;
            for (int j = 0; j < 8; j++) crc = ((crc << 1) ^ ((crc & 128) != 0 ? 7 : 0)) & 255;
        }
        return crc;
    }
    public static int u16(byte[] b, int o) { return (b[o] & 255) | ((b[o+1] & 255) << 8); }
    public static long u32(byte[] b, int o) {
        return ((long)u16(b,o)) | (((long)u16(b,o+2)) << 16);
    }
    public static Frame parse(byte[] b) {
        if (b == null || b.length < 4 || (b[0] & 255) != 165) return null;
        int cmd = b[1] & 255;
        if (cmd == 1 && (b.length != 14 || b[2] != 10)) return null;
        if (cmd == 2 && (b.length != 20 || b[2] != 16)) return null;
        if (cmd != 1 && cmd != 2) return null;
        if (crc8(b, b.length-1) != (b[b.length-1] & 255)) return null;
        Frame f = new Frame(cmd == 1);
        if (f.realtime) {
            f.sample = u32(b,3); f.uviX10 = u16(b,7); f.raw = u16(b,9);
            f.battery = b[11] & 255; f.flags = b[12] & 255;
            if (f.battery > 100) return null;
        } else {
            f.sample = u16(b,3); f.ctrl = b[5]&255; f.rate = b[6]&255;
            f.gain = b[7]&255; f.id = b[8]&255; f.status = b[9]&255;
            f.raw = u32(b,10); f.adc = (short)u16(b,14); f.mv = u16(b,16);
            f.diagFlags = b[18]&255;
        }
        return f;
    }
    public static byte[] threshold(int x10) {
        if (x10 < 1 || x10 > 150) throw new IllegalArgumentException("阈值范围0.1~15.0");
        return new byte[]{16, (byte)x10, (byte)(x10>>8)};
    }
    public static byte[] command(int id) { return new byte[]{(byte)id}; }
    public static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b: bytes) s.append(String.format(Locale.US, "%02X ", b&255));
        return s.toString().trim();
    }
    /** ALS标称照度，仅当诊断寄存器标识18位/3倍时适用；不是UV。 */
    public static double estimatedLux(Frame f) {
        if (f.realtime || !f.als() || f.gain != 1 || (f.rate & 0x70) != 0x20) return Double.NaN;
        return f.raw * 0.6 / 3.0;
    }
    public static String risk(double uvi) {
        return uvi < 3 ? "低" : uvi < 6 ? "中等" : uvi < 8 ? "高" : uvi < 11 ? "很高" : "极高";
    }
    private Protocol() {}
}
