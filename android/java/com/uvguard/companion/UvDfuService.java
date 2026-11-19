package com.uvguard.companion;

import android.app.Activity;
import no.nordicsemi.android.dfu.DfuBaseService;

/** 使用 Nordic 官方 Secure DFU 传输、断点校验和绑定共享实现。 */
public final class UvDfuService extends DfuBaseService {
    @Override protected Class<? extends Activity> getNotificationTarget() { return MainActivity.class; }
    @Override protected boolean isDebug() { return false; }
}
