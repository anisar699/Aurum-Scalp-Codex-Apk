package com.aurum.scalp;

import android.app.*;
import android.content.*;
import android.os.*;

/** Explicit, user-started data-sync foreground service; Android's time/power limits still apply. */
public final class MonitoringService extends Service {
    static final String STOP="com.aurum.scalp.STOP_MONITOR";
    private static final long ACTIVE_UPDATE_MS=5_000, SLEEP_UPDATE_MS=15_000;
    private final Handler main=new Handler(Looper.getMainLooper());
    private MonitorRuntime runtime;
    private final MonitorRuntime.Listener listener=this::scheduleUpdate;
    private final Runnable publish=this::update;
    private MonitorLevels.Status previous;
    private long nextUpdate;
    private boolean active, updatePending;
    @Override public void onCreate(){super.onCreate();runtime=MonitorRuntime.get(this);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){stop("Stopped by you",false);return START_NOT_STICKY;}
        // Foreground immediately, before any network operation or callback.
        previous=snapshot();
        startForeground(MonitorNotifications.STATUS_ID,MonitorNotifications.status(this,previous));
        nextUpdate=SystemClock.uptimeMillis()+updateInterval();
        active=true;
        runtime.observe(listener);runtime.background(true);MonitoringWatch.started(runtime);
        return START_NOT_STICKY;
    }
    private long updateInterval(){
        return getSystemService(PowerManager.class).isInteractive()?ACTIVE_UPDATE_MS:SLEEP_UPDATE_MS;
    }
    private MonitorLevels.Status snapshot(){
        MarketRepository r=runtime.repository;
        return MonitorLevels.from(r.quote,r.quoteOnline,r.marketNow(),r.analyses,r.liveSynced);
    }
    private void scheduleUpdate(){
        if(!active||updatePending)return;
        updatePending=true;
        main.postAtTime(publish,Math.max(SystemClock.uptimeMillis(),nextUpdate));
    }
    private void update(){
        updatePending=false;
        if(!active)return;
        nextUpdate=SystemClock.uptimeMillis()+updateInterval();
        MonitorLevels.Status status=snapshot();
        if(status.equals(previous))return;
        try{
            getSystemService(NotificationManager.class).notify(MonitorNotifications.STATUS_ID,MonitorNotifications.status(this,status));
            previous=status;
        }catch(SecurityException ignored){/* Permission may be revoked while monitoring. */}
    }
    private void stopUpdates(){
        active=false;
        updatePending=false;
        main.removeCallbacks(publish);
        runtime.unobserve(listener);
    }
    private void stop(String reason,boolean notify){
        stopUpdates();
        runtime.background(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        MonitoringWatch.stopped(runtime,reason,notify);
        stopSelf();
    }
    @Override public void onTimeout(int startId,int fgsType){
        stop("Android background data-sync time limit reached. Open Health to restart.",true);
    }
    @Override public void onDestroy(){
        if(runtime!=null&&active){
            stopUpdates();
            runtime.background(false);
            MonitoringWatch.stopped(runtime,"Android stopped background monitoring. Open Health to restart.",true);
        }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
