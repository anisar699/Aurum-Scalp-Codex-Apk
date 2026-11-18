package com.aurum.scalp;

import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Best-effort stop detection. Android decides when the job actually runs. */
public final class MonitoringWatch extends JobService {
    private static final int JOB_ID=2310;
    private static final String EXPECTED="background-monitoring-expected";
    private static final String INTERRUPTED="Android interrupted background monitoring. Open Health to restart.";

    static String reason(MonitorRuntime runtime){
        String previous=runtime.settings.getString("stop-reason","");
        return previous.isEmpty()&&!runtime.background()&&runtime.settings.getBoolean(EXPECTED,false)?INTERRUPTED:previous;
    }

    static void started(MonitorRuntime runtime){
        Context context=runtime.context;
        runtime.settings.edit().putBoolean(EXPECTED,true).remove("stop-reason").apply();
        context.getSystemService(NotificationManager.class).cancel(MonitorNotifications.STOPPED_ID);
        JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(context,MonitoringWatch.class))
            .setPeriodic(15*Market.MINUTE).build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    static void stopped(MonitorRuntime runtime,String reason,boolean notify){
        runtime.settings.edit().putBoolean(EXPECTED,false).putString("stop-reason",reason).apply();
        runtime.context.getSystemService(JobScheduler.class).cancel(JOB_ID);
        if(notify)MonitorNotifications.stopped(runtime.context,reason);
    }

    @Override public boolean onStartJob(JobParameters parameters){
        MonitorRuntime runtime=MonitorRuntime.get(this);
        if(!runtime.background()&&runtime.settings.getBoolean(EXPECTED,false))stopped(runtime,INTERRUPTED,true);
        return false;
    }

    @Override public boolean onStopJob(JobParameters parameters){return false;}
}
