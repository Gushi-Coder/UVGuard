package com.uvguard.companion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Direct official HTTPS only. No local bridge, fallback server, logging, retry or BLE control. */
public final class AiClient {
    public interface Callback { void completed(boolean success,String text); }
    interface Transport { HttpURLConnection open(URL url)throws Exception; }
    private final AiKeyStore keys;
    private final AiQuota quota;
    private final Transport transport;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile HttpURLConnection active;
    private long lastCheck=-1;
    public AiClient(Context context){this(new AiKeyStore(context),new AiQuota(context),url->(HttpURLConnection)url.openConnection());}
    AiClient(AiKeyStore keys,AiQuota quota,Transport transport){this.keys=keys;this.quota=quota;this.transport=transport;}
    static JSONObject providerBody(JSONObject payload)throws Exception{
        if(payload==null||!payload.optBoolean("consent",false))throw new IllegalStateException("请先确认发送数据。");
        boolean daily=payload.has("daily");
        if(daily==payload.has("snapshot"))throw new IllegalStateException("只能选择日报或当前快照。");
        String question=payload.optString("question","");
        if(question.length()>500)throw new IllegalStateException("问题最多500字。");
        JSONObject user=new JSONObject().put("question",question);
        if(daily){
            JSONObject d=payload.getJSONObject("daily");
            if(!d.optBoolean("scan_complete")||d.optLong("total",0)<=0)
                throw new IllegalStateException("没有完整扫描的实际记录，未调用AI。");
            requireFields(d,"date,timezone,day_start_ms,day_end_ms,as_of_ms,covered_seconds,total,als,invalid,sensor_errors,alarm_samples,duplicates,legacy_samples,scan_complete,first_ms,last_ms,peak_ms,uv,hours");
            checkStats(d.getJSONObject("uv"));
            JSONArray hours=d.getJSONArray("hours");
            if(hours.length()<22||hours.length()>26)throw new IllegalStateException("小时摘要不完整。");
            for(int i=0;i<hours.length();i++){JSONObject h=hours.getJSONObject(i);requireFields(h,"start_ms,end_ms,uv");checkStats(h.getJSONObject("uv"));}
            user.put("daily",d);
        }else{
            JSONObject s=payload.getJSONObject("snapshot");
            requireFields(s,"connected,demo,age_ms,mode,sensor_error,sample,raw,uvi,battery_percent,charging,diagnostic,history");
            long age=s.optLong("age_ms",-1);
            if(!s.optBoolean("connected")||s.optBoolean("demo",true)||age<0||age>10000||s.optBoolean("sensor_error",true))
                throw new IllegalStateException("当前数据已过期或无效，请重新采样并确认发送。");
            String mode=s.optString("mode");
            if(!mode.equals("uv")&&!mode.equals("als"))throw new IllegalStateException("测量模式无效。");
            if(mode.equals("uv")&&(s.isNull("uvi")||s.getDouble("uvi")<0||s.getDouble("uvi")>6553.4))
                throw new IllegalStateException("当前没有有效UV，未调用AI。");
            if(mode.equals("als")&&!s.isNull("uvi"))throw new IllegalStateException("ALS不能作为当前UVI。");
            if(s.has("diagnostic")){
                JSONObject d=s.getJSONObject("diagnostic");requireFields(d,"age_ms,part_id,adc_raw,battery_mv,raw,mode");
                if(d.optLong("age_ms",10001)>10000||d.optInt("adc_raw",-1)<0||d.optInt("battery_mv",0)<2000||d.optInt("battery_mv",0)>5000||!mode.equals(d.optString("mode")))
                    s.remove("diagnostic");
            }
            if(s.has("history"))requireFields(s.getJSONObject("history"),"count,window_seconds,min,max,mean");
            user.put("snapshot",s);
        }
        if(user.toString().getBytes(StandardCharsets.UTF_8).length>8192)throw new IllegalStateException("摘要过大，已停止发送。");
        return new JSONObject().put("model",DeepSeekPolicy.MODEL)
            .put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",DeepSeekPolicy.INSTRUCTIONS))
                .put(new JSONObject().put("role","user").put("content",user.toString())))
            .put("thinking",new JSONObject().put("type","disabled")).put("max_tokens",700).put("stream",false);
    }
    private static void requireFields(JSONObject object,String allowed)throws Exception{
        java.util.Set<String> fields=new java.util.HashSet<>(java.util.Arrays.asList(allowed.split(",")));
        java.util.Iterator<String> it=object.keys();while(it.hasNext())if(!fields.contains(it.next()))throw new IllegalStateException("摘要包含未授权的字段，已停止发送。");
    }
    private static void checkStats(JSONObject s)throws Exception{
        requireFields(s,"count,min,max,mean");
        long n=s.getLong("count");if(n<0||n>500000)throw new IllegalStateException("样本数无效。");
        if(n==0){if(!s.isNull("min")||!s.isNull("max")||!s.isNull("mean"))throw new IllegalStateException("空白不能统计成零。");return;}
        double min=s.getDouble("min"),max=s.getDouble("max"),mean=s.getDouble("mean");
        if(!Double.isFinite(min)||!Double.isFinite(max)||!Double.isFinite(mean)||min<0||max>6553.4||min>mean||mean>max)
            throw new IllegalStateException("UV统计范围无效。");
    }
    public void call(JSONObject payload,boolean check,Callback callback){
        if(closed)return;
        final JSONObject frozen;
        try{frozen=payload==null?null:new JSONObject(payload.toString());}
        catch(Exception e){callback.completed(false,"摘要格式无效，未发送。");return;}
        worker.execute(()->{
            boolean success=false,remote=false;String result;String key=null;HttpURLConnection c=null;
            try{
                if(closed)return;
                byte[] body=check?null:providerBody(frozen).toString().getBytes(StandardCharsets.UTF_8);
                try{key=keys.load();}catch(Exception e){throw new IllegalStateException("尚无可读取的API密钥，请在AI设置保存；若已保存，请删除后重新输入。");}
                if(check){
                    long now=SystemClock.elapsedRealtime();
                    if(lastCheck>=0&&now-lastCheck<15000)throw new IllegalStateException("密钥验证请间隔15秒。");lastCheck=now;
                }else{String blocked=quota.reserve();if(blocked!=null)throw new IllegalStateException(blocked);}
                URL url=new URL(DeepSeekPolicy.BASE+(check?"/models":"/chat/completions"));
                remote=true;c=transport.open(url);active=c;if(closed)return;
                c.setConnectTimeout(10000);c.setReadTimeout(45000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
                c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Accept","application/json");
                if(!check){
                    c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");
                    c.setFixedLengthStreamingMode(body.length);try(OutputStream out=c.getOutputStream()){out.write(body);}
                }
                int code=c.getResponseCode();
                if(code!=200){result=DeepSeekPolicy.httpError(code);}
                else{
                    JSONObject response=read(c.getInputStream());
                    if(check){
                        JSONArray models=response.getJSONArray("data");boolean found=false;
                        for(int i=0;i<models.length();i++)if(DeepSeekPolicy.MODEL.equals(models.getJSONObject(i).optString("id")))found=true;
                        if(!found)throw new IOException();
                        success=true;result="DeepSeek密钥验证通过，已访问官方模型列表。\n未发送测量数据，未生成AI回答；这不保证账户余额或后续建议请求一定成功。";
                    }else{
                        JSONObject choice=response.getJSONArray("choices").getJSONObject(0);
                        String answer=choice.getJSONObject("message").getString("content").trim();
                        if(answer.isEmpty())throw new IOException();
                        answer=answer.replace(key,"[已隐藏密钥]").replaceAll("sk-[A-Za-z0-9_-]{16,256}","[已隐藏密钥]");
                        success=true;result="DeepSeek · "+DeepSeekPolicy.MODEL+"\n\n"+answer.substring(0,Math.min(answer.length(),8000))
                            +("length".equals(choice.optString("finish_reason"))?"\n\n回答因长度限制截断。":"")
                            +"\n\n"+DeepSeekPolicy.SAFETY
                            +(frozen.has("daily")?"\n\n仅解读所选日期已记录的摘要，不能填补空白或推算个人UV剂量。":"\n\n仅针对点击时的快照，非实时报警。");
                    }
                }
            }catch(IllegalStateException e){result=remote?"DeepSeek请求未完成，未展示任何内部错误；不会自动重试。":e.getMessage();}
            catch(Exception e){result="未取得有效回答。请检查手机网络、API账户和余额。若请求发出后超时，可能已计费；不会自动重试，请稍后自行决定是否再试。";}
            finally{key=null;if(c!=null)c.disconnect();active=null;}
            final boolean ok=success;final String text=result;
            if(!closed)main.post(()->{if(!closed)callback.completed(ok,text);});
        });
    }
    private static JSONObject read(InputStream input)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=input){byte[] buf=new byte[2048];int n;
            while((n=in.read(buf))!=-1){if(out.size()+n>131072)throw new IOException();out.write(buf,0,n);}}
        return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
    }
    public void close(){closed=true;HttpURLConnection c=active;if(c!=null)c.disconnect();worker.shutdownNow();main.removeCallbacksAndMessages(null);}
}
