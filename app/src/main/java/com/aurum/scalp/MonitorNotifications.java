package com.aurum.scalp;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class MonitorNotifications {
    static final String MONITOR="aurum-monitor-v1",ZONES="aurum-zones-v1";
    static final int STATUS_ID=2300,ALERT_ID=2301,SUMMARY_ID=2302,STOPPED_ID=2303;
    static final String STOPPED="aurum-monitor-stopped-v1";
    private static final String ALERT_GROUP="aurum-zone-alerts";
    private static long alertSequence;
    private MonitorNotifications() {}
    static boolean permission(Context c){return Build.VERSION.SDK_INT<33||c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;}
    static boolean alertsAllowed(Context c){
        NotificationManager nm=c.getSystemService(NotificationManager.class);NotificationChannel ch=nm.getNotificationChannel(ZONES);
        return permission(c)&&nm.areNotificationsEnabled()&&ch!=null&&ch.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    static PendingIntent open(Context c,boolean health,Market.Frame frame){
        Intent i=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("health",health);if(frame!=null)i.putExtra("alert-frame",frame.name());
        return PendingIntent.getActivity(c,health?23:30+(frame==null?0:frame.ordinal()),i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static Notification status(Context c,MonitorLevels.Status status){
        Intent stop=new Intent(c,MonitoringService.class).setAction(MonitoringService.STOP);
        PendingIntent action=PendingIntent.getService(c,25,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent chart = new Intent(c, ChartActivity.class).putExtra("default-frame", Market.Frame.H1.name());
        PendingIntent openChart = PendingIntent.getActivity(c, 26, chart, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.InboxStyle expanded=new Notification.InboxStyle();
        for(String row:status.rows())expanded.addLine(row);
        if(!status.legend().isEmpty())expanded.addLine(status.legend());
        return new Notification.Builder(c,MONITOR).setSmallIcon(com.aurum.scalp.R.drawable.ic_stat_aurum)
            .setContentTitle(status.title()).setContentText(status.preview()).setStyle(expanded)
            .setContentIntent(open(c,true,null)).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null,"Stop monitoring",action).build())
            .addAction(new Notification.Action.Builder(null,"Open chart",openChart).build()).build();
    }
    static boolean alert(Context c,String title,String text,Market.Frame frame){
        if(!permission(c))return false;
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        String tag="zone-"+System.currentTimeMillis()+"-"+(++alertSequence);
        Notification n=new Notification.Builder(c,ZONES).setSmallIcon(R.drawable.ic_stat_aurum)
            .setContentTitle(title).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open(c,false,frame))
            .setAutoCancel(true).setCategory(Notification.CATEGORY_EVENT)
            .setGroup(ALERT_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN).build();
        try{
            manager.notify(tag,ALERT_ID,n);
            List<StatusBarNotification> active=new ArrayList<>();
            for(StatusBarNotification item:manager.getActiveNotifications()){
                if(item.getId()==ALERT_ID&&item.getTag()!=null)active.add(item);
            }
            active.sort(Comparator.comparingLong(StatusBarNotification::getPostTime));
            for(int i=0;i<active.size()-10;i++)manager.cancel(active.get(i).getTag(),ALERT_ID);
            Notification summary=new Notification.Builder(c,ZONES).setSmallIcon(R.drawable.ic_stat_aurum)
                .setContentTitle("Aurum zone alerts").setContentText("Recent XAU/USD events")
                .setGroup(ALERT_GROUP).setGroupSummary(true).setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setContentIntent(open(c,false,frame)).setAutoCancel(true).build();
            manager.notify(SUMMARY_ID,summary);
            return true;
        }catch(SecurityException ignored){return false;}
    }

    static void stopped(Context c,String reason){
        if(!permission(c))return;
        Notification n=new Notification.Builder(c,STOPPED).setSmallIcon(R.drawable.ic_stat_aurum)
            .setContentTitle("Aurum monitoring stopped").setContentText(reason)
            .setStyle(new Notification.BigTextStyle().bigText(reason)).setAutoCancel(true)
            .setContentIntent(open(c,true,null)).build();
        try{c.getSystemService(NotificationManager.class).notify(STOPPED_ID,n);}catch(SecurityException ignored){}
    }
}
