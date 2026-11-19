package com.uvguard.companion;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

/** 原生离线监测界面。通过绑定前台服务保持界面与连接生命周期分离。 */
public final class MainActivity extends Activity implements BleService.Listener {
    private final int BG=0xff101f25, PANEL=0xff1c3037, TEXT=0xffeef5f3,
        MUTED=0xffabc0c5, ACCENT=0xffffcb85, GREEN=0xff6ed9b6, RED=0xffff8b82;
    private BleService ble;
    private boolean bound, demo;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout nav,deviceList;
    private FrameLayout body;
    private View[] pages;
    private TextView status,hero,caption,battery,raw,summary,diag,logs,link,commandResult,alarm,otaText;
    private CheckBox auto,phoneAlarm,boardAlerts;
    private EditText threshold;
    private Chart chart;
    private int page;
    private AiClient aiClient;
    private AiKeyStore aiKeys;
    private EditText aiKey, aiQuestion;
    private TextView aiKeyStatus;
    private TextView aiAnswer;
    private TextView sunAdvice;
    private boolean aiBusy;
    private TextView dailyText,dailyAnswer;
    private DailyReport.Result dailyReport;
    private boolean dailyLoading;
    private volatile boolean destroyed;
    private final java.util.concurrent.ExecutorService dailyWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final ServiceConnection binding=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){ble=((BleService.LocalBinder)b).get();ble.listen(MainActivity.this);refresh();}
        public void onServiceDisconnected(ComponentName n){ble=null;refresh();}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        aiKeys=new AiKeyStore(this);aiClient=new AiClient(this);
        // The obsolete computer token is not a provider key and must not be migrated.
        getSharedPreferences("ai",0).edit().remove("token").apply();
        makeUi();permissions();handler.post(ticker);
    }
    private boolean allowed(){
        if(Build.VERSION.SDK_INT>=31)return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private void permissions(){
        if(!allowed()){
            requestPermissions(Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION},10);
            return;
        }
        startBle();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] result){
        super.onRequestPermissionsResult(r,p,result);
        if(allowed()){
            startBle();
            if(r==10 && Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);
        }else toast("需要附近设备权限才能连接");
    }
    private void startBle(){
        if(bound)return;
        Intent service=new Intent(this,BleService.class);
        try{startForegroundService(service);bound=bindService(service,binding,BIND_AUTO_CREATE);}
        catch(Exception e){toast("启动服务失败："+e.getMessage());}
    }
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);t.setPadding(0,dp(3),0,dp(3));return t;
    }
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private Button button(String label,Runnable run){
        Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setTextColor(TEXT);b.setAllCaps(false);
        b.setMinHeight(dp(48));b.setBackground(bg(0xff29444b,12));b.setPadding(dp(10),dp(7),dp(10),dp(7));b.setOnClickListener(v->run.run());return b;
    }
    private void addButton(LinearLayout parent,String label,Runnable run){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(9);parent.addView(button(label,run),p);
    }
    private void pair(LinearLayout parent,String left,Runnable a,String right,Runnable b){
        LinearLayout r=row();
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(0,dp(8),dp(4),0);r.addView(button(left,a),p);
        LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(0,-2,1);q.setMargins(dp(4),dp(8),0,0);r.addView(button(right,b),q);parent.addView(r);
    }
    private LinearLayout card(LinearLayout parent,String title){
        LinearLayout c=col();c.setPadding(dp(16),dp(12),dp(16),dp(16));c.setBackground(bg(PANEL,18));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);parent.addView(c,p);c.addView(text(title,17,TEXT));return c;
    }
    private ScrollView scroll(LinearLayout root){ScrollView s=new ScrollView(this);s.setFillViewport(true);root.setPadding(dp(18),0,dp(18),dp(25));s.addView(root);return s;}
    private void makeUi(){
        LinearLayout shell=col();shell.setBackgroundColor(BG);setContentView(shell);
        LinearLayout head=col();head.setPadding(dp(22),dp(18),dp(22),dp(12));head.addView(text("UV-Guard  随行",26,TEXT));
        status=text("准备连接 · AI仅确认后上传",13,GREEN);head.addView(status);shell.addView(head);
        body=new FrameLayout(this);shell.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        pages=new View[]{overview(),connection(),controls(),history(),help(),aiPage()};
        nav=row();nav.setPadding(dp(6),dp(6),dp(6),dp(9));shell.addView(nav);
        String[] labels={"实时","连接","控制","记录","说明","AI"};
        for(int i=0;i<labels.length;i++){final int n=i;TextView t=text(labels[i],14,MUTED);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(13),0,dp(13));
            t.setOnClickListener(v->show(n));nav.addView(t,new LinearLayout.LayoutParams(0,-2,1));}
        show(1);
    }
    private void show(int n){
        page=n;body.removeAllViews();body.addView(pages[n]);for(int i=0;i<nav.getChildCount();i++){
            TextView t=(TextView)nav.getChildAt(i);t.setTextColor(i==n?ACCENT:MUTED);t.setBackground(i==n?bg(PANEL,12):null);}
        refresh();
    }
    private View overview(){
        LinearLayout root=col(), c=card(root,"当前紫外线");
        hero=text("—",76,ACCENT);hero.setTypeface(Typeface.DEFAULT,Typeface.BOLD);c.addView(hero);
        caption=text("连接手环后开启实时监测",15,MUTED);c.addView(caption);alarm=text("等待数据",14,GREEN);c.addView(alarm);
        c.addView(text("UVI为标称估算，未用参考仪器标定；不用于医疗判断。",12,MUTED));
        LinearLayout protection=card(root,"光敏人群 · 日常防晒");
        protection.addView(text(SunProtection.DAILY,15,ACCENT));
        sunAdvice=text("监测不可用时也应继续既定防护。",14,TEXT);protection.addView(sunAdvice);
        protection.addView(text(SunProtection.LIMITS,12,MUTED));
        LinearLayout info=card(root,"设备状态");battery=text("电量 — · 电压 —",19,TEXT);raw=text("原始读数 —",14,MUTED);info.addView(battery);info.addView(raw);
        LinearLayout trend=card(root,"最近 300 个样本");chart=new Chart();trend.addView(chart,new LinearLayout.LayoutParams(-1,dp(180)));
        summary=text("有效样本 —",13,MUTED);trend.addView(summary);
        LinearLayout d=card(root,"传感器诊断");diag=text("诊断帧每约5秒刷新。连接成功不等于传感器正常。",13,MUTED);d.addView(diag);
        return scroll(root);
    }
    private View connection(){
        LinearLayout root=col(),c=card(root,"连接你的手环");
        c.addView(text("先退出电脑、旧App和nRF Connect连接。安全版首次连接需确认系统配对，只允许一个主人。换手机或电脑测试后，请长按手环SW1至少8秒，松开后120秒内配对；手机若有旧记录，先在蓝牙设置中取消配对。",14,MUTED));
        pair(c,"扫描设备",()->{demo=false;permissions();if(ble!=null)ble.scan();},"连接上次",()->{demo=false;permissions();if(ble!=null)ble.reconnectLast();});
        pair(c,"主动断开",()->{if(ble!=null)ble.disconnect();},"重新扫描",()->{if(ble!=null){ble.disconnect();ble.scan();}});
        auto=new CheckBox(this);auto.setText("意外断线后自动重连");auto.setTextColor(TEXT);auto.setChecked(true);auto.setOnClickListener(v->{if(ble!=null)ble.autoReconnect(auto.isChecked());});c.addView(auto);
        link=text("尚未连接",13,MUTED);c.addView(link);LinearLayout list=card(root,"附近设备 · 优先显示 UV-Guard");
        deviceList=col();list.addView(deviceList);deviceList.addView(text("点扫描设备，再点列表中的手环。",14,MUTED));
        addButton(root,"权限与蓝牙",()->{permissions();try{startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));}catch(SecurityException e){toast("请先允许附近设备权限");}catch(Exception e){startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));}});
        addButton(root,"应用省电设置",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()))));
        addButton(root,"结束后台监测",()->{if(ble!=null&&ble.otaRunning){toast("请先完成或取消升级");return;}if(ble!=null){ble.listen(null);ble.shutdown();}if(bound){unbindService(binding);bound=false;}ble=null;refresh();});
        return scroll(root);
    }
    private View controls(){
        LinearLayout root=col(),t=card(root,"紫外线报警阈值");
        t.addView(text("范围0.1～15.0 UVI，固件默认3.0。它只是工程测试触发值，不是狼疮患者的医学安全阈值；低于此值也应防护。个体方案需医生评估，不能据此推算可晒多久。板子重启后恢复固件默认值。",13,MUTED));
        threshold=new EditText(this);threshold.setTextColor(TEXT);threshold.setText("3.0");threshold.setTextSize(24);
        threshold.setInputType(8194);t.addView(threshold);
        addButton(t,"发送报警阈值",()->{try{
            double v=Double.parseDouble(threshold.getText().toString());if(!Double.isFinite(v)||v<.1||v>15)throw new Exception();
            send(Protocol.threshold((int)Math.round(v*10)),"阈值 "+String.format(Locale.US,"%.1f",v));
        }catch(Exception e){toast("请输入0.1～15.0");}});
        boardAlerts=new CheckBox(this);boardAlerts.setTextColor(TEXT);boardAlerts.setText("允许手环阈值报警");boardAlerts.setChecked(true);
        boardAlerts.setOnClickListener(v->send(new byte[]{17,(byte)(boardAlerts.isChecked()?1:0)},"手环报警 "+boardAlerts.isChecked()));t.addView(boardAlerts);
        phoneAlarm=new CheckBox(this);phoneAlarm.setTextColor(TEXT);phoneAlarm.setText("手机声音、振动和通知提醒");
        phoneAlarm.setChecked(getSharedPreferences("settings",0).getBoolean("phoneAlarm",true));phoneAlarm.setOnClickListener(v->getSharedPreferences("settings",0).edit().putBoolean("phoneAlarm",phoneAlarm.isChecked()).apply());t.addView(phoneAlarm);
        t.addView(text("手机提醒最短间隔60秒；受静音/勿扰和通知权限影响。强制报警测试不受手环阈值开关限制。",12,MUTED));
        LinearLayout tests=card(root,"设备测试");pair(tests,"灯环三色 · 9秒",()->send(Protocol.command(18),"灯环自检"),"报警测试 · 10秒",()->send(Protocol.command(22),"报警测试"));
        addButton(tests,"可见光 ALS 测试 · 15秒",()->new AlertDialog.Builder(this).setTitle("可见光测试")
            .setMessage("15秒内暂停紫外线测量。用手遮住传感器再照亮，观察ALS原始值变化。需要本次配套固件。")
            .setPositiveButton("开始",(d,w)->{
                if(ble==null||ble.diagnostic==null||(ble.diagnostic.diagFlags&128)==0){toast("需配套v2固件，请先连接并等待诊断帧");return;}
                send(Protocol.command(21),"ALS测试");
            }).setNegativeButton("取消",null).show());
        commandResult=text("尚未发送命令",13,ACCENT);tests.addView(commandResult);
        tests.addView(text("ATT写入成功只表示接收写请求；需查看数据和实物确认执行效果。",12,MUTED));
        LinearLayout expert=card(root,"高级诊断");expert.addView(text("P0.15高电平用于万用表测量，不是灯色或亮度控制。无需测量时不要开启。",13,MUTED));
        TextView brightness=text("灯环亮度：10%（配套v2固件）",14,MUTED);tests.addView(brightness);
        SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(10);tests.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int n,boolean user){brightness.setText("灯环亮度："+n+"%（默认10%）");}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){
                if(ble==null||ble.diagnostic==null||(ble.diagnostic.diagFlags&128)==0){toast("请连接v2固件并等待诊断帧，旧固件不支持调光");return;}
                send(new byte[]{23,(byte)s.getProgress()},"灯环亮度 "+s.getProgress()+"%");
            }
        });
        pair(expert,"P0.15 高电平",()->new AlertDialog.Builder(this).setMessage("暂时停止灯环协议，让P0.15保持高电平，确认？")
            .setPositiveButton("执行",(d,w)->send(Protocol.command(19),"P0.15高电平")).setNegativeButton("取消",null).show(),
            "恢复灯环控制",()->send(Protocol.command(20),"恢复灯环"));
        return scroll(root);
    }
    private View history(){
        LinearLayout root=col(),c=card(root,"本机历史与导出");
        c.addView(text("服务运行时自动记录有效数据。CSV导出当前会话；旧会话保存在应用私有目录，卸载会删除。",14,MUTED));
        addButton(c,"导出当前会话 CSV",()->{if(ble==null){toast("先启动连接服务");return;}
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv").putExtra(Intent.EXTRA_TITLE,ble.historyName),20);});
        addButton(c,"分享连接日志",()->{if(ble!=null)startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,ble.logs()),"分享排障日志"));});
        LinearLayout l=card(root,"连接与命令日志");logs=text("等待服务",12,MUTED);logs.setTypeface(Typeface.MONOSPACE);logs.setTextIsSelectable(true);l.addView(logs);return scroll(root);
    }
    private View help(){
        LinearLayout root=col(),c=card(root,"三步才是真正收到数据");
        LinearLayout special=card(root,"系统性红斑狼疮与光敏防护");
        special.addView(text(SunProtection.DAILY+"\n\n"+SunProtection.LIMITS+"\n\n部分患者对窗边或人工光源也敏感，应结合个人情况咨询风湿免疫科/皮肤科医生。不要根据本App自行停药或调药；出现新发皮疹或症状加重应联系医生。",14,MUTED));
        addButton(special,"查看NHS狼疮防护说明",()->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.nhs.uk/conditions/lupus/"))));
        addButton(special,"查看Lupus Foundation紫外线防护",()->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.lupus.org/resources/uv-exposure-what-you-need-to-know"))));
        c.addView(text("① 建立蓝牙链路\n② 发现FF00并订阅两路通知\n③ 每秒收到CRC正确的FF01数据\n本App完成②才显示已连接；③有独立的数据时间。",15,TEXT));
        LinearLayout a=card(root,"可见光 ALS 的原理与用途");
        a.addView(text("光照使内部光电二极管产生电流，经过放大、积分和ADC转换成计数。ALS测环境明暗，UVS测紫外线，两通道需要轮流测量。\n\n可用于遮挡检测、明暗判断、光路和I²C排障，未来可做自动调光（当前未实现）。白光手电使ALS升高，不代表UV强。\n\n本次ALS测试15秒后自动恢复UV，测试期间UV不刷新。照度仅按标称参数估算，外壳窗口仍需标定。",14,MUTED));
        LinearLayout b=card(root,"断开后连接不上");
        b.addView(text("1. 退出其他App/电脑连接，等待约6秒释放旧链路。\n2. 点主动断开，再连接上次或重新扫描。\n3. 看日志卡在链路、发现服务还是订阅。\n4. 所有App都扫不到，检查手环重新广播和供电。\n5. 只有本App异常，导出日志。\n\nAndroid GATT status和板端HCI原因码含义不同。自动重连无法修复断电或天线问题。",14,MUTED));
        LinearLayout ota=card(root,"无线升级 OTA");
        ota.addView(text("首次必须用J-Link安装安全版整包。之后用已配对的本机，选择配套的UVGuard_OTA_vXXX.zip，无需解压。只在手环附近进行蓝牙升级，不是互联网远程升级。\n\n升级时暂停采样和报警，请保持稳定供电、手机靠近，不要退出App。新版本必须高于手环已安装版本。Bootloader验证签名，错误包不会替换应用。升级失败时可在这里重选同一ZIP重试。",14,MUTED));
        otaText=text("尚未升级",14,ACCENT);ota.addView(otaText);
        addButton(ota,"选择固件 ZIP / 重试升级",()->{
            if(demo || ble==null){toast("先退出演示并启动连接服务");return;}
            if(ble.otaRunning){toast("升级正在进行");return;}
            new AlertDialog.Builder(this).setTitle("开始无线升级")
                .setMessage("请确认手环供电稳定，手机是已绑定的主人。升级期间不提供紫外线监测。选择本项目签名的OTA ZIP。")
                .setPositiveButton("选择ZIP",(x,y)->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),30)).setNegativeButton("取消",null).show();
        });
        addButton(ota,"取消正在进行的升级",()->{if(ble!=null)ble.cancelOta();});
        LinearLayout d=card(root,"使用边界");
        d.addView(text("Android 8.0及以上，普通蓝牙监测无需账号和网络；AI功能需手机联网与自己的DeepSeek API密钥，可能产生费用，不再需要电脑或USB。只有点击确认后上传快照或日报摘要，不自动上传CSV或日志。后台连接需允许通知和后台活动；前台服务不能保证所有系统永不杀进程。\n\n安全版使用LE Secure Connections加密和绑定；无屏幕/键盘，首次Just Works配对不能防主动中间人，请在可信环境近距离完成。\n\n电量由电压粗略估算，UVI未标定，阈值不保存到Flash。双分区升级不等于自动回退坏程序。用于环境监测与学习，不提供医疗诊断或防晒时长保证。\n\n演示数据为模拟值，不能证明实物功能。",14,MUTED));
        addButton(d,"查看 / 退出演示数据",()->{demo=!demo;show(0);refresh();});return scroll(root);
    }
    private View aiPage(){
        LinearLayout root=col();
        aiSettings(root);
        LinearLayout daily=card(root,"每日统计 · 本机先算，再选择AI建议");
        daily.addView(text("统计本机保存的所有会话，不要求手环当前连接。今天是截至点击时的部分记录；昨天也未必有全天数据。没有记录不能补成零。",14,MUTED));
        pair(daily,"查看今日统计",()->loadDaily(0),"查看昨日统计",()->loadDaily(1));
        dailyText=text("请先选择今天或昨天。统计在本地完成，无需API密钥。",14,TEXT);
        dailyText.setTextIsSelectable(true);dailyText.setMaxLines(13);dailyText.setEllipsize(android.text.TextUtils.TruncateAt.END);daily.addView(dailyText);
        addButton(daily,"展开完整统计与小时趋势",()->{
            if(dailyReport==null){toast("请先选择今日或昨日");return;}
            LinearLayout details=col();TextView report=text(dailyReport.preview(),15,TEXT);report.setTextIsSelectable(true);details.addView(report);
            ScrollView reportScroll=scroll(details);reportScroll.setBackgroundColor(PANEL);
            new AlertDialog.Builder(this).setTitle("本机日报 · "+dailyReport.date).setView(reportScroll).setPositiveButton("关闭",null).show();
        });
        addButton(daily,"让AI给这一天的建议",()->{
            if(aiBusy||dailyLoading){toast("请等待当前操作完成");return;}
            final org.json.JSONObject payload;
            try{if(dailyReport==null)throw new IllegalStateException("先查看今日或昨日统计");payload=DailyPayload.create(dailyReport);}
            catch(Exception e){toast(e.getMessage());return;}
            new AlertDialog.Builder(this).setTitle("发送这份日报摘要？")
                .setMessage("日期："+dailyReport.date+"\n手机将直接通过HTTPS把统计日期、时区、记录覆盖、每小时UVI摘要、ALS/异常和报警标志样本数发给DeepSeek。不会发送原始CSV、位置、设备地址或病史。可能产生API费用，服务商数据政策适用。不会自动上传或控制手环；仅解读这份统计，不会因等待而更新日期。")
                .setPositiveButton("同意并生成建议",(d,w)->requestDaily(payload)).setNegativeButton("取消",null).show();
        });
        addButton(daily,"查看日报发送字段",()->{
            try{if(dailyReport==null)throw new IllegalStateException("先查看今日或昨日统计");
                new AlertDialog.Builder(this).setTitle("只发送摘要，不发送CSV").setMessage(DailyPayload.create(dailyReport).toString(2)).setPositiveButton("关闭",null).show();
            }catch(Exception e){toast(e.getMessage());}
        });
        dailyAnswer=text("尚未生成AI日报。统计与固定防晒提示不依赖AI。",15,ACCENT);
        dailyAnswer.setTextIsSelectable(true);daily.addView(dailyAnswer);
        addButton(daily,"分享当前日报",()->{
            if(dailyReport==null||dailyLoading){toast("请先生成本地统计");return;}
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT,dailyReport.preview()+"\n\n"+dailyAnswer.getText()),"自行选择日报接收方"));
        });
        LinearLayout ask=card(root,"解读当次数据");
        aiQuestion=new EditText(this);aiQuestion.setTextColor(TEXT);aiQuestion.setHintTextColor(MUTED);
        aiQuestion.setHint("选填：例如为什么室内UVI为0？最多500字");
        aiQuestion.setMinLines(2);aiQuestion.setMaxLines(5);
        aiQuestion.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(500)});ask.addView(aiQuestion);
        addButton(ask,"查看将发送的数据",()->{
            try{String preview=AiSnapshot.create(ble,demo).toString(2);new AlertDialog.Builder(this).setTitle("测量快照 · 不含设备地址")
                .setMessage(preview).setPositiveButton("关闭",null).show();}
            catch(Exception ex){toast(ex.getMessage()==null?"暂时没有有效读数":ex.getMessage());}
        });
        addButton(ask,"AI解读当前数据",()->{
            if(aiBusy){toast("正在等待上一条请求");return;}
            final org.json.JSONObject payload;
            try{payload=new org.json.JSONObject().put("consent",true).put("question",aiQuestion.getText().toString().trim()).put("snapshot",AiSnapshot.create(ble,demo));}
            catch(Exception ex){toast(ex.getMessage());return;}
            final long captured=SystemClock.elapsedRealtime();
            new AlertDialog.Builder(this).setTitle("发送数据到DeepSeek？")
                .setMessage("手机将直接通过HTTPS把点击时UV/ALS、电池、数据状态和最近5分钟统计，以及你填写的问题发送给DeepSeek。可能产生API费用，服务商数据政策适用。不会上传设备地址或日志，也不会修改手环。不要输入个人敏感信息。若确认等待过久，请重新获取快照。")
                .setPositiveButton("同意并解读",(d,w)->requestSnapshot(payload,captured)).setNegativeButton("取消",null).show();
        });
        aiAnswer=text("尚未调用AI。请在本页顶部保存并验证自己的DeepSeek API密钥。",15,TEXT);
        aiAnswer.setTextIsSelectable(true);ask.addView(aiAnswer);
        ask.addView(text("AI可能出错，不是实时报警或医疗建议。每次建议至少间隔15秒，本机每日最多30次（UTC日），发起后的失败也计数。此限制不是账户消费上限；清除应用数据/卸载会重置，请在官方平台关注余额。",12,MUTED));
        addButton(ask,"清除本页回答",()->{
            if(aiBusy){toast("请等待当前请求结束");return;}
            aiQuestion.setText("");aiAnswer.setText("已清除本页回答。");dailyAnswer.setText("已清除本页AI日报回答；密钥和本地测量历史未删除。");
        });
        return scroll(root);
    }
    private void aiSettings(LinearLayout root){
        LinearLayout config=card(root,"DeepSeek设置 · 手机直连 v3.4");
        config.addView(text("不再需要电脑或USB。手机联网，填自己的API密钥（不是DeepSeek登录密码或旧电脑口令）。仅确认后发送测量摘要，可能产生API费用。",14,MUTED));
        aiKeyStatus=text(aiKeys.hasSaved()?"已保存加密密钥 · 尚未在本次验证":"尚未保存API密钥",14,ACCENT);config.addView(aiKeyStatus);
        aiKey=new EditText(this);aiKey.setTextColor(TEXT);aiKey.setHintTextColor(MUTED);aiKey.setHint("输入 sk- 开头的 DeepSeek API Key");
        aiKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        aiKey.setSingleLine(true);aiKey.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        aiKey.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);aiKey.setSaveEnabled(false);
        aiKey.setOnFocusChangeListener((v,focus)->{
            if(focus)getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }); // Prevent screenshots/recordings while entering a provider credential.
        aiKey.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(259)});config.addView(aiKey);
        pair(config,"保存密钥",()->{
            if(aiBusy){toast("请等待当前请求结束");return;}
            String secret=aiKey.getText().toString().trim();
            if(!DeepSeekPolicy.validKey(secret)){toast("请填写完整的sk-开头API密钥，不是登录密码或旧电脑口令");return;}
            try{aiKeys.save(secret);aiKey.setText("");((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(aiKey.getWindowToken(),0);
                aiKeyStatus.setText("已加密保存 · 点击验证密钥");toast("已保存；输入框已清空，不回显密钥");}
            catch(Exception e){aiKeyStatus.setText("保存失败，请重试；未展示密钥或内部错误");}
        },"验证密钥",()->{
            if(aiBusy){toast("正在等待上一条请求");return;}
            if(!aiKeys.hasSaved()){toast("请先保存API密钥");return;}
            if(aiKey.length()>0){toast("输入框里有未保存内容，请先保存或清空");return;}
            new AlertDialog.Builder(this).setTitle("验证DeepSeek密钥？")
                .setMessage("将携带保存的密钥访问官方HTTPS模型列表，不发送测量、问题或病史，不生成AI回答。验证通过不代表账户余额充足。")
                .setPositiveButton("验证",(d,w)->checkAiKey()).setNegativeButton("取消",null).show();
        });
        addButton(config,"删除已保存的密钥",()->{
            if(aiBusy){toast("请等待当前请求结束");return;}
            new AlertDialog.Builder(this).setTitle("删除手机上的密钥？").setMessage("仅删除本机加密密钥，不删除测量历史，也不会注销DeepSeek平台密钥；若怀疑泄露，请到官方平台撤销。")
                .setPositiveButton("删除",(d,w)->{try{aiKeys.delete();aiKey.setText("");aiKeyStatus.setText("已删除 · AI调用已停用");}catch(Exception e){toast("删除未完成，请重试");}}).setNegativeButton("取消",null).show();
        });
        config.addView(text("密钥用Android系统密钥库加密保存在本机，保存后不回显；不是写进安装包。不能抵御已被控制/root的手机或恶意输入法。不要分享密钥或录屏输入过程。",12,MUTED));
        config.addView(text("光敏防护辅助：AI不能给医学安全阈值或可晒时长；UVI为0、低值或未报警都不代表安全。",13,ACCENT));
        addButton(config,"打开DeepSeek官方API平台",()->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://platform.deepseek.com/"))));
    }
    private boolean aiReady(){
        if(aiBusy){toast("正在等待上一条请求");return false;}
        if(!aiKeys.hasSaved()){toast("先在AI页顶部保存自己的API密钥");return false;}
        if(aiKey.length()>0){toast("密钥输入框有未保存内容，请先保存或清空");return false;}
        return true;
    }
    private void checkAiKey(){
        if(!aiReady())return;aiBusy=true;aiKeyStatus.setText("正在访问DeepSeek官方模型列表…");
        aiClient.call(null,true,(success,result)->{aiBusy=false;aiKeyStatus.setText(result);});
    }
    private void loadDaily(int daysAgo){
        if(dailyLoading||aiBusy){toast("请等待当前操作完成");return;}
        dailyLoading=true;dailyReport=null;
        dailyText.setText("正在读取本机历史并统计…不会上传数据。");dailyAnswer.setText("尚未生成本次统计的AI建议。");
        final java.time.ZoneId zone=java.time.ZoneId.systemDefault();
        final long now=System.currentTimeMillis();
        final java.time.LocalDate date=java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(daysAgo);
        Runnable work=()->{
            try{DailyReport.Result result=DailyReport.read(getFilesDir(),date,zone,now);
                if(!destroyed)handler.post(()->{if(destroyed)return;dailyLoading=false;dailyReport=result;dailyText.setText(result.preview());});
            }catch(Exception e){if(!destroyed)handler.post(()->{if(destroyed)return;dailyLoading=false;dailyText.setText("读取失败，请重试。没有把缺失记录当成零。");});}
        };
        if(ble!=null)ble.readDaily(work);else dailyWorker.execute(work);
    }
    private void requestDaily(org.json.JSONObject payload){
        if(!aiReady())return;
        aiBusy=true;dailyAnswer.setText("等待DeepSeek生成这份日报建议…不会自动重试。");
        aiClient.call(payload,false,(success,result)->{aiBusy=false;dailyAnswer.setText(result);});
    }
    private void requestSnapshot(org.json.JSONObject payload,long captured){
        if(!aiReady())return;
        try{
            org.json.JSONObject snapshot=payload.getJSONObject("snapshot");
            long age=snapshot.getLong("age_ms")+SystemClock.elapsedRealtime()-captured;
            AiSnapshot.create(ble,demo); // Also reject disconnect, demo, OTA or sensor error since the dialog opened.
            if(age<0||age>10000)throw new IllegalStateException("快照已超过10秒，请重新点击解读并确认。");
            snapshot.put("age_ms",age);
        }catch(Exception e){toast(e.getMessage()==null?"没有有效新数据":e.getMessage());return;}
        aiBusy=true;aiAnswer.setText("等待DeepSeek解读…蓝牙采样和原有报警继续运行；离开页面不代表请求取消。");
        aiClient.call(payload,false,(success,result)->{aiBusy=false;aiAnswer.setText(result);});
    }
    private void send(byte[] b,String label){if(demo){toast("演示模式不发命令");return;}if(ble==null){toast("先连接手环");return;}ble.send(b,label);}
    @Override protected void onActivityResult(int r,int code,Intent i){
        super.onActivityResult(r,code,i);
        if(code!=RESULT_OK||i==null||i.getData()==null||ble==null)return;
        if(r==20)ble.exportHistory(i.getData());
        if(r==30){ble.startOta(i.getData());show(4);}
    }
    public void changed(){if(page==1)renderDevices();refresh();}
    private void renderDevices(){
        if(deviceList==null||ble==null)return;deviceList.removeAllViews();ArrayList<BleService.Device> list=new ArrayList<>(ble.devices.values());
        list.sort((a,b)->a.match!=b.match?(a.match?-1:1):Integer.compare(b.rssi,a.rssi));
        if(list.isEmpty()){deviceList.addView(text(ble.scanning?"扫描中…":"尚无设备，请扫描",14,MUTED));return;}
        int n=0;for(BleService.Device d:list){if(++n>30)break;
            addButton(deviceList,(d.match?"★ ":"")+d.name+" · "+d.rssi+" dBm\n"+d.address,()->{demo=false;
                if(d.match)ble.connect(d.address);else new AlertDialog.Builder(this).setMessage("该设备未标识UV-Guard，仍连接？")
                .setPositiveButton("连接",(x,y)->ble.connect(d.address)).setNegativeButton("取消",null).show();});
        }
    }
    private String age(long time){return time==0?"未收到":(SystemClock.elapsedRealtime()-time)/1000+"秒前";}
    private String number(double x){return Double.isNaN(x)?"—":String.format(Locale.US,"%.1f",x);}
    private void refresh(){
        if(status==null)return;
        if(sunAdvice!=null) {
            boolean fresh=!demo && ble!=null && ble.ready && ble.realtime!=null &&
                SystemClock.elapsedRealtime()-ble.lastRealtimeAt<=10000;
            sunAdvice.setText(demo?"演示数据不能用于防护决策。" : SunProtection.current(ble==null?null:ble.realtime,fresh));
        }
        if(demo){status.setText("演示模式 · 以下全部为模拟数据");hero.setText("3.2");caption.setText("中等 · 模拟值");
            alarm.setText("模拟数据，不反映手环状态");battery.setText("演示：76% · 3.98 V");raw.setText("模拟 UV raw：307");
            summary.setText("演示曲线，不写入CSV");diag.setText("模拟：PART_ID=0xB2 · 18位 / 3倍");chart.invalidate();return;}
        if(ble==null){status.setText("连接服务未启动");hero.setText("—");caption.setText("在连接页启动并授权");
            alarm.setText("未连接，不能判断报警");battery.setText("电量 — · 电压 —");raw.setText("原始值 —");
            diag.setText("诊断数据未收到");link.setText("连接服务未启动");chart.invalidate();return;}
        status.setText(ble.state);auto.setChecked(ble.autoReconnect());
        otaText.setText(ble.otaStatus);
        link.setText("地址："+(ble.address.isEmpty()?"—":ble.address)+"\nRSSI："+(ble.rssi==-127?"—":ble.rssi+" dBm")
            +"\n实时："+age(ble.lastRealtimeAt)+" · 诊断："+age(ble.lastDiagAt)
            +"\n已连接："+(ble.connectedAt==0?"—":(SystemClock.elapsedRealtime()-ble.connectedAt)/1000+"秒")
            +"\n上次断开："+(ble.disconnectReason.isEmpty()?"—":ble.disconnectReason));
        Protocol.Frame f=ble.realtime,d=ble.diagnostic;
        if(f!=null){
            boolean stale=SystemClock.elapsedRealtime()-ble.lastRealtimeAt>10000;
            if(!ble.ready||stale){hero.setText("—");caption.setText("数据已过期，请查看连接");}
            else if(f.als()){hero.setText("ALS");caption.setText("可见光测试 · UV监测暂停");}
            else if((f.flags&1)!=0){hero.setText("—");caption.setText("传感器错误 · 不能判断UV");}
            else if(!f.validUv()){hero.setText("—");caption.setText("等待传感器新样本");}
            else{hero.setText(number(f.uviX10/10.0));caption.setText("UVI估算 · 非个人安全判定");}
            alarm.setText(!ble.ready||stale?"历史状态，当前报警未知":(f.flags&2)!=0?"手环报警/测试：请核对状态并避光防护":(f.flags&8)!=0?"尚未触发工程阈值 ≠ 可以晒太阳":"工程阈值报警已关闭，仍需防护");alarm.setTextColor(ble.ready&&!stale&&(f.flags&2)!=0?RED:ACCENT);
            battery.setText(f.battery+"%"+((f.flags&4)!=0?" · 充电中":"")+(d!=null?String.format(Locale.US," · %.3f V",d.mv/1000.0):" · 电压待诊断"));
            raw.setText((f.als()?"ALS":"UV")+" raw（FF01限16位）："+f.raw+" · 序号 "+f.sample);boardAlerts.setChecked((f.flags&8)!=0);
        }else{hero.setText("—");caption.setText("等待有效FF01数据");battery.setText("电量 — · 电压 —");raw.setText("原始值 —");alarm.setText("等待报警状态");}
        summary.setText("UV样本 "+ble.totalSamples+" · 最小 "+number(ble.minUv)+" · 最大 "+number(ble.maxUv)+" · 均值 "+number(ble.totalSamples==0?Double.NaN:ble.sumUv/ble.totalSamples)
            +"\n有效帧 "+ble.validFrames+" · 错帧 "+ble.badFrames+" · 序号缺口 "+ble.dataGaps);
        if(d!=null){double lux=Protocol.estimatedLux(d);diag.setText(String.format(Locale.US,"CTRL 0x%02X · RATE 0x%02X · GAIN 0x%02X\nPART_ID 0x%02X · STATUS 0x%02X\n%s完整raw：%d · ADC：%d\n%s",d.ctrl,d.rate,d.gain,d.id,d.status,d.als()?"ALS":"UV",d.raw,d.adc,
            d.als()?(Double.isNaN(lux)?"ALS参数不匹配，未换算lx":String.format(Locale.US,"照度估算 %.1f lx · 未标定",lux)):"UV模式 · 照度不适用"));}
        else diag.setText("诊断帧每约5秒刷新；等待本次连接数据。");
        commandResult.setText(ble.lastCommand);logs.setText(ble.logs()+(ble.exportResult.isEmpty()?"":"\n"+ble.exportResult));chart.invalidate();
    }
    private final Runnable ticker=new Runnable(){public void run(){refresh();handler.postDelayed(this,1000);}};
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onPause(){if(aiKey!=null)aiKey.setText("");super.onPause();}
    @Override public void onDestroy(){destroyed=true;aiClient.close();dailyWorker.shutdownNow();handler.removeCallbacksAndMessages(null);if(ble!=null)ble.listen(null);if(bound)unbindService(binding);super.onDestroy();}
    private final class Chart extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Chart(){super(MainActivity.this);}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),height=getHeight(),left=dp(30),bottom=height-dp(24),top=dp(12),right=w-dp(7);
            double max=5;ArrayList<BleService.Point> values=new ArrayList<>();
            if(demo){for(int i=0;i<80;i++)values.add(new BleService.Point(i,2.5+Math.sin(i*.12)));}
            else if(ble!=null)values.addAll(ble.points);
            for(BleService.Point p:values)if(Double.isFinite(p.uvi))max=Math.max(max,Math.ceil(p.uvi));
            paint.setTextSize(dp(10));paint.setStrokeWidth(dp(1));
            for(int i=0;i<=2;i++){float y=bottom-(bottom-top)*i/2;paint.setColor(0xff395057);c.drawLine(left,y,right,y,paint);paint.setColor(MUTED);c.drawText(String.format(Locale.US,"%.0f",max*i/2),0,y,paint);}
            if(values.size()<2){c.drawText("等待至少两个有效样本",left+dp(8),height/2,paint);return;}
            paint.setColor(ACCENT);paint.setStrokeWidth(dp(2));paint.setStyle(Paint.Style.STROKE);Path path=new Path();boolean connected=false;
            long start=values.get(0).time,span=Math.max(1,values.get(values.size()-1).time-start);
            for(BleService.Point p:values){if(!Double.isFinite(p.uvi)){connected=false;continue;}
                float x=left+(right-left)*(p.time-start)/span,y=bottom-(float)(p.uvi/max)*(bottom-top);
                if(connected)path.lineTo(x,y);else path.moveTo(x,y);connected=true;}
            c.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(MUTED);c.drawText(demo?"模拟数据":"仅绘制有效UV，ALS期间留空",left,bottom+dp(18),paint);
        }
    }
}
