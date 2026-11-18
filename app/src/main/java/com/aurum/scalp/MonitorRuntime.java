package com.aurum.scalp;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One repository shared by the app and its foreground service. */
final class MonitorRuntime {
    interface Listener { void changed(); }

    private static final Set<Market.Frame> SHADE_FRAMES=
        EnumSet.of(Market.Frame.M5,Market.Frame.M15,Market.Frame.H1,Market.Frame.H4);
    @android.annotation.SuppressLint("StaticFieldLeak") // Application context only.
    private static MonitorRuntime instance;

    static synchronized MonitorRuntime get(Context context){
        if(instance==null)instance=new MonitorRuntime(context.getApplicationContext());
        return instance;
    }

    final Context context;
    final SharedPreferences settings;
    MarketRepository repository;
    private final ZonePins pins;
    private final Set<Market.Frame> configuredFrames=EnumSet.noneOf(Market.Frame.class);
    private final Map<Listener,Set<Market.Frame>> viewers=new HashMap<>();
    private final Set<Listener> observers=new HashSet<>();
    private final SmartAlerts alerts;
    private final ExecutorService evaluator=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Set<Market.Frame> armed=EnumSet.noneOf(Market.Frame.class);
    private final Map<Market.Frame,ZoneEngine.Analysis> pinSnapshots=new EnumMap<>(Market.Frame.class);
    private final Map<Market.Frame,Integer> pinMisses=new EnumMap<>(Market.Frame.class);
    private final Map<Market.Frame,Long> pinLastClose=new EnumMap<>(Market.Frame.class);
    private boolean background,pulsing,permissionAllowed,enabled,evaluating,dirty;
    private long permissionCheck,revision;
    private final Map<Market.Frame,Integer> frameRevisions=new EnumMap<>(Market.Frame.class);
    private String lastAlert="No alerts this session",alertState="Waiting for fresh data";

    private final Runnable pulse=new Runnable(){
        public void run(){
            if(!pulsing)return;
            changed();
            main.postDelayed(this,5000);
        }
    };

    private MonitorRuntime(Context context){
        this.context=context;
        settings=context.getSharedPreferences("monitor-settings",Context.MODE_PRIVATE);
        pins=new ZonePins(settings);
        enabled=settings.getBoolean("alerts",true);
        for(Market.Frame f:Market.Frame.values()){
            if(settings.getBoolean("frame-"+f.name(),f==Market.Frame.H1||f==Market.Frame.H4))configuredFrames.add(f);
        }
        alerts=new SmartAlerts(new SmartAlerts.Watermarks(){
            public long load(Market.Frame frame){return settings.getLong("alert-close-"+frame.name(),0);}
            public void store(Market.Frame frame,long close){
                settings.edit().putLong("alert-close-"+frame.name(),close).apply();
            }
        });
        createChannels();
        refreshPermissions();
        repository=new MarketRepository(context,this::changed);
    }

    void attach(Listener viewer){attach(viewer,EnumSet.allOf(Market.Frame.class));}
    void attach(Listener viewer,Set<Market.Frame> frames){viewers.put(viewer,frames);reconcile();}
    void viewFrames(Listener viewer,Set<Market.Frame> frames){
        if(viewers.containsKey(viewer)&&!frames.equals(viewers.get(viewer))){
            viewers.put(viewer,frames);
            updateDemand();
        }
    }
    void detach(Listener viewer){viewers.remove(viewer);reconcile();}
    void observe(Listener observer){observers.add(observer);}
    void unobserve(Listener observer){observers.remove(observer);}
    void background(boolean active){background=active;reconcile();changed();}
    boolean background(){return background;}
    boolean alertsEnabled(){return enabled;}

    void alertsEnabled(boolean value){
        if(value==enabled)return;
        enabled=value;
        settings.edit().putBoolean("alerts",value).apply();
        resetAlerts(null);
    }

    Set<Market.Frame> frames(){
        Set<Market.Frame> copy=EnumSet.noneOf(Market.Frame.class);
        copy.addAll(configuredFrames);
        return copy;
    }

    void frame(Market.Frame frame,boolean value){
        if(value==configuredFrames.contains(frame))return;
        if(value)configuredFrames.add(frame);else configuredFrames.remove(frame);
        settings.edit().putBoolean("frame-"+frame.name(),value).apply();
        resetAlerts(frame);
    }

    Map<Market.Frame,ZoneSelection.Pin> pins(){return pins.current();}
    void pin(ZoneEngine.Zone zone,ZoneSelection.Trigger trigger){
        pins.put(zone,trigger);
        clearPinWatch(zone.frame);
        resetAlerts(zone.frame);
    }
    void removePin(Market.Frame frame){
        pins.remove(frame);
        clearPinWatch(frame);
        resetAlerts(frame);
    }

    private void clearPinWatch(Market.Frame frame){
        pinSnapshots.remove(frame);
        pinMisses.remove(frame);
        pinLastClose.remove(frame);
    }

    String lastAlert(){return lastAlert;}
    String alertState(){return alertState;}

    void refreshPermissions(){
        permissionAllowed=MonitorNotifications.alertsAllowed(context);
        permissionCheck=SystemClock.elapsedRealtime();
    }
    boolean notificationsAllowed(){return permissionAllowed;}
    void refreshState(){changed();}

    private void resetAlerts(Market.Frame frame){
        // Keep the evaluator ordered with configuration changes already sent to the worker.
        if(frame==null){
            revision++;
            armed=EnumSet.noneOf(Market.Frame.class);
            evaluator.execute(alerts::reset);
        }else{
            frameRevisions.merge(frame,1,Integer::sum);
            armed.remove(frame);
            evaluator.execute(()->alerts.reset(frame));
        }
        updateDemand();
        changed();
    }

    private void updateDemand(){
        Set<Market.Frame> demand=EnumSet.noneOf(Market.Frame.class);
        if(enabled){
            demand.addAll(configuredFrames);
            demand.addAll(pins.current().keySet());
        }
        for(Set<Market.Frame> view:viewers.values())demand.addAll(view);
        if(background)demand.addAll(SHADE_FRAMES);
        repository.demand(demand);
    }

    private void reconcile(){
        boolean active=background||!viewers.isEmpty();
        updateDemand();
        if(active){
            repository.start();
            if(!pulsing){pulsing=true;main.postDelayed(pulse,5000);}
        }else{
            repository.stop();
            revision++;
            armed=EnumSet.noneOf(Market.Frame.class);
            evaluator.execute(alerts::pause);
            pulsing=false;
            main.removeCallbacks(pulse);
        }
    }

    private void changed(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::changed);return;}
        if(SystemClock.elapsedRealtime()-permissionCheck>=30_000)refreshPermissions();
        evaluate();
        updateReadiness();
        for(Listener viewer:new ArrayList<>(viewers.keySet()))viewer.changed();
        for(Listener observer:new ArrayList<>(observers))observer.changed();
    }

    private void evaluate(){
        if(!enabled||!permissionAllowed||!repository.running()||configuredFrames.isEmpty()&&pins().isEmpty())return;
        if(evaluating){dirty=true;return;}
        evaluating=true;
        dirty=false;
        long requestRevision=revision;
        Map<Market.Frame,Integer> versions=new EnumMap<>(frameRevisions);
        MarketRepository source=repository;
        Market.Quote quote=source.quote;
        boolean online=source.quoteOnline;
        long now=source.marketNow();
        Map<Market.Frame,ZoneEngine.Analysis> analyses=source.analyses;
        Map<Market.Frame,Long> synced=source.liveSynced;
        Set<Market.Frame> selected=frames();
        Map<Market.Frame,ZoneSelection.Pin> pinned=new EnumMap<>(Market.Frame.class);
        pinned.putAll(pins());
        evaluator.execute(()->{
            List<SmartAlerts.Alert> events=alerts.evaluate(quote,online,now,analyses,synced,selected,pinned);
            Set<Market.Frame> ready=EnumSet.noneOf(Market.Frame.class);
            for(Market.Frame frame:Market.Frame.values())if(alerts.armed(frame))ready.add(frame);
            main.post(()->{
                evaluating=false;
                if(requestRevision==revision&&repository==source&&enabled&&permissionAllowed){
                    Set<Market.Frame> reconfigured=EnumSet.noneOf(Market.Frame.class);
                    for(Market.Frame frame:Market.Frame.values()){
                        if(!Objects.equals(frameRevisions.get(frame),versions.get(frame)))reconfigured.add(frame);
                    }
                    ready.removeAll(reconfigured);
                    for(Market.Frame frame:reconfigured)pinned.remove(frame);
                    boolean readinessChanged=!ready.equals(armed);
                    armed=ready;
                    for(SmartAlerts.Alert alert:events){
                        if(reconfigured.contains(alert.frame))continue;
                        if(MonitorNotifications.alert(context,alert.title,alert.detail,alert.frame))lastAlert=alert.title+" · "+alert.detail;
                    }
                    if(SmartAlerts.usableQuote(quote,online,now))rebindPins(pinned,analyses,synced,now);
                    if(readinessChanged||!events.isEmpty()){
                        updateReadiness();
                        for(Listener viewer:new ArrayList<>(viewers.keySet()))viewer.changed();
                    }
                }
                if(dirty)evaluate();
            });
        });
    }

    private void rebindPins(Map<Market.Frame,ZoneSelection.Pin> snapshot,Map<Market.Frame,ZoneEngine.Analysis> analyses,
                            Map<Market.Frame,Long> synced,long now){
        for(ZoneSelection.Pin pin:snapshot.values()){
            Market.Frame frame=pin.frame();
            ZoneEngine.Analysis analysis=analyses.get(frame);
            if(analysis==null||!analysis.historyFresh(now)||!DataFreshness.verified(now,synced.getOrDefault(frame,0L),frame)
                ||pinSnapshots.put(frame,analysis)==analysis)continue;
            ZoneEngine.Zone match=ZoneSelection.resolve(pin,analysis);
            if(match!=null){
                if(!pin.matches(match)||pin.support()!=match.support) pins.bind(match,pin.trigger());
                pinMisses.remove(frame);
                pinLastClose.remove(frame);
                continue;
            }
            long close=DataFreshness.lastClose(analysis);
            if(close<=pinLastClose.getOrDefault(frame,0L))continue;
            pinLastClose.put(frame,close);
            if(pinMisses.merge(frame,1,Integer::sum)==3){
                MonitorNotifications.alert(context,frame.label+" · Pinned zone unavailable",
                    PriceFormat.low(pin.low())+"–"+PriceFormat.high(pin.high())+" · No matching zone in three closed snapshots",frame);
            }
        }
    }

    String frameReadiness(Market.Frame frame){
        if(!configuredFrames.contains(frame)&&!pins().containsKey(frame))return "Off";
        if(!enabled||!permissionAllowed)return "Paused · notifications off";
        if(!repository.running())return "Paused · monitoring stopped";
        long now=repository.marketNow();
        Market.Quote quote=repository.quote;
        if(quote!=null&&quote.marketClosed)return "Paused · market closed";
        if(now<=0)return "Paused · server time unverified";
        if(!SmartAlerts.usableQuote(quote,repository.quoteOnline,now))return "Waiting for fresh quote";
        ZoneEngine.Analysis a=repository.analyses.get(frame);
        long sync=repository.liveSynced.getOrDefault(frame,0L);
        if(a==null||a.atr<=0||!a.historyFresh(now)||!DataFreshness.verified(now,sync,frame))return "Waiting for current candles";
        if(quote.time<DataFreshness.lastClose(a))return "Waiting for matching quote";
        return armed.contains(frame)?"Armed":"Warming up";
    }

    private void updateReadiness(){
        if(!enabled){alertState="Smart alerts switched off";return;}
        if(!permissionAllowed){alertState="Notifications disabled; enable them in Android settings";return;}
        Set<Market.Frame> selected=frames();
        selected.addAll(pins().keySet());
        if(selected.isEmpty()){alertState="Select an alert timeframe or pin a zone";return;}
        int ready=0;
        String reason="Waiting for feed";
        for(Market.Frame frame:selected){
            String state=frameReadiness(frame);
            if("Armed".equals(state))ready++;else reason=state;
        }
        alertState=ready>0?"Alerts armed · "+ready+" of "+selected.size()+" frames":reason;
    }

    private void createChannels(){
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        NotificationChannel monitor=new NotificationChannel(MonitorNotifications.MONITOR,"Background price monitoring",NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("Ongoing prices and Stop control");
        manager.createNotificationChannel(monitor);
        NotificationChannel zones=new NotificationChannel(MonitorNotifications.ZONES,"Smart XAU/USD zone alerts",NotificationManager.IMPORTANCE_DEFAULT);
        zones.setDescription("Zone approaches and closed-candle confirmations");
        manager.createNotificationChannel(zones);
        manager.createNotificationChannel(new NotificationChannel(MonitorNotifications.STOPPED,"Monitoring stopped",NotificationManager.IMPORTANCE_DEFAULT));
    }
}
