package com.uvguard.companion;

import android.os.SystemClock;
import org.json.JSONObject;

/** Whitelist only measurements. Never upload address, logs, CSV, location or keys. */
public final class AiSnapshot {
    public static JSONObject create(BleService ble, boolean demo) throws Exception {
        long now = SystemClock.elapsedRealtime();
        Protocol.Frame f = ble == null ? null : ble.realtime;
        long age = ble == null || ble.lastRealtimeAt == 0 ? -1 : now - ble.lastRealtimeAt;
        String blocked = AiDataPolicy.blocked(demo, ble != null && ble.ready,
            ble != null && ble.otaRunning, f, age);
        if (blocked != null) throw new IllegalStateException(blocked);
        JSONObject s = new JSONObject();
        s.put("connected", true).put("demo", false).put("age_ms", age)
            .put("mode", f.als() ? "als" : "uv").put("sensor_error", (f.flags & 1) != 0)
            .put("sample", f.sample).put("raw", f.raw)
            .put("uvi", f.validUv() ? f.uviX10 / 10.0 : JSONObject.NULL)
            .put("battery_percent", f.battery).put("charging", (f.flags & 4) != 0);
        Protocol.Frame d = ble.diagnostic;
        long diagAge = now - ble.lastDiagAt;
        if (d != null && ble.lastDiagAt != 0 && diagAge >= 0 && diagAge <= 10000 && d.als() == f.als()) {
            s.put("diagnostic", new JSONObject().put("age_ms", diagAge).put("part_id", d.id)
                .put("adc_raw", d.adc).put("battery_mv", d.mv).put("raw", d.raw)
                .put("mode", d.als() ? "als" : "uv"));
        }
        long wallNow = System.currentTimeMillis(), first = wallNow;
        int count = 0;
        double sum = 0, min = Double.POSITIVE_INFINITY, max = 0;
        // points already excludes invalid UV and ALS. Restrict to actual recent window.
        for (BleService.Point p : ble.points) {
            long delta = wallNow - p.time;
            if (delta < 0 || delta > 300000 || !Double.isFinite(p.uvi)) continue;
            count++; sum += p.uvi; min = Math.min(min, p.uvi); max = Math.max(max, p.uvi);
            first = Math.min(first, p.time);
        }
        if (count > 0) s.put("history", new JSONObject().put("count", count)
            .put("window_seconds", (wallNow - first) / 1000)
            .put("min", min).put("max", max).put("mean", Math.max(min, Math.min(max, sum / count))));
        return s;
    }
    private AiSnapshot() {}
}
