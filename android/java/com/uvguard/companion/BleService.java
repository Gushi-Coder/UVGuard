package com.uvguard.companion;

import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipFile;
import org.json.JSONObject;
import no.nordicsemi.android.dfu.*;

/** 所有状态只在主线程改变。旧Gatt回调必须先检查实例身份。 */
public final class BleService extends Service {
    public interface Listener { void changed(); }
    public static final class Device {
        public String name, address;
        public int rssi;
        public boolean match;
    }
    public static final class Point {
        public long time;
        public double uvi;
        Point(long t, double u) { time=t; uvi=u; }
    }
    public final class LocalBinder extends Binder { public BleService get() { return BleService.this; } }
    private final Handler h = new Handler(Looper.getMainLooper());
    private final HandlerThread ioThread = new HandlerThread("UVGuard-history");
    private Handler io;
    private final ArrayDeque<Op> ops = new ArrayDeque<>();
    private final RetryPolicy retry = new RetryPolicy();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    public final LinkedHashMap<String,Device> devices = new LinkedHashMap<>();
    public final ArrayList<Point> points = new ArrayList<>();
    public Protocol.Frame realtime, diagnostic;
    public String state="未连接", address="", deviceName="", lastCommand="尚未发送",
        disconnectReason="", exportResult="", historyName="";
    public long connectedAt, lastRealtimeAt, lastDiagAt, validFrames, badFrames, dataGaps, totalSamples;
    public int rssi=-127;
    public double minUv=Double.NaN, maxUv=Double.NaN, sumUv;
    public boolean ready, scanning, wantConnection;
    public boolean otaRunning, secureFirmware;
    public String otaStatus="尚未升级";
    private DfuServiceController dfuController;
    private boolean awaitingBond;
    private static final UUID BONDED_DFU=UUID.fromString("8ec90004-f315-4f60-9fb8-838830daea50");
    private final BroadcastReceiver bondReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction()))return;
            BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if(gatt==null || device==null || !device.equals(gatt.getDevice()) || !awaitingBond)return;
            int bond;
            try{bond=device.getBondState();}catch(SecurityException e){wantConnection=false;fail("蓝牙权限被撤销");return;}
            if(bond==BluetoothDevice.BOND_BONDED){awaitingBond=false;log("系统配对完成，继续订阅加密通知");beginSubscriptions(gatt);}
            else if(bond==BluetoothDevice.BOND_NONE){wantConnection=false;fail("配对失败：检查120秒配对窗口；换主人需长按SW1 8秒，并在手机取消旧配对");}
        }
    };
    private final DfuProgressListenerAdapter dfuProgress=new DfuProgressListenerAdapter(){
        @Override public void onDeviceConnecting(String a){dfuState("升级：连接手环…");}
        @Override public void onEnablingDfuMode(String a){dfuState("升级：切换到Bootloader…");}
        @Override public void onDfuProcessStarting(String a){dfuState("升级：校验升级包…");}
        @Override public void onProgressChanged(String a,int percent,float speed,float avg,int part,int total){dfuState("升级传输 "+percent+"% · 请保持供电和近距离");}
        @Override public void onFirmwareValidating(String a){dfuState("升级：校验固件…");}
        @Override public void onDfuCompleted(String a){finishDfu(true,"传输完成，正在重连确认应用数据");}
        @Override public void onDfuAborted(String a){finishDfu(false,"升级已取消；等约2分钟让Bootloader超时，再连接；也可重新尝试升级");}
        @Override public void onError(String a,int code,int type,String message){finishDfu(false,"升级失败 "+code+"："+message+"；保持供电，见使用说明中的恢复步骤");}
    };
    private void dfuState(String s){h.post(()->{if(!otaRunning)return;otaStatus=s;setState(s);});}
    private void finishDfu(boolean success,String s){h.post(()->{
        if(!otaRunning)return;otaRunning=false;dfuController=null;otaStatus=s;log(s);setState(s);
        if(success){confirmingOta=true;wantConnection=true;h.postDelayed(()->{if(!destroyed&&wantConnection&&!otaRunning)reconnectLast();},6000);}
    });}
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic command;
    private Listener listener;
    private Op active;
    private int pendingSubscriptions;
    private int serviceRetries;
    private boolean confirmingOta;
    private long lastSample=-1, lastAlarm, connectStarted;
    private int scanEpoch;
    private boolean reconnectScan, destroyed;
    private File history;
    private Runnable connectTimer, scanTimer, opTimer, reconnectTimer;
    private boolean scanUiScheduled;
    private final Runnable scanUi=()->{scanUiScheduled=false;changed();};
    private ToneGenerator tone;
    private static final String CHANNEL="uvguard_link", ALARM_CHANNEL="uvguard_alert";
    private static final class Op {
        BluetoothGattDescriptor descriptor;
        byte[] bytes;
        String label;
        Op(BluetoothGattDescriptor d, byte[] b, String text) { descriptor=d; bytes=b; label=text; }
    }

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter bondFilter=new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(bondReceiver,bondFilter,Context.RECEIVER_EXPORTED);
        else registerReceiver(bondReceiver,bondFilter);
        DfuServiceListenerHelper.registerProgressListener(this,dfuProgress);
        DfuServiceInitiator.createDfuNotificationChannel(this,"固件升级","显示手环固件升级进度",false);
        ioThread.start(); io=new Handler(ioThread.getLooper());
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=manager==null ? null : manager.getAdapter();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"手环连接",NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(ALARM_CHANNEL,"紫外线提醒",NotificationManager.IMPORTANCE_HIGH));
        Notification n=notification("准备连接手环");
        if (Build.VERSION.SDK_INT>=29) startForeground(7,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(7,n);
        historyName="uvguard_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".csv";
        history=new File(getFilesDir(),historyName);
        writeCsv("timestamp,type,sample,uvi_x10,raw,battery_percent,flags,ctrl,rate,gain,part_id,status,adc,battery_mv,diag_flags");
        log("已启动；历史仅保存在本机。新App不替换旧测试台。");
        h.post(watchdog);
    }
    private Notification notification(String text) {
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("UV-Guard 随行").setContentText(text).setContentIntent(pi).setOngoing(true).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
    public void listen(Listener l) { listener=l; if(l!=null) l.changed(); }
    private void changed() { if(listener!=null)listener.changed(); }
    private void setState(String s) {
        state=s; getSystemService(NotificationManager.class).notify(7,notification(s)); changed();
    }
    private String stamp() { return new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date()); }
    private void log(String s) {
        log.add(stamp()+"  "+s); while(log.size()>200)log.poll();
        android.util.Log.i("UVGuard",s); changed();
    }
    public String logs() { StringBuilder b=new StringBuilder(); for(String s:log)b.append(s).append('\n'); return b.toString(); }
    public boolean autoReconnect() { return getSharedPreferences("settings",0).getBoolean("auto",true); }
    public void autoReconnect(boolean enabled) {
        getSharedPreferences("settings",0).edit().putBoolean("auto",enabled).apply();
        if(!enabled){
            cancelReconnect();
            if(reconnectScan && scanning){stopScan();setState("自动重连已关闭");}
        }
        changed();
    }
    private boolean usable() {
        try { return adapter!=null && adapter.isEnabled(); }
        catch(SecurityException e) { log("蓝牙权限不足"); return false; }
    }
    public void scan() {
        if(otaRunning){log("升级中，请等完成再扫描");return;}
        if(wantConnection || gatt!=null) { log("请先断开连接再扫描其他设备"); return; }
        devices.clear(); startScan(false);
    }
    private void startScan(boolean reconnect) {
        stopScan();
        if(!usable()) { setState("蓝牙未开启或权限不足"); scheduleReconnect(); return; }
        reconnectScan=reconnect; scanning=true; int epoch=++scanEpoch;
        scanner=adapter.getBluetoothLeScanner();
        try {
            if(scanner==null)throw new IllegalStateException("扫描器不可用");
            scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
            setState(reconnect ? "重连中：扫描手环广播" : "正在扫描（15秒）");
            scanTimer=()->{
                if(epoch!=scanEpoch)return;
                stopScan();
                if(reconnect){log("未发现手环，等待下一轮");scheduleReconnect();}
                else setState("扫描结束 · "+devices.size()+"台设备");
            };
            h.postDelayed(scanTimer,15000);
        }catch(SecurityException e){scanning=false;wantConnection=false;setState("请重新允许附近设备权限");}
        catch(Exception e){ scanning=false; log("扫描失败："+e.getMessage());setState("扫描失败");scheduleReconnect(); }
    }
    public void stopScan() {
        ++scanEpoch;
        if(scanTimer!=null)h.removeCallbacks(scanTimer);
        try { if(scanning && scanner!=null)scanner.stopScan(scanCallback); }catch(SecurityException ignored){}catch(Exception ignored){}
        scanning=false;
    }
    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult r){h.post(()->{
            if(!scanning)return;
            String addr=r.getDevice().getAddress();
            String name=r.getScanRecord()==null ? null : r.getScanRecord().getDeviceName();
            if(name==null) {try{name=r.getDevice().getName();}catch(SecurityException ignored){}}
            Device d=devices.get(addr);
            if(d==null){d=new Device();d.address=addr;devices.put(addr,d);}
            d.name=name==null ? "未命名设备" : name;d.rssi=r.getRssi();
            d.match=d.name.toLowerCase(Locale.US).contains("uv-guard");
            if(r.getScanRecord()!=null && r.getScanRecord().getServiceUuids()!=null)
                d.match |= r.getScanRecord().getServiceUuids().contains(new ParcelUuid(Protocol.SERVICE));
            if(reconnectScan && wantConnection && addr.equals(address)){
                stopScan(); open(r.getDevice()); return;
            }
            if(!scanUiScheduled){scanUiScheduled=true;h.postDelayed(scanUi,600);}
        });}
        @Override public void onScanFailed(int code){h.post(()->{
            stopScan();log("扫描错误="+code);setState("扫描失败");scheduleReconnect();
        });}
    };
    public void connect(String addr) {
        if(otaRunning){log("升级服务正在使用蓝牙，请勿同时连接");return;}
        cancelReconnect();stopScan();wantConnection=true;address=addr;
        getSharedPreferences("settings",0).edit().putString("lastAddress",addr).apply();
        try { open(adapter.getRemoteDevice(addr)); }
        catch(Exception e){log("连接失败："+e.getMessage());scheduleReconnect();}
    }
    public void reconnectLast(){
        String last=getSharedPreferences("settings",0).getString("lastAddress","");
        if(last.isEmpty()){log("没有上次设备，请先扫描");return;}
        connect(last);
    }
    private void open(BluetoothDevice device) {
        closeGatt();serviceRetries=0;connectStarted=SystemClock.elapsedRealtime();
        setState("连接中…");log("连接 "+address);
        try {
            gatt=device.connectGatt(this,false,gattCallback,BluetoothDevice.TRANSPORT_LE);
            if(gatt==null)throw new IllegalStateException("connectGatt返回空");
            connectTimer=()->{if(!ready)fail("连接/订阅超时");};
            h.postDelayed(connectTimer,60000);
        }catch(SecurityException e){wantConnection=false;fail("蓝牙连接权限被撤销");}
        catch(Exception e){fail("连接启动失败："+e.getMessage());}
    }
    private void cancelReconnect(){if(reconnectTimer!=null)h.removeCallbacks(reconnectTimer);}
    public void disconnect() {
        if(otaRunning){log("升级中请使用取消升级按钮");return;}
        wantConnection=false;cancelReconnect();stopScan();
        disconnectReason="用户主动断开";
        closeGatt();setState("已断开，可再次点击连接");log("用户断开：取消自动重连和所有旧队列");
    }
    private void closeGatt() {
        awaitingBond=false;secureFirmware=false;
        ready=false; connectedAt=0;command=null;pendingSubscriptions=0;ops.clear();active=null;
        if(connectTimer!=null)h.removeCallbacks(connectTimer);
        if(opTimer!=null)h.removeCallbacks(opTimer);
        BluetoothGatt old=gatt;gatt=null;
        if(old!=null){try{old.disconnect();}catch(SecurityException ignored){}catch(Exception ignored){} try{old.close();}catch(SecurityException ignored){}catch(Exception ignored){}}
    }
    private void fail(String why) {
        disconnectReason=why;log(why);closeGatt();setState("连接已断开");scheduleReconnect();
    }
    private void scheduleReconnect() {
        if(destroyed || !wantConnection || !autoReconnect())return;
        cancelReconnect();long delay=retry.nextDelayMs();
        setState("等待重连 · "+delay/1000+"秒");
        reconnectTimer=()->{if(wantConnection && !ready)startScan(true);};
        h.postDelayed(reconnectTimer,delay);
    }
    private final BluetoothGattCallback gattCallback=new BluetoothGattCallback(){
        @Override public void onServiceChanged(BluetoothGatt g){h.post(()->{
            if(g!=gatt)return;
            // Bootloader和应用的服务表不同，不能继续拿旧handle写命令。
            ready=false;command=null;pendingSubscriptions=0;ops.clear();completed();
            log("收到Service Changed，重新发现服务");
            discoverLater(g,1000);
        });}
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int newState){h.post(()->{
            if(g!=gatt)return;
            if(status!=BluetoothGatt.GATT_SUCCESS){fail("Android GATT status="+status);return;}
            if(newState==BluetoothProfile.STATE_CONNECTED){
                setState("已建立链路，发现服务…");
                // 已绑定设备先等加密和Service Changed处理，避免使用DFU旧缓存。
                discoverLater(g,2000);
            }else if(newState==BluetoothProfile.STATE_DISCONNECTED)fail("链路断开：status="+status);
        });}
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){h.post(()->{
            if(g!=gatt)return;
            if(status!=0){fail("服务发现失败="+status);return;}
            BluetoothGattService service=g.getService(Protocol.SERVICE);
            if(service==null){
                if(g.getService(UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb"))!=null){
                    log("暂见DFU服务表，等待应用服务刷新（"+(serviceRetries+1)+"）");
                    if(++serviceRetries<=3)discoverLater(g,3000);
                    else fail("升级后服务表尚未刷新，重建连接");
                }else{wantConnection=false;fail("设备没有FF00服务，请确认选择UV-Guard");}
                return;
            }
            command=service.getCharacteristic(Protocol.COMMAND);
            BluetoothGattCharacteristic rt=service.getCharacteristic(Protocol.REALTIME), dg=service.getCharacteristic(Protocol.DIAG);
            if(command==null || rt==null || dg==null){wantConnection=false;fail("FF01/02/03不完整");return;}
            BluetoothGattService dfu=g.getService(UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb"));
            secureFirmware=dfu!=null && dfu.getCharacteristic(BONDED_DFU)!=null;
            try{
            if(secureFirmware && g.getDevice().getBondState()!=BluetoothDevice.BOND_BONDED){
                awaitingBond=true;setState("请在系统提示中配对；手环只允许一个主人");
                try{if(g.getDevice().getBondState()!=BluetoothDevice.BOND_BONDING && !g.getDevice().createBond()){
                    wantConnection=false;fail("系统无法启动配对，请检查蓝牙设置");
                }}catch(Exception e){wantConnection=false;fail("配对启动失败："+e.getMessage());}
            }else beginSubscriptions(g);
            }catch(SecurityException e){wantConnection=false;fail("蓝牙权限被撤销，请重新授权");}
        });}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){h.post(()->{
            if(g!=gatt || active==null || active.descriptor!=d)return;
            if(status!=0){fail("订阅失败="+status);return;}
            completed(); pendingSubscriptions--;
            if(pendingSubscriptions==0){
                ready=true;retry.ready();h.removeCallbacks(connectTimer);connectedAt=SystemClock.elapsedRealtime();
                lastRealtimeAt=0;lastDiagAt=0;lastSample=-1;rssi=-127;realtime=null;diagnostic=null;
                try{deviceName=g.getDevice().getName();}catch(SecurityException ignored){}catch(Exception ignored){}
                setState(secureFirmware?"已配对 · 两路通知已订阅":"已连接 · 旧固件未启用安全升级");log("READY，订阅完成，可以接收和发送");
                try{g.readRemoteRssi();}catch(SecurityException e){wantConnection=false;fail("蓝牙权限被撤销");return;}
            }else setState("订阅数据 1/2…");
            pump();
        });}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){h.post(()->{
            if(g!=gatt || active==null || active.descriptor!=null)return;
            String label=active.label;
            completed();
            lastCommand=status==0 ? label+"：ATT写入成功（执行结果需看数据）" : label+"：失败 "+status;
            log(lastCommand);pump();
        });}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){
            byte[] data=c.getValue();if(data!=null)data=data.clone();
            final byte[] bytes=data;h.post(()->{if(g==gatt)receive(c.getUuid(),bytes);});
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){
            byte[] copy=value.clone();h.post(()->{if(g==gatt)receive(c.getUuid(),copy);});
        }
        @Override public void onReadRemoteRssi(BluetoothGatt g,int value,int status){h.post(()->{
            if(g==gatt && status==0){rssi=value;changed();}
        });}
    };
    private void discoverLater(BluetoothGatt g,long delay){
        h.postDelayed(()->{
            if(g!=gatt || destroyed)return;
            try{if(!g.discoverServices())fail("无法开始服务发现");}
            catch(SecurityException e){wantConnection=false;fail("蓝牙权限被撤销");}
            catch(Exception e){fail("服务发现异常："+e.getMessage());}
        },delay);
    }
    private void beginSubscriptions(BluetoothGatt g){
        if(g!=gatt || pendingSubscriptions!=0)return;
        BluetoothGattService s=g.getService(Protocol.SERVICE);
        if(s==null){fail("服务已变化，请重新连接");return;}
        pendingSubscriptions=2;setState("订阅数据 0/2…");
        if(!subscribe(g,s.getCharacteristic(Protocol.REALTIME)) || !subscribe(g,s.getCharacteristic(Protocol.DIAG))){fail("通知描述符缺失或无法启用");return;}
        pump();
    }
    private boolean subscribe(BluetoothGatt g,BluetoothGattCharacteristic c) {
        try{if(c==null || !g.setCharacteristicNotification(c,true))return false;}
        catch(SecurityException e){log("订阅需要蓝牙权限");return false;}
        BluetoothGattDescriptor d=c.getDescriptor(Protocol.CCCD);
        if(d==null)return false;
        ops.add(new Op(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,"订阅 "+c.getUuid()));
        return true;
    }
    public void send(byte[] bytes,String label) {
        if(!ready){log("未完成连接，不能发送："+label);return;}
        if(ops.size()>=12){log("命令太快，请等待");return;}
        ops.add(new Op(null,bytes.clone(),label));pump();
    }
    @SuppressWarnings("deprecation")
    private void pump() {
        if(gatt==null || active!=null || ops.isEmpty())return;
        active=ops.poll(); boolean ok;
        try{
            if(active.descriptor!=null){
                active.descriptor.setValue(active.bytes);ok=gatt.writeDescriptor(active.descriptor);
            }else{
                command.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                command.setValue(active.bytes);ok=gatt.writeCharacteristic(command);
            }
        }catch(SecurityException e){wantConnection=false;fail("GATT权限被撤销");return;}
        catch(Exception e){fail("GATT操作异常："+e.getMessage());return;}
        if(!ok){fail("GATT拒绝操作，关闭旧会话后重连");return;}
        Op expected=active;
        opTimer=()->{if(active==expected)fail("GATT操作10秒无应答："+expected.label);};
        h.postDelayed(opTimer,10000);
    }
    private void completed(){if(opTimer!=null)h.removeCallbacks(opTimer);active=null;}
    private void receive(UUID characteristic,byte[] bytes) {
        Protocol.Frame f=Protocol.parse(bytes);
        if(f==null || (f.realtime ? !Protocol.REALTIME.equals(characteristic) : !Protocol.DIAG.equals(characteristic))){
            badFrames++;log("无效帧已丢弃");return;
        }
        validFrames++;long now=SystemClock.elapsedRealtime();
        if(ready && confirmingOta){confirmingOta=false;otaStatus="OTA完成，已重连并收到有效应用数据";log(otaStatus);}
        if(f.realtime){
            realtime=f;lastRealtimeAt=now;
            if(lastSample>=0){
                long diff=(f.sample-lastSample)&0xffffffffL;
                if(diff>1 && diff<10000)dataGaps+=diff-1;
                else if(diff>=10000)log("序号回退/跳变，可能复位或数据丢失");
            }
            lastSample=f.sample;
            double uv=f.validUv() ? f.uviX10/10.0 : Double.NaN;
            points.add(new Point(System.currentTimeMillis(),uv));while(points.size()>300)points.remove(0);
            if(f.validUv()){
                totalSamples++;sumUv+=uv;minUv=Double.isNaN(minUv)?uv:Math.min(minUv,uv);maxUv=Double.isNaN(maxUv)?uv:Math.max(maxUv,uv);
            }
            // bit1是板端统一报警状态；强制测试在传感器故障时也应可验证提醒。
            if((f.flags&2)!=0 && (lastAlarm==0 || now-lastAlarm>=60000))alarm(now);
        }else{diagnostic=f;lastDiagAt=now;}
        // 新记录携带时区偏移，跨时区查看日报时仍能还原实际时刻；兼容旧CSV。
        String time=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US).format(new Date());
        if(f.realtime)writeCsv(time+",realtime,"+f.sample+","+f.uviX10+","+f.raw+","+f.battery+","+f.flags+",,,,,,,,");
        else writeCsv(time+",diagnostic,"+f.sample+",,"+f.raw+",,,"+f.ctrl+","+f.rate+","+f.gain+","+f.id+","+f.status+","+f.adc+","+f.mv+","+f.diagFlags);
        changed();
    }
    private void alarm(long now) {
        if(!getSharedPreferences("settings",0).getBoolean("phoneAlarm",true))return;
        lastAlarm=now;
        Notification.Builder b=new Notification.Builder(this,ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("UV-Guard 防晒提醒")
            .setContentText("手环报警或测试：请核对状态，避免直射阳光并做好遮挡；没有报警也不代表安全。").setAutoCancel(true);
        getSystemService(NotificationManager.class).notify(8,b.build());
        try{
            Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);
            if(v!=null)v.vibrate(VibrationEffect.createOneShot(350,VibrationEffect.DEFAULT_AMPLITUDE));
            if(tone==null)tone=new ToneGenerator(AudioManager.STREAM_NOTIFICATION,60);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2,450);
        }catch(Exception e){log("手机提醒未播放："+e.getMessage());}
    }
    private final Runnable watchdog=new Runnable(){public void run(){
        if(destroyed)return;
        if(ready){
            long base=lastRealtimeAt==0?connectedAt:lastRealtimeAt;
            long age=SystemClock.elapsedRealtime()-base;
            if(age>30000){fail("FF01超过30秒无有效数据，重建订阅");}
            else if(active==null && ops.isEmpty())try{gatt.readRemoteRssi();}catch(SecurityException e){wantConnection=false;fail("蓝牙权限被撤销");}catch(Exception ignored){}
        }
        h.postDelayed(this,5000);
    }};
    private void writeCsv(String line) {
        io.post(()->{try(FileOutputStream stream=new FileOutputStream(history,true)){
            stream.write((line+"\n").getBytes(StandardCharsets.UTF_8));
        }catch(IOException e){h.post(()->log("历史写入失败："+e.getMessage()));}});
    }
    /** 与CSV写入共用队列，先完成已排队的写入，避免读取半行。 */
    public void readDaily(Runnable reader){io.post(reader);}
    public void exportHistory(android.net.Uri uri){
        io.post(()->{
            String result;
            try(InputStream in=new FileInputStream(history);OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IOException("无法打开导出位置");
                byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
                result="CSV导出完成";
            }catch(Exception e){result="导出失败："+e.getMessage();}
            final String text=result;h.post(()->{exportResult=text;log(text);});
        });
    }
    /** 先暂停监测连接，再将独占BLE交给官方DFU服务，避免两个Gatt互相抢占。 */
    public void startOta(android.net.Uri uri){
        if(otaRunning){log("已有升级任务");return;}
        if(!usable()){log("请先开启蓝牙");return;}
        if(address.isEmpty())address=getSharedPreferences("settings",0).getString("lastAddress","");
        if(address.isEmpty()){log("请先连接手环并完成配对");return;}
        final String target=address;
        try{
            if(adapter.getRemoteDevice(target).getBondState()!=BluetoothDevice.BOND_BONDED){log("OTA只允许已绑定的主人，请先完成配对");return;}
        }catch(SecurityException e){log("请先允许蓝牙连接权限");return;}
        catch(Exception e){log("无法检查配对："+e.getMessage());return;}
        if(ready && !secureFirmware){log("当前是旧固件，必须先用J-Link安装安全版Bootloader");return;}
        otaRunning=true;wantConnection=false;cancelReconnect();stopScan();closeGatt();
        otaStatus="检查升级ZIP…";setState(otaStatus);
        io.post(()->{
            File zip=new File(getFilesDir(),"pending_ota.zip");
            try{
                try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(zip)){
                    if(in==null)throw new IOException("文件不可读");
                    byte[] buf=new byte[8192];int n,total=0;
                    while((n=in.read(buf))!=-1){total+=n;if(total>2*1024*1024)throw new IOException("升级包超过2MB限制");out.write(buf,0,n);}
                }
                // 这里只检查文件形状；公钥验签和版本检查必须由手环执行，不能信任手机代验。
                try(ZipFile z=new ZipFile(zip)){
                    java.util.zip.ZipEntry manifest=z.getEntry("manifest.json");
                    if(manifest==null)throw new IOException("缺少Nordic manifest.json");
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    try(InputStream in=z.getInputStream(manifest)){
                        byte[] buf=new byte[1024];int n;
                        while((n=in.read(buf))!=-1){if(bytes.size()+n>65536)throw new IOException("清单过大");bytes.write(buf,0,n);}
                    }
                    JSONObject m=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)).getJSONObject("manifest");
                    if(m.length()!=1 || !m.has("application"))throw new IOException("本App仅允许application升级包，不允许修改协议栈/Bootloader");
                    JSONObject app=m.getJSONObject("application");
                    if(z.getEntry(app.getString("bin_file"))==null || z.getEntry(app.getString("dat_file"))==null)throw new IOException("缺少bin/dat");
                    long binSize=z.getEntry(app.getString("bin_file")).getSize();
                    long datSize=z.getEntry(app.getString("dat_file")).getSize();
                    if(binSize<=0 || binSize>0x26000 || datSize<=0 || datSize>512)throw new IOException("固件大小不符合本项目的双分区布局");
                }
                h.post(()->{
                    if(destroyed)return;
                    try{
                        otaStatus="启动升级…";setState(otaStatus);
                        dfuController=new DfuServiceInitiator(target).setDeviceName("UV-Guard")
                            .setKeepBond(true).setForeground(true).setNumberOfRetries(2)
                            .setPacketsReceiptNotificationsEnabled(true).setPacketsReceiptNotificationsValue(12)
                            .setZip(zip.getAbsolutePath()).start(BleService.this,UvDfuService.class);
                    }catch(Exception e){finishDfu(false,"无法启动升级："+e.getMessage());}
                });
            }catch(Exception e){finishDfu(false,"升级文件检查失败："+e.getMessage());}
        });
    }
    public void cancelOta(){
        if(dfuController!=null){dfuController.abort();log("已请求取消升级，等待服务结束");}
        else if(otaRunning)log("正在准备升级，请稍后再取消");
    }
    public void shutdown(){if(otaRunning){log("升级中请勿结束服务");return;}disconnect();stopForeground(true);stopSelf();}
    @Override public void onDestroy(){
        destroyed=true;wantConnection=false;stopScan();closeGatt();h.removeCallbacksAndMessages(null);
        unregisterReceiver(bondReceiver);DfuServiceListenerHelper.unregisterProgressListener(this,dfuProgress);
        ioThread.quitSafely();if(tone!=null)tone.release();super.onDestroy();
    }
}
