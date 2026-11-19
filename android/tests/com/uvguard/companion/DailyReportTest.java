package com.uvguard.companion;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public final class DailyReportTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static String row(String time,long sample,int uv,int flags){return time+",realtime,"+sample+","+uv+",10,50,"+flags+",,,,,,,,\n";}
    private static Path dir()throws Exception{return Files.createTempDirectory("uvguard-daily-test-");}
    private static void write(Path d,String file,String rows)throws Exception{Files.write(d.resolve("uvguard_"+file+".csv"),rows.getBytes(StandardCharsets.UTF_8));}
    private static DailyReport.Result read(Path d)throws Exception{return DailyReport.read(d.toFile(),LocalDate.parse("2026-10-28"),ZoneId.of("Asia/Shanghai"),Instant.parse("2026-10-29T04:00:00Z").toEpochMilli());}
    private static void remove(Path d)throws Exception{try(java.util.stream.Stream<Path> files=Files.list(d)){for(Path p:(Iterable<Path>)files::iterator)Files.delete(p);}Files.delete(d);}
    public static void main(String[] args)throws Exception{
        Path d=dir();
        try{
            write(d,"a",row("2026-10-27T23:59:59+08:00",0,100,8)
                +row("2026-10-28T00:00:00+08:00",1,0,8)+row("2026-10-28T00:00:01+08:00",2,20,10)
                +row("2026-10-28T00:00:02+08:00",2,20,10)+row("2026-10-28T00:00:03+08:00",3,30,8)
                +row("2026-10-28T00:00:04+08:00",4,65535,24)+row("2026-10-28T00:00:05+08:00",5,65535,9)
                +row("2026-10-28T00:00:06+08:00",6,65535,8)+row("2026-10-28T01:00:00+08:00",7,10,8)
                +row("2026-10-29T00:00:00+08:00",8,900,8));
            DailyReport.Result r=read(d);
            check(r.total==7&&r.uv.count==4&&r.als==1&&r.invalid==2,"classification / midnight");
            check(r.errors==1&&r.alarms==1&&r.duplicates==1,"flags not events / duplicate");
            check(r.uv.min==0&&r.uv.max==3&&r.uv.mean()==1.5,"zero is valid; sample mean");
            check(r.coveredSeconds()==1,"duplicate/ALS/error/large gaps never bridged");
            check(r.hours.get(0).uv.count==3&&r.hours.get(1).uv.count==1,"hour grouping");
            check(r.complete&&r.preview().contains("不是报警次数"),"preview caveat");
        }finally{remove(d);}
        d=dir();try{
            write(d,"a",row("2026-10-28T08:00:00",0,0,8)+row("2026-10-28T08:00:01",1,0,8));
            write(d,"b",row("2026-10-28T08:00:00+08:00",0,0,8)+row("2026-10-28T08:00:01+08:00",1,0,8));
            DailyReport.Result r=read(d);
            check(r.coveredSeconds()==1&&r.legacy==2&&r.uv.count==4,"overlap union and legacy timezone warning");
        }finally{remove(d);}
        d=dir();try{
            write(d,"a",row("2026-10-27T16:00:00Z",4294967295L,0,8)+row("2026-10-27T16:00:01Z",0,0,8));
            check(read(d).coveredSeconds()==1,"offset UTC / wrapping sample counter");
        }finally{remove(d);}
        d=dir();try{
            DailyReport.Result r=read(d);check(r.total==0&&r.complete&&r.preview().contains("不是UVI=0"),"empty not zero");
            write(d,"bad","broken row\n"+row("2026-10-28T08:00:01+08:00",0,0,8));
            r=read(d);check(!r.complete&&r.malformed==1,"bad rows flagged not silently omitted");
        }finally{remove(d);}
        d=dir();try{
            ZoneId zone=ZoneId.of("America/Los_Angeles");LocalDate day=LocalDate.of(2026,11,1);
            long now=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            DailyReport.Result r=DailyReport.read(d.toFile(),day,zone,now);
            check(r.hours.size()==25&&r.elapsedSeconds()==90000,"DST fall day has25 hours");
            r=DailyReport.read(d.toFile(),LocalDate.of(2026,3,8),zone,now);
            check(r.hours.size()==23,"DST spring day has23 hours");
            long partial=Instant.parse("2026-10-28T04:00:00Z").toEpochMilli();
            r=DailyReport.read(d.toFile(),LocalDate.of(2026,10,28),ZoneId.of("Asia/Shanghai"),partial);
            check(r.elapsedSeconds()==43200&&r.asOf<r.end,"today stops at snapshot not24h");
        }finally{remove(d);}
        System.out.println("PASS: daily boundaries, zero, ALS, errors, gaps, duplicates, overlap, timezone, empty, malformed, DST, partial day");
    }
}
