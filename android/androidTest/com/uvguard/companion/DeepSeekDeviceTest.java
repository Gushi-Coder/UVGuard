package com.uvguard.companion;

import android.content.Context;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Runs on the real phone with isolated TEST keys and fake HTTP transport.
 * Never uses the user's key and never contacts a paid provider.
 */
public final class DeepSeekDeviceTest extends InstrumentationTestCase {
    private static final String TEST=("sk-" + "00000000000000000000000000000000");
    private Context context;
    private AiKeyStore keys;
    @Override protected void setUp()throws Exception{
        super.setUp();context=getInstrumentation().getTargetContext();
        keys=new AiKeyStore(context,"test_direct_key","uvguard.TEST.aes");keys.delete();
        context.getSharedPreferences("test_direct_quota",0).edit().clear().commit();
    }
    @Override protected void tearDown()throws Exception{
        keys.delete();context.getSharedPreferences("test_direct_quota",0).edit().clear().commit();super.tearDown();
    }
    private static JSONObject snapshot()throws Exception{
        return new JSONObject().put("consent",true).put("question","为什么UVI为0？")
            .put("snapshot",new JSONObject().put("connected",true).put("demo",false).put("age_ms",100)
                .put("mode","uv").put("sensor_error",false).put("sample",1).put("raw",0)
                .put("uvi",0).put("battery_percent",40).put("charging",false));
    }
    private static final class Fake extends HttpURLConnection {
        int code=200;String reply="{}";boolean fail;
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        Fake()throws Exception{super(new URL("https://api.deepseek.com/chat/completions"));}
        public void connect(){}
        public void disconnect(){}
        public boolean usingProxy(){return false;}
        public int getResponseCode()throws IOException{if(fail)throw new SocketTimeoutException();return code;}
        public OutputStream getOutputStream(){return sent;}
        public InputStream getInputStream(){return new ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8));}
    }
    private String invoke(Fake fake,JSONObject payload,boolean check,boolean expected)throws Exception{
        final int[] opens={0};final boolean[] ok={false};final String[] text={null};CountDownLatch done=new CountDownLatch(1);
        AiClient client=new AiClient(keys,new AiQuota(context,"test_direct_quota"),url->{
            assertEquals("https",url.getProtocol());assertEquals("api.deepseek.com",url.getHost());
            assertEquals(check?"/models":"/chat/completions",url.getPath());opens[0]++;return fake;
        });
        try{
            client.call(payload,check,(success,result)->{ok[0]=success;text[0]=result;done.countDown();});
            assertTrue("callback timeout",done.await(8,TimeUnit.SECONDS));assertEquals(expected,ok[0]);
            assertNotNull(text[0]);assertFalse("credential echoed",text[0].contains(TEST));
            assertTrue(opens[0]<=1); // No automatic retry, including timeout.
            if(opens[0]>0){assertFalse(fake.getInstanceFollowRedirects());assertEquals("Bearer "+TEST,fake.getRequestProperty("Authorization"));}
            return text[0];
        }finally{client.close();}
    }
    public void testEncryptedStorageAndDelete()throws Exception{
        keys.save(TEST);assertTrue(keys.hasSaved());assertTrue(TEST.equals(keys.load()));
        String cipher=context.getSharedPreferences("test_direct_key",0).getString("ciphertext","");
        assertFalse(cipher.contains(TEST));assertTrue(cipher.length()>20);
        AiKeyStore reopened=new AiKeyStore(context,"test_direct_key","uvguard.TEST.aes");assertTrue(TEST.equals(reopened.load()));
        context.getSharedPreferences("test_direct_key",0).edit().putString("ciphertext",cipher.substring(0,cipher.length()-5)+"AAAAA").commit();
        boolean rejected=false;try{keys.load();}catch(Exception e){rejected=true;}assertTrue("tamper rejected",rejected);
        keys.delete();assertFalse(keys.hasSaved());
        rejected=false;try{keys.load();}catch(Exception e){rejected=true;}assertTrue(rejected);
    }
    public void testKeyFormatsAndMedicalRules(){
        assertTrue(DeepSeekPolicy.validKey(TEST));assertFalse(DeepSeekPolicy.validKey("password"));
        assertFalse(DeepSeekPolicy.validKey(TEST+"\r\nX:bad"));assertFalse(DeepSeekPolicy.validKey("sk-short"));
        assertTrue(DeepSeekPolicy.INSTRUCTIONS.contains("不代表安全"));assertTrue(DeepSeekPolicy.INSTRUCTIONS.contains("不是佩戴时长"));
        assertTrue(DeepSeekPolicy.INSTRUCTIONS.contains("停药"));assertTrue(DeepSeekPolicy.SAFETY.contains("UVA/UVB"));
    }
    public void testProviderContractAndZero()throws Exception{
        JSONObject body=AiClient.providerBody(snapshot());assertEquals("deepseek-flash",body.getString("model"));
        assertEquals("disabled",body.getJSONObject("thinking").getString("type"));assertFalse(body.getBoolean("stream"));
        assertEquals(700,body.getInt("max_tokens"));
        JSONObject user=new JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"));
        assertEquals(0.0,user.getJSONObject("snapshot").getDouble("uvi"));assertFalse(user.has("consent"));
        keys.save(TEST);Fake fake=new Fake();fake.reply="{\"choices\":[{\"message\":{\"content\":\"TEST RESPONSE ONLY "+TEST+"\"},\"finish_reason\":\"length\"}]}";
        String text=invoke(fake,snapshot(),false,true);assertTrue(text.contains("已隐藏密钥"));assertTrue(text.contains("截断"));assertTrue(text.contains("不代表安全"));
        assertFalse(new String(fake.sent.toByteArray(),StandardCharsets.UTF_8).contains(TEST));
    }
    public void testModelVerificationSendsNoMeasurements()throws Exception{
        keys.save(TEST);Fake f=new Fake();f.reply="{\"data\":[{\"id\":\"deepseek-flash\"}]}";
        assertTrue(invoke(f,null,true,true).contains("未发送测量数据"));assertEquals(0,f.sent.size());assertEquals("GET",f.getRequestMethod());
        assertFalse(context.getSharedPreferences("test_direct_quota",0).contains("count"));
    }
    public void testMissingKeyNoTransport()throws Exception{
        Fake f=new Fake();assertTrue(invoke(f,snapshot(),false,false).contains("API密钥"));assertEquals(0,f.sent.size());
        assertFalse(context.getSharedPreferences("test_direct_quota",0).contains("count"));
    }
    public void testErrorsRedirectTimeoutNoRetry()throws Exception{
        keys.save(TEST);
        for(int status:new int[]{401,402,403,429,500,302}){
            context.getSharedPreferences("test_direct_quota",0).edit().clear().commit();Fake f=new Fake();f.code=status;
            assertEquals(DeepSeekPolicy.httpError(status),invoke(f,snapshot(),false,false));
            assertEquals(1,context.getSharedPreferences("test_direct_quota",0).getInt("count",0));
        }
        context.getSharedPreferences("test_direct_quota",0).edit().clear().commit();Fake f=new Fake();f.fail=true;
        assertTrue(invoke(f,snapshot(),false,false).contains("不会自动重试"));
    }
    public void testRejectedSnapshotsAndExtraFields()throws Exception{
        for(String mode:new String[]{"stale","demo","sensor","disconnected","address","consent","nullUV","both"}){
            JSONObject p=snapshot(),s=p.getJSONObject("snapshot");
            switch(mode){case "stale":s.put("age_ms",10001);break;case "demo":s.put("demo",true);break;
                case "sensor":s.put("sensor_error",true);break;case "disconnected":s.put("connected",false);break;
                case "address":s.put("address","not-uploaded");break;case "consent":p.put("consent",false);break;
                case "nullUV":s.put("uvi",JSONObject.NULL);break;case "both":p.put("daily",new JSONObject());break;}
            boolean reject=false;try{AiClient.providerBody(p);}catch(Exception e){reject=true;}assertTrue(mode,reject);
        }
        JSONObject p=snapshot();p.getJSONObject("snapshot").put("mode","als").put("uvi",JSONObject.NULL);AiClient.providerBody(p);
    }
    public void testDailyContract()throws Exception{
        File dir=new File(context.getCacheDir(),"daily_direct_TEST");assertTrue(dir.isDirectory()||dir.mkdir());
        File csv=new File(dir,"uvguard_test.csv");
        try{
            try(FileOutputStream out=new FileOutputStream(csv)){out.write(("2026-10-28T00:00:00+08:00,realtime,1,0,0,40,8\n"
                +"2026-10-28T00:00:01+08:00,realtime,2,20,0,40,8\n").getBytes(StandardCharsets.UTF_8));}
            DailyReport.Result r=DailyReport.read(dir,java.time.LocalDate.of(2026,10,28),java.time.ZoneId.of("Asia/Shanghai"),1793289600000L);
            JSONObject p=DailyPayload.create(r),body=AiClient.providerBody(p);
            JSONObject user=new JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"));
            assertEquals(2,user.getJSONObject("daily").getJSONObject("uv").getLong("count"));assertFalse(user.has("snapshot"));
            assertEquals(1,user.getJSONObject("daily").getLong("covered_seconds"));
            p.getJSONObject("daily").put("scan_complete",false);
            boolean rejected=false;try{AiClient.providerBody(p);}catch(Exception e){rejected=true;}assertTrue(rejected);
        }finally{csv.delete();dir.delete();}
    }
    public void testQuotaPersistsAndBounds()throws Exception{
        AiQuota quota=new AiQuota(context,"test_direct_quota");long wall=1793232000000L,elapsed=100000;
        assertNull(quota.reserve(wall,elapsed));assertNotNull(quota.reserve(wall+1000,elapsed+1000));
        assertNotNull(new AiQuota(context,"test_direct_quota").reserve(wall+2000,elapsed+2000));
        for(int i=1;i<30;i++)assertNull(quota.reserve(wall+i*16000,elapsed+i*16000));
        assertTrue(quota.reserve(wall+30*16000,elapsed+30*16000).contains("30次"));
        assertNull(quota.reserve(wall+86400000,elapsed+86400000));
    }
}
