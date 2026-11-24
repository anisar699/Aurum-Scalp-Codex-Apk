package com.aurum.scalp;

import java.util.*;

/** Closed-candle watermarks keep recent confirmations eligible across brief feed gaps. */
public final class SmartAlerts {
    public static final class Alert {
        public final String key,title,detail;
        public final Market.Frame frame;
        Alert(String key,String title,String detail,Market.Frame frame){this.key=key;this.title=title;this.detail=detail;this.frame=frame;}
    }
    private static final class Approach {boolean armed;long last;}
    private static final long ZONE_COOLDOWN=600_000;
    private static final class State {
        long close,quote,lastApproach,seen;double price;
        final Map<String,Approach> zones=new HashMap<>();
    }
    private final Map<Market.Frame,State> states=new EnumMap<>(Market.Frame.class);
    interface Watermarks {
        long load(Market.Frame frame);
        void store(Market.Frame frame,long close);
    }
    private final Watermarks watermarks;
    SmartAlerts(){this(null);}
    SmartAlerts(Watermarks watermarks){this.watermarks=watermarks;}
    public void reset(){
        for(Market.Frame frame:Market.Frame.values())reset(frame);
    }
    void reset(Market.Frame frame){
        states.remove(frame);
        if(watermarks!=null)watermarks.store(frame,0);
    }
    void pause(){for(State state:states.values())state.seen=0;}
    boolean armed(Market.Frame frame){State state=states.get(frame);return state!=null&&state.seen>0;}
    static boolean usableQuote(Market.Quote quote, boolean online, long now){
        return online && quote!=null && quote.valid() && !quote.marketClosed && !quote.providerStale
            && now>0 && quote.time<=now+MarketClock.FUTURE_TOLERANCE && now-quote.time<=30_000;
    }
    public List<Alert> evaluate(Market.Quote quote,boolean online,long now,Map<Market.Frame,ZoneEngine.Analysis> analyses,Map<Market.Frame,Long> synced,Set<Market.Frame> selected){
        return evaluate(quote,online,now,analyses,synced,selected,Collections.emptyMap());
    }
    List<Alert> evaluate(Market.Quote quote,boolean online,long now,Map<Market.Frame,ZoneEngine.Analysis> analyses,
                         Map<Market.Frame,Long> synced,Set<Market.Frame> selected,Map<Market.Frame,ZoneSelection.Pin> pins){
        List<Alert> out=new ArrayList<>();
        boolean tick=usableQuote(quote,online,now);
        for(Market.Frame f:Market.Frame.values()){
            ZoneSelection.Pin pin=pins.get(f);boolean auto=selected.contains(f);
            ZoneEngine.Analysis a=analyses.get(f);long last=DataFreshness.lastClose(a);
            long sync=synced.getOrDefault(f,0L);
            boolean ready=tick&&(auto||pin!=null)&&a!=null&&a.atr>0&&a.historyFresh(now)&&DataFreshness.verified(now,sync,f);
            if(!ready)continue;
            State s=states.get(f);
            // Preserve the prior close while the stream catches up to the newer history packet.
            if(quote.time<last)continue;
            ZoneEngine.Zone pinnedZone=ZoneSelection.resolve(pin,a);
            if(s==null){
                s=new State();
                long prior=watermarks==null?0:watermarks.load(f);
                s.close=prior>0?prior:last;
                states.put(f,s);
                if(prior==0&&watermarks!=null)watermarks.store(f,last);
            }
            boolean prime=s.seen==0||now-s.seen>30_000;
            // Re-arm from the first reliable quote after a pause, keeping delivery cooldowns.
            if(prime)for(Approach approach:s.zones.values())approach.armed=false;
            s.seen=Math.max(s.seen,now);
            if(last>s.close){
                // Only events on newly closed candles. Each frame confirms its own break/retest.
                Set<String> unique=new HashSet<>();
                for(ZoneEngine.Event e:a.events)if(e.time>s.close&&e.time<=last&&now-e.time<=HistorySchedule.eventWindow(f)&&unique.add(e.key())){
                    ZoneSelection.Trigger trigger=e.kind==ZoneEngine.EventKind.BREAK?ZoneSelection.Trigger.BREAK:ZoneSelection.Trigger.RETEST;
                    boolean pinned=pin!=null&&pin.trigger()==trigger&&(pin.matches(e.zone)||pinnedZone!=null&&pinnedZone.id.equals(e.zone.id));
                    if(!auto&&!pinned)continue;
                    String kind=e.kind==ZoneEngine.EventKind.BREAK?"Break confirmed":e.kind==ZoneEngine.EventKind.RETEST_HELD?"Retest held":"Retest failed";
                    String evidence=e.kind==ZoneEngine.EventKind.RETEST_HELD?"New pivot confirmed after right-side closes": "Two buffered closes confirmed";
                    long delayed=now-e.time;
                    if(delayed>60_000)evidence+=" · confirmed "+(delayed/1000)+"s ago";
                    out.add(new Alert(e.key(),f.label+(pinned?" · Pinned · ":" · ")+kind,band(e.zone)+" · "+evidence,f));
                }
                s.close=last;
                if(watermarks!=null)watermarks.store(f,last);
            }
            // Ignore duplicate or out-of-order ticks, including repeated payloads from the provider.
            if(quote.time<s.quote||(!prime&&quote.time==s.quote&&quote.mid()==s.price))continue;s.quote=quote.time;s.price=quote.mid();
            Set<String> active=new HashSet<>();
            List<ZoneEngine.Zone> zones=new ArrayList<>(a.zones);zones.sort(Comparator.comparingDouble(z->Math.abs(z.center-quote.mid())));
            for(ZoneEngine.Zone z:zones){
                boolean pinned=pinnedZone!=null&&pin.trigger()==ZoneSelection.Trigger.APPROACH&&pinnedZone.id.equals(z.id);
                if(z.reference||z.breakPending||(!auto&&!pinned))continue;String id=z.id+":"+z.support;active.add(id);
                double distance=quote.mid()<z.low?z.low-quote.mid():quote.mid()>z.high?quote.mid()-z.high:0;
                Approach p=s.zones.get(id);if(p==null){p=new Approach();p.armed=z.validSide(quote.mid())&&distance>a.atr*.60;s.zones.put(id,p);continue;}
                if(z.validSide(quote.mid())&&distance>a.atr*.60)p.armed=true;
                double threshold=Math.max(.10,a.atr*.15);
                if(z.validSide(quote.mid())&&distance<=threshold&&p.armed){
                    p.armed=false;
                    if(now-p.last>=ZONE_COOLDOWN&&now-s.lastApproach>=120_000){
                        p.last=now;s.lastApproach=now;
                        out.add(new Alert("approach:"+id+":"+quote.time,f.label+(pinned?" · Pinned approach":" · Zone approach"),band(z)+" · MID "+usd(quote.mid())+" · "+(distance==0?"inside band":"near edge")+"; reaction not confirmed",f));
                    }
                }
            }
            for(var entries=s.zones.entrySet().iterator();entries.hasNext();){
                var entry=entries.next();
                if(active.contains(entry.getKey()))continue;
                entry.getValue().armed=false;
                if(now-entry.getValue().last>=ZONE_COOLDOWN)entries.remove();
            }
        }
        return out;
    }
    private static String band(ZoneEngine.Zone z){return (z.support?"Support ":"Resistance ")+ZoneSelection.band(z);}
    private static String usd(double p){return String.format(Locale.US,"%.2f",p);}
}
