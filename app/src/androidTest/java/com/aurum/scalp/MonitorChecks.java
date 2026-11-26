package com.aurum.scalp;

import android.app.*;
import android.content.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Actual foreground-service/notification checks. Fake transport exists in the test APK only. */
final class MonitorChecks {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void await(BooleanSupplier p,String text)throws Exception{long end=SystemClock.elapsedRealtime()+15000;while(!p.getAsBoolean()&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);check(p.getAsBoolean(),text);}
    private static Notification notification(Context c,int id){
        for(StatusBarNotification n:c.getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==id)return n.getNotification();
        return null;
    }
    private static boolean hasNotification(Context c,int id){return notification(c,id)!=null;}
    private static List<String> lines(Context c){
        Notification n=notification(c,MonitorNotifications.STATUS_ID);
        if(n==null)return Collections.emptyList();
        CharSequence[] lines=n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if(lines==null)return Collections.emptyList();
        List<String> text=new ArrayList<>();
        for(CharSequence line:lines)text.add(line.toString());
        return text;
    }
    private static ZoneEngine.Analysis levels(Market.Frame frame,long now){
        long closed=now/frame.duration()*frame.duration();
        Market.Candle last=new Market.Candle(closed-frame.duration(),4000,4002,3998,4000,closed);
        List<ZoneEngine.Zone> zones=Arrays.asList(
            new ZoneEngine.Zone(frame,3997,3998,2,true,false,false,2,closed-1000,closed),
            new ZoneEngine.Zone(frame,4007,4008,2,true,false,false,2,closed-2000,closed),
            new ZoneEngine.Zone(frame,4011,4012,2,false,false,false,2,closed-3000,closed));
        List<ZoneEngine.Zone> watches=Collections.singletonList(
            new ZoneEngine.Zone(frame,4005,4006,2,false,true,false,0,closed-4000,closed,true));
        return new ZoneEngine.Analysis(frame,Collections.singletonList(last),frame==Market.Frame.H1?zones.subList(0,2):zones,watches,3,0,"range");
    }
    private static android.widget.TextView text(android.view.View node,String label){
        if(node instanceof android.widget.TextView value&&label.equals(value.getText().toString()))return value;
        if(node instanceof android.view.ViewGroup group)for(int index=0;index<group.getChildCount();index++){
            android.widget.TextView value=text(group.getChildAt(index),label);
            if(value!=null)return value;
        }
        return null;
    }
    private static void cancelAlerts(Context context){
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        for(StatusBarNotification alert:manager.getActiveNotifications()){
            if(alert.getId()==MonitorNotifications.ALERT_ID||alert.getId()==MonitorNotifications.SUMMARY_ID)
                manager.cancel(alert.getTag(),alert.getId());
        }
    }
    static int run(Instrumentation i)throws Exception{
        Context c=i.getTargetContext();AtomicReference<MonitorRuntime> ref=new AtomicReference<>();AtomicReference<MarketRepository> original=new AtomicReference<>();AtomicInteger quotes=new AtomicInteger(),bid=new AtomicInteger(4000);
        File dir=new File(c.getCacheDir(),"monitor-check-"+System.nanoTime());check(dir.mkdirs(),"Isolated test cache");
        Context isolated=new ContextWrapper(c){@Override public File getFilesDir(){return dir;}};
        MarketRepository injected=new MarketRepository(isolated,()->{MonitorRuntime r=ref.get();if(r!=null)r.refreshState();},address->{
            if(!address.endsWith("/api/XAUUSD"))throw new IOException("Controlled history unavailable");
            quotes.incrementAndGet();long now=System.currentTimeMillis()-500;int price=bid.get();
            return "{\"symbol\":\"XAUUSD\",\"timestamp\":\""+java.time.Instant.ofEpochMilli(now)+"\",\"bid\":"+price+",\"ask\":"+(price+.5)+"}";
        });
        java.lang.reflect.Field clock=MarketRepository.class.getDeclaredField("marketClock");clock.setAccessible(true);((MarketClock)clock.get(injected)).sync(System.currentTimeMillis(),SystemClock.elapsedRealtime());
        MonitorRuntime.Listener viewer=()->{};int checks=0;
        try{
            i.runOnMainSync(()->{MonitorRuntime r=MonitorRuntime.get(c);ref.set(r);original.set(r.repository);r.repository.stop();r.repository=injected;r.attach(viewer);});
            await(()->injected.quoteOnline,"Shared runtime quote is online");checks++;
            Map<Market.Frame,ZoneEngine.Analysis> fixture=new EnumMap<>(Market.Frame.class);
            long now=injected.marketNow();
            for(Market.Frame frame:Market.Frame.values())if(frame!=Market.Frame.H4)fixture.put(frame,levels(frame,now));
            injected.analyses=Collections.unmodifiableMap(fixture);
            Map<Market.Frame,Long> verified=new EnumMap<>(Market.Frame.class);
            for(Market.Frame frame:fixture.keySet())if(frame!=Market.Frame.M15)verified.put(frame,now);
            injected.liveSynced=Collections.unmodifiableMap(verified);
            i.runOnMainSync(()->c.startForegroundService(new Intent(c,MonitoringService.class)));
            await(()->ref.get().background()&&hasNotification(c,MonitorNotifications.STATUS_ID),"Foreground service and ongoing notification start");checks++;
            i.runOnMainSync(()->ref.get().detach(viewer));
            check(injected.running(),"Hiding activity must not stop service-owned repository");check(ref.get().repository==injected,"Service and activity use one repository");checks+=2;
            List<String> initial=lines(c);
            check(initial.size()==5&&initial.get(0).startsWith("5m ·")&&initial.get(1).startsWith("15m* ·")&&initial.get(2).startsWith("1H ·")&&initial.get(3).equals("4H · Waiting for candles"),
                "Expanded shade limits timeframe rows to 5m, 15m, 1H and 4H");checks++;
            check(initial.get(0).contains("S 3997.00–3998.00")&&initial.get(0).contains("R 4011.00–4012.00")&&initial.get(2).contains("R? 4005.00–4006.00")&&initial.get(4).contains("* Saved candles"),
                "Confirmed zones lead, unconfirmed watches and saved candles are marked");checks++;
            check(initial.get(2).contentEquals(notification(c,MonitorNotifications.STATUS_ID).extras.getCharSequence(Notification.EXTRA_TEXT)),"Collapsed shade previews 1H");checks++;
            Map<Market.Frame,ZoneEngine.Analysis> loaded=new EnumMap<>(fixture);
            loaded.put(Market.Frame.H4,levels(Market.Frame.H4,now));
            i.runOnMainSync(()->{injected.analyses=Collections.unmodifiableMap(loaded);Map<Market.Frame,Long> sync=new EnumMap<>(verified);sync.put(Market.Frame.H4,injected.marketNow());injected.liveSynced=Collections.unmodifiableMap(sync);ref.get().refreshState();});
            await(()->lines(c).stream().anyMatch(line->line.startsWith("4H · S 3997.00")),"History-only update redraws the H4 row");checks++;
            int prior=quotes.get();bid.set(4010);
            await(()->quotes.get()>prior,"Quote polls continue without a visible activity");checks++;
            await(()->{
                Notification n=notification(c,MonitorNotifications.STATUS_ID);
                List<String> current=lines(c);
                return n!=null&&String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)).contains("4,010.25 MID")
                    &&current.size()==5&&current.get(0).contains("S 4007.00–4008.00")&&current.get(0).contains("R 4011.00–4012.00");
            },"A background quote updates the MID and reselects the nearest zones together");checks++;
            StatusBarNotification status=Arrays.stream(c.getSystemService(NotificationManager.class).getActiveNotifications()).filter(n->n.getId()==MonitorNotifications.STATUS_ID).findFirst().orElseThrow(()->new AssertionError("Missing status notification"));
            check((status.getNotification().flags&Notification.FLAG_ONGOING_EVENT)!=0,"Status notification is ongoing");check(status.getNotification().actions!=null&&status.getNotification().actions.length==2&&"Open chart".contentEquals(status.getNotification().actions[1].title),"Stop and Open chart controls exist");checks+=2;
            // Exercise the notification action itself, not just stopService().
            status.getNotification().actions[0].actionIntent.send();await(()->!ref.get().background()&&!injected.running()&&!hasNotification(c,MonitorNotifications.STATUS_ID),"Stop notification action stops polling and clears service");checks++;
            check(MonitorNotifications.alertsAllowed(c),"Zone channel is enabled on test device");
            check(MonitorNotifications.alert(c,"TEST · confirmed break","Controlled instrumentation fixture, not live market data",Market.Frame.H1),"Notification accepted");await(()->hasNotification(c,MonitorNotifications.ALERT_ID),"Smart alert notification delivered");checks++;
            check(MonitorNotifications.alert(c,"TEST · retest held","Controlled subsequent event",Market.Frame.H4),"Second notification accepted");
            await(()->Arrays.stream(c.getSystemService(NotificationManager.class).getActiveNotifications())
                .filter(alert->alert.getId()==MonitorNotifications.ALERT_ID).count()==2
                &&hasNotification(c,MonitorNotifications.SUMMARY_ID),"Both recent events and group summary remain visible");checks++;
            cancelAlerts(c);
            // A notification starts the correct timeframe in the activity.
            bid.set(4000);
            i.runOnMainSync(()->injected.quote=new Market.Quote(injected.marketNow()-500,4000,4000.5));
            Activity activity=i.startActivitySync(new Intent(c,MainActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("alert-frame","H4"));
            AtomicReference<Throwable> failure=new AtomicReference<>();
            i.runOnMainSync(()->{try{
                java.lang.reflect.Field frame=MainActivity.class.getDeclaredField("frame");frame.setAccessible(true);check(frame.get(activity)==Market.Frame.H4,"Alert opens its own timeframe");
                android.view.View root=activity.getWindow().getDecorView();
                android.widget.TextView scalp=text(root,"Scalp View");check(scalp!=null&&scalp.performClick(),"Scalp View opens from Market");
                check(text(root,"SCALP SETUP")!=null&&text(root,"View setup on 5m chart")!=null,"Scalp View exposes Setup Card and 5m shortcut");
                for(String tf:new String[]{"5m","15m","1H","4H"})check(text(root,"Open "+tf+" chart")!=null,"Scalp View exposes "+tf);
                check(text(root,"Open 1m chart")==null&&text(root,"Distance filter · All distances")!=null,"Four scalp rows and distance filter");
                android.widget.TextView watch=text(root,"Retest watch · potential resistance 4005.00–4006.00 · $4.75 from edge");
                check(watch!=null&&watch.getVisibility()==android.view.View.VISIBLE,"Scalp View shows a nearby retest even when confirmed resistance is available");
                ZoneEngine.Zone pin=loaded.get(Market.Frame.H4).zones.get(0);
                String pinFixtureName="pins-test-"+System.nanoTime();
                android.content.SharedPreferences isolatedPins=c.getSharedPreferences(pinFixtureName,Context.MODE_PRIVATE);
                new ZonePins(isolatedPins).put(pin,ZoneSelection.Trigger.BREAK);
                ZonePins reloaded=new ZonePins(isolatedPins);
                check(reloaded.current().get(Market.Frame.H4).matches(pin)&&reloaded.current().get(Market.Frame.H4).trigger()==ZoneSelection.Trigger.BREAK,"Pinned zone and trigger persist");
                reloaded.remove(Market.Frame.H4);check(new ZonePins(isolatedPins).current().isEmpty(),"Pinned alert can be removed");
                c.deleteSharedPreferences(pinFixtureName);
                java.lang.reflect.Field page=MainActivity.class.getDeclaredField("page");page.setAccessible(true);page.setInt(activity,4);java.lang.reflect.Method render=MainActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(activity);
                java.lang.reflect.Field scroll=MainActivity.class.getDeclaredField("scroll");scroll.setAccessible(true);android.widget.ScrollView view=(android.widget.ScrollView)scroll.get(activity);android.view.View child=view.getChildAt(0);view.scrollTo(0,400);Object first=((android.view.ViewGroup)child).getChildAt(0);ref.get().refreshState();check(first==((android.view.ViewGroup)child).getChildAt(0),"Health update preserves existing views and accessibility focus targets");
                activity.finish();
            }catch(Throwable e){failure.set(e);}});
            if(failure.get()!=null)throw new AssertionError(failure.get());checks+=6;
        }finally{
            i.runOnMainSync(()->{c.stopService(new Intent(c,MonitoringService.class));MonitorRuntime r=ref.get();if(r!=null){r.detach(viewer);r.background(false);injected.stop();r.repository=original.get();r.refreshState();}});
            cancelAlerts(c);
        }
        return checks;
    }
}
