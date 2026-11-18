package com.aurum.scalp;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

final class MonitorNotifications {
    static final String MONITOR="aurum-monitor-v1",ZONES="aurum-zones-v1";
    static final int STATUS_ID=2300,ALERT_ID=2301;
    private MonitorNotifications() {}
    static boolean permission(Context c){return Build.VERSION.SDK_INT<33||c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;}
    static boolean alertsAllowed(Context c){
        NotificationManager nm=c.getSystemService(NotificationManager.class);NotificationChannel ch=nm.getNotificationChannel(ZONES);
        return permission(c)&&nm.areNotificationsEnabled()&&ch!=null&&ch.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    static PendingIntent open(Context c,boolean health,Market.Frame frame){
        Intent i=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("health",health);if(frame!=null)i.putExtra("alert-frame",frame.name());
        return PendingIntent.getActivity(c,health?23:24,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
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
        if(!alertsAllowed(c))return false;
        Notification n=new Notification.Builder(c,ZONES).setSmallIcon(com.aurum.scalp.R.drawable.ic_stat_aurum).setContentTitle(title).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open(c,false,frame)).setAutoCancel(true).setCategory(Notification.CATEGORY_EVENT).build();
        try{c.getSystemService(NotificationManager.class).notify(ALERT_ID,n);return true;}catch(SecurityException ignored){return false;}
    }
}
