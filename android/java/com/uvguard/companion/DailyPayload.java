package com.uvguard.companion;

import org.json.JSONArray;
import org.json.JSONObject;

/** Upload only a fixed daily summary, never CSV, BLE addresses, logs or medical history. */
public final class DailyPayload {
    private static JSONObject stats(DailyReport.Stats s)throws Exception{
        return new JSONObject().put("count",s.count).put("min",s.count==0?JSONObject.NULL:s.min)
            .put("max",s.count==0?JSONObject.NULL:s.max).put("mean",s.count==0?JSONObject.NULL:s.mean());
    }
    public static JSONObject create(DailyReport.Result r)throws Exception{
        if(!r.complete)throw new IllegalStateException("历史扫描不完整，请先查看本地统计中的警告；未发送AI。");
        if(r.total==0)throw new IllegalStateException("这一天没有实际记录，无法生成AI日报；未发送AI。");
        JSONObject d=new JSONObject().put("date",r.date.toString()).put("timezone",r.zone.getId())
            .put("day_start_ms",r.start).put("day_end_ms",r.end).put("as_of_ms",r.asOf)
            .put("covered_seconds",r.coveredSeconds()).put("total",r.total).put("als",r.als)
            .put("invalid",r.invalid).put("sensor_errors",r.errors).put("alarm_samples",r.alarms)
            .put("duplicates",r.duplicates).put("legacy_samples",r.legacy).put("scan_complete",true)
            .put("first_ms",r.first).put("last_ms",r.last).put("peak_ms",r.peak<0?JSONObject.NULL:r.peak)
            .put("uv",stats(r.uv));
        JSONArray hours=new JSONArray();
        for(DailyReport.Hour h:r.hours)hours.put(new JSONObject().put("start_ms",h.start).put("end_ms",h.end).put("uv",stats(h.uv)));
        return new JSONObject().put("consent",true).put("question","请根据这一天实际记录，给光敏人群一份中文防晒日报：先说明覆盖与空白，再描述观察到的趋势，最后给保守、可执行的日常建议。不要推算安全日晒时长、个人剂量或病情。")
            .put("daily",d.put("hours",hours));
    }
    private DailyPayload(){}
}
