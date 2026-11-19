package com.uvguard.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Local expense guard only, not an account-wide/provider-enforced spending cap. */
public final class AiQuota {
    private final SharedPreferences prefs;
    public AiQuota(Context context){this(context,"deepseek_quota_v1");}
    AiQuota(Context context,String name){prefs=context.getSharedPreferences(name,0);}
    public synchronized String reserve(){return reserve(System.currentTimeMillis(),SystemClock.elapsedRealtime());}
    synchronized String reserve(long wall,long elapsed){
        try{
            long lastWall=prefs.getLong("last_wall",0),lastElapsed=prefs.getLong("last_elapsed",0);
            if(lastWall>0&&(wall-lastWall<15000||(elapsed>=lastElapsed&&elapsed-lastElapsed<15000)))
                return "每次AI建议请至少间隔15秒，避免重复计费；时间回退时也会暂时阻止请求。";
            String today=Instant.ofEpochMilli(wall).atZone(ZoneOffset.UTC).toLocalDate().toString();
            int count=today.equals(prefs.getString("day",""))?prefs.getInt("count",0):0;
            if(count<0||count>30)return "本地次数记录异常，已停止调用。";
            if(count>=30)return "已达到本机每日30次建议请求上限（UTC日），请明天再试。";
            if(!prefs.edit().putString("day",today).putInt("count",count+1).putLong("last_wall",wall)
                .putLong("last_elapsed",elapsed).commit())return "无法保存本地次数记录，已停止调用。";
            return null; // Count before the provider request; failure still consumes an attempt.
        }catch(Exception e){return "本地次数记录不可读，已停止调用。";}
    }
}
