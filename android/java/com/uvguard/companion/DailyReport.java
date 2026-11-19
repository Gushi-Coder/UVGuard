package com.uvguard.companion;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 本机CSV的只读统计，不连接AI。覆盖时长是保守的样本区间估计，不是佩戴时长或剂量。 */
public final class DailyReport {
    private static final long MAX_BYTES=64L*1024*1024;
    private static final int MAX_ROWS=500000;
    public static final class Stats {
        public long count;
        public double sum, min=Double.POSITIVE_INFINITY, max=Double.NEGATIVE_INFINITY;
        void add(double v){count++;sum+=v;min=Math.min(min,v);max=Math.max(max,v);}
        public double mean(){return Math.max(min,Math.min(max,sum/count));}
    }
    public static final class Hour {
        public final long start,end;
        public final Stats uv=new Stats();
        Hour(long start,long end){this.start=start;this.end=end;}
    }
    public static final class Result {
        public LocalDate date;
        public ZoneId zone;
        public long start,end,asOf,total,als,invalid,errors,alarms,duplicates,legacy,malformed,unreadable;
        public long first=-1,last=-1,peak=-1,coveredMs;
        public boolean complete=true;
        public final Stats uv=new Stats();
        public final List<Hour> hours=new ArrayList<>();
        public long elapsedSeconds(){return (asOf-start)/1000;}
        public long coveredSeconds(){return coveredMs/1000;}
        public String at(long ms){return ms<0?"—":Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss XXX",Locale.US));}
        public String preview(){
            StringBuilder s=new StringBuilder(date+" · "+zone.getId()+"\n");
            s.append(asOf<end?"今日截至 "+at(asOf):"已结束的日历日（不代表全天有记录）");
            s.append("\n首条 / 末条记录：").append(at(first)).append(" / ").append(at(last));
            s.append("\n有效UV样本：").append(uv.count).append(" / 实时记录：").append(total);
            s.append("\n连续有效UV区间估计：").append(duration(coveredSeconds()));
            s.append("\n已过去但未覆盖：").append(duration(elapsedSeconds()-coveredSeconds()));
            s.append("\n仅把同一文件相邻、序号连续、间隔≤3秒的两个有效UV样本之间计入覆盖；重叠区间只计一次。不跨断连补值，不能等同佩戴或日晒时长。");
            if(uv.count>0)s.append(String.format(Locale.CHINA,"\nUVI 最小 %.1f / 最大 %.1f / 样本均值 %.2f\n最高样本时间：%s",uv.min,uv.max,uv.mean(),at(peak)));
            else s.append("\n没有有效UV，不能判断当天紫外线；不是UVI=0。");
            s.append("\nALS：").append(als).append("；无效UV：").append(invalid).append("；传感器错误标志：").append(errors).append("（可能含ALS）");
            s.append("\n报警标志样本：").append(alarms).append("（含测试，不是报警次数）");
            s.append("\n忽略重复序号：").append(duplicates).append("；无时区旧样本：").append(legacy);
            s.append("\n扫描坏行：").append(malformed).append("；不可读文件：").append(unreadable);
            if(legacy>0)s.append("\n旧CSV没有时区，按本次手机时区解释；旅行/改时区后可能归日不准。");
            if(!complete)s.append("\n警告：扫描未完整，不能当作完整日报；暂不允许发送AI。");
            s.append("\n\n每小时UVI（样本均值 / 最大 / 样本数；空白不是0）");
            for(Hour h:hours){
                s.append("\n").append(at(h.start).substring(0,5)).append(" ").append(Instant.ofEpochMilli(h.start).atZone(zone).getOffset()).append("：");
                if(h.uv.count==0)s.append("无有效样本");
                else s.append(String.format(Locale.CHINA,"%.2f / %.1f / %d",h.uv.mean(),h.uv.max,h.uv.count));
            }
            return s.append("\n\n这些是手机接收到的环境估算，不是个人UV剂量或病情评估。低均值、0或未报警都不代表安全；仍应避免直射阳光并按医嘱防护。").toString();
        }
    }
    public static String duration(long seconds){return String.format(Locale.CHINA,"%d小时%d分%d秒",seconds/3600,seconds%3600/60,seconds%60);}
    /** Background thread only. A bounded scan prevents unlimited files from stalling the phone. */
    public static Result read(File directory,LocalDate date,ZoneId zone,long now) throws IOException {
        Result r=new Result();r.date=date;r.zone=zone;
        r.start=date.atStartOfDay(zone).toInstant().toEpochMilli();
        r.end=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        r.asOf=Math.min(now,r.end);
        if(r.asOf<r.start)throw new IOException("不能统计尚未开始的日期");
        for(long t=r.start;t<r.end;t+=3600000)r.hours.add(new Hour(t,Math.min(t+3600000,r.end)));
        File[] files=directory.listFiles((d,n)->n.startsWith("uvguard_")&&n.endsWith(".csv"));
        if(files==null)throw new IOException("无法读取本机历史目录");
        Arrays.sort(files,Comparator.comparing(File::getName).reversed());
        List<long[]> intervals=new ArrayList<>();
        long bytes=0;int rows=0;
        outer:for(File file:files){
            if(bytes+file.length()>MAX_BYTES){r.complete=false;break;}bytes+=file.length();
            long previousTime=-1,previousSample=-1;boolean previousValid=false;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){
                String line;
                while((line=reader.readLine())!=null){
                    if(++rows>MAX_ROWS){r.complete=false;break outer;}
                    if(line.startsWith("timestamp,")||line.trim().isEmpty())continue;
                    String[] c=line.split(",",-1);
                    if(c.length>1&&"diagnostic".equals(c[1]))continue;
                    try{
                        if(c.length<7||!"realtime".equals(c[1]))throw new IllegalArgumentException();
                        boolean old=false;long time;
                        try{time=OffsetDateTime.parse(c[0]).toInstant().toEpochMilli();}
                        catch(java.time.format.DateTimeParseException e){time=LocalDateTime.parse(c[0]).atZone(zone).toInstant().toEpochMilli();old=true;}
                        if(time<r.start||time>=r.end||time>r.asOf){previousValid=false;previousSample=-1;continue;}
                        long sample=Long.parseLong(c[2]);int x=Integer.parseInt(c[3]),flags=Integer.parseInt(c[6]);
                        if(sample<0||sample>0xffffffffL||x<0||x>65535||flags<0||flags>255)throw new IllegalArgumentException();
                        if(sample==previousSample){r.duplicates++;previousValid=false;continue;}
                        boolean valid=(flags&17)==0&&x!=65535;
                        r.total++;if(old)r.legacy++;
                        r.first=r.first<0?time:Math.min(r.first,time);r.last=Math.max(r.last,time);
                        if((flags&2)!=0)r.alarms++;
                        if((flags&16)!=0)r.als++;
                        else if(!valid)r.invalid++;
                        if((flags&1)!=0)r.errors++; // Error may also occur in ALS; kept separately.
                        if(valid){
                            double u=x/10.0;
                            if(u>r.uv.max||(u==r.uv.max&&time<r.peak))r.peak=time;
                            r.uv.add(u);
                            for(Hour h:r.hours)if(time>=h.start&&time<h.end){h.uv.add(u);break;}
                            long gap=time-previousTime;
                            if(previousValid&&((sample-previousSample)&0xffffffffL)==1&&gap>0&&gap<=3000)
                                intervals.add(new long[]{previousTime,time});
                        }
                        previousValid=valid;previousTime=time;previousSample=sample;
                    }catch(RuntimeException e){r.malformed++;r.complete=false;previousValid=false;previousSample=-1;}
                }
            }catch(IOException e){r.unreadable++;r.complete=false;}
        }
        intervals.sort(Comparator.comparingLong(a->a[0]));
        long left=-1,right=-1;
        for(long[] i:intervals){
            if(left<0){left=i[0];right=i[1];}
            else if(i[0]<=right)right=Math.max(right,i[1]);
            else{r.coveredMs+=right-left;left=i[0];right=i[1];}
        }
        if(left>=0)r.coveredMs+=right-left;
        return r;
    }
    private DailyReport(){}
}
