package com.aurum.scalp;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

/** One process-wide repository shared by the visible activity and the foreground service. */
final class MonitorRuntime {
    interface Listener {void changed();}
    @android.annotation.SuppressLint("StaticFieldLeak") // Holds the application context only, never an activity/service.
    private static MonitorRuntime instance;
    static synchronized MonitorRuntime get(Context c){if(instance==null)instance=new MonitorRuntime(c.getApplicationContext());return instance;}
    final Context context;
    final SharedPreferences settings;
    private final ZonePins pins;
    MarketRepository repository;
    private final Set<Listener> viewers=new HashSet<>();
    private final Set<Listener> observers=new HashSet<>();
    private final SmartAlerts alerts=new SmartAlerts();
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean background,pulsing;
    private String lastAlert="No alerts this session",alertState="Waiting for fresh data";
    private final Runnable pulse=new Runnable(){public void run(){if(!pulsing)return;changed();main.postDelayed(this,5000);}};
    private MonitorRuntime(Context context){
        this.context=context;settings=context.getSharedPreferences("monitor-settings",Context.MODE_PRIVATE);pins=new ZonePins(settings);createChannels();
        repository=new MarketRepository(context,this::changed);
    }
    void attach(Listener viewer){viewers.add(viewer);reconcile();}
    void detach(Listener viewer){viewers.remove(viewer);reconcile();}
    void observe(Listener observer){observers.add(observer);}
    void unobserve(Listener observer){observers.remove(observer);}
    void background(boolean active){background=active;reconcile();changed();}
    boolean background(){return background;}
    boolean alertsEnabled(){return settings.getBoolean("alerts",true);}
    void alertsEnabled(boolean enabled){settings.edit().putBoolean("alerts",enabled).apply();alerts.reset();changed();}
    Set<Market.Frame> frames(){
        Set<Market.Frame> out=EnumSet.noneOf(Market.Frame.class);
        for(Market.Frame f:Market.Frame.values())if(settings.getBoolean("frame-"+f.name(),f==Market.Frame.H1||f==Market.Frame.H4))out.add(f);
        return out;
    }
    void frame(Market.Frame f,boolean enabled){settings.edit().putBoolean("frame-"+f.name(),enabled).apply();alerts.reset(f);changed();}
    Map<Market.Frame,ZoneSelection.Pin> pins(){return pins.current();}
    void pin(ZoneEngine.Zone zone,ZoneSelection.Trigger trigger){pins.put(zone,trigger);alerts.reset(zone.frame);changed();}
    void removePin(Market.Frame frame){pins.remove(frame);alerts.reset(frame);changed();}
    void reloadFeed(){repository.stop();repository=new MarketRepository(context,this::changed);alerts.reset();reconcile();changed();}
    String lastAlert(){return lastAlert;}
    String alertState(){return alertState;}
    void settingsChanged(){alerts.reset();changed();}
    void refreshState(){changed();}
    private void reconcile(){
        boolean active=background||!viewers.isEmpty();
        if(active){repository.start();if(!pulsing){pulsing=true;main.postDelayed(pulse,5000);}}
        else{repository.stop();alerts.reset();pulsing=false;main.removeCallbacks(pulse);}
    }
    private void changed(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::changed);return;}
        if(!alertsEnabled()){alerts.reset();alertState="Smart alerts switched off";}
        else if(!MonitorNotifications.alertsAllowed(context)){alerts.reset();alertState="Notifications disabled; enable them in Android settings";}
        else if(frames().isEmpty()&&pins().isEmpty()){alerts.reset();alertState="Select at least one alert timeframe";}
        else{
            List<SmartAlerts.Alert> events=alerts.evaluate(repository.quote,repository.quoteOnline,repository.marketNow(),repository.analyses,repository.liveSynced,frames(),pins());
            alertState=readiness();
            if(!events.isEmpty()){
                // One grouped notification per repository update, with a bounded visible summary.
                SmartAlerts.Alert first=events.get(0);StringBuilder detail=new StringBuilder();
                for(int i=0;i<Math.min(events.size(),3);i++){if(i>0)detail.append('\n');detail.append(events.get(i).title).append(": ").append(events.get(i).detail);}
                if(events.size()>3)detail.append("\n+").append(events.size()-3).append(" more zone events in this update");
                if(MonitorNotifications.alert(context,first.title+(events.size()>1?" + "+(events.size()-1):""),detail.toString(),first.frame))lastAlert=first.title+" · "+first.detail;
            }
        }
        for(Listener l:new ArrayList<>(viewers))l.changed();for(Listener l:new ArrayList<>(observers))l.changed();
    }
    String frameReadiness(Market.Frame frame) {
        if (!frames().contains(frame) && !pins().containsKey(frame)) return "Off";
        if (!alertsEnabled() || !MonitorNotifications.alertsAllowed(context)) return "Paused · notifications off";
        if (!repository.running()) return "Paused · monitoring stopped";
        long now = repository.marketNow();
        Market.Quote quote = repository.quote;
        if (quote != null && quote.marketClosed) return "Paused · market closed";
        if (now <= 0) return "Paused · server time unverified";
        if (!SmartAlerts.usableQuote(quote, repository.quoteOnline, now)) return "Waiting for fresh quote";
        ZoneEngine.Analysis a = repository.analyses.get(frame);
        long sync = repository.liveSynced.getOrDefault(frame, 0L);
        if (a == null || a.atr <= 0 || !a.historyFresh(now) || !DataFreshness.verified(now, sync, frame))
            return "Waiting for current candles";
        if (quote.time < DataFreshness.lastClose(a)) return "Waiting for matching quote";
        return alerts.armed(frame) ? "Armed" : "Warming up";
    }
    private String readiness(){
        Set<Market.Frame> selected = frames();
        selected.addAll(pins().keySet());
        if (selected.isEmpty()) return "Select an alert timeframe or pin a zone";
        int ready = 0;
        String reason = "Waiting for feed";
        for (Market.Frame frame : selected) {
            String state = frameReadiness(frame);
            if ("Armed".equals(state)) ready++;
            else reason = state;
        }
        return ready > 0 ? "Alerts armed · " + ready + " of " + selected.size() + " frames" : reason;
    }
    private void createChannels(){
        NotificationManager nm=context.getSystemService(NotificationManager.class);
        NotificationChannel monitor=new NotificationChannel(MonitorNotifications.MONITOR,"Background price monitoring",NotificationManager.IMPORTANCE_LOW);monitor.setDescription("Ongoing status and Stop control; no sound");
        NotificationChannel zones=new NotificationChannel(MonitorNotifications.ZONES,"Smart XAU/USD zone alerts",NotificationManager.IMPORTANCE_DEFAULT);zones.setDescription("Zone approaches and closed-candle breaks/retests");
        nm.createNotificationChannel(monitor);nm.createNotificationChannel(zones);
    }
}
