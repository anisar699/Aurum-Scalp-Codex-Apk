package com.aurum.scalp;

import java.util.*;

/** Session-local watermarks prevent replaying old history as new alerts. No trade or journal records. */
public final class SmartAlerts {
    public static final class Alert {
        public final String key,title,detail;
        public final Market.Frame frame;
        Alert(String key,String title,String detail,Market.Frame frame){this.key=key;this.title=title;this.detail=detail;this.frame=frame;}
    }
    private static final class Approach {boolean armed;long last;}
    private static final class State {
        long close,quote,lastApproach,seen;double price;
        final Map<String,Approach> zones=new HashMap<>();
    }
    private final Map<Market.Frame,State> states=new EnumMap<>(Market.Frame.class);
    private static final long EVENT_MAX_AGE = 90_000;
    public void reset(){states.clear();}
    void reset(Market.Frame frame){states.remove(frame);}
    boolean armed(Market.Frame frame){return states.containsKey(frame);}
    static boolean usableQuote(Market.Quote quote, boolean online, long now){
        return online && quote!=null && quote.valid() && !quote.marketClosed && !quote.providerStale
            && now>0 && quote.time<=now+1000 && now-quote.time<=30_000;
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
            if(!ready){states.remove(f);continue;}
            // HTTP Date has one-second precision; a new response can correct the anchor slightly.
            State s=states.get(f);if(s!=null&&(now-s.seen>EVENT_MAX_AGE||now+2000<s.seen)){states.remove(f);s=null;}
            // Preserve the prior close while the stream catches up to the newer history packet.
            if(quote.time<last)continue;
            boolean first=s==null;
            if(first){s=new State();s.close=last;states.put(f,s);}
            s.seen=Math.max(s.seen,now);
            if(!first&&last>s.close){
                // Only events on newly closed candles. Each frame confirms its own break/retest.
                Set<String> unique=new HashSet<>();
                for(ZoneEngine.Event e:a.events)if(e.time>s.close&&e.time<=last&&now-e.time<=EVENT_MAX_AGE&&unique.add(e.key())){
                    boolean pinned=pin!=null&&pin.matches(e.zone);
                    ZoneSelection.Trigger trigger=e.kind==ZoneEngine.EventKind.BREAK?ZoneSelection.Trigger.BREAK:ZoneSelection.Trigger.RETEST;
                    if(pinned?pin.trigger()!=trigger:!auto)continue;
                    String kind=e.kind==ZoneEngine.EventKind.BREAK?"Break confirmed":e.kind==ZoneEngine.EventKind.RETEST_HELD?"Retest held":"Retest failed";
                    String evidence=e.kind==ZoneEngine.EventKind.RETEST_HELD?"New pivot confirmed after right-side closes": "Two buffered closes confirmed";
                    out.add(new Alert(e.key(),f.label+(pinned?" · Pinned · ":" · ")+kind,band(e.zone)+" · "+evidence,f));
                }
                s.close=last;
            }
            // Ignore duplicate or out-of-order ticks, including repeated payloads from the provider.
            if(quote.time<s.quote||(quote.time==s.quote&&quote.mid()==s.price))continue;s.quote=quote.time;s.price=quote.mid();
            Set<String> active=new HashSet<>();
            List<ZoneEngine.Zone> zones=new ArrayList<>(a.zones);zones.sort(Comparator.comparingDouble(z->Math.abs(z.center-quote.mid())));
            for(ZoneEngine.Zone z:zones){
                boolean pinned=pin!=null&&pin.matches(z);
                if(z.reference||(pinned?pin.trigger()!=ZoneSelection.Trigger.APPROACH:!auto))continue;String id=z.id+":"+z.support;active.add(id);
                double distance=quote.mid()<z.low?z.low-quote.mid():quote.mid()>z.high?quote.mid()-z.high:0;
                Approach p=s.zones.get(id);if(p==null){p=new Approach();p.armed=z.validSide(quote.mid())&&distance>a.atr*.60;s.zones.put(id,p);continue;}
                if(z.validSide(quote.mid())&&distance>a.atr*.60)p.armed=true;
                double threshold=Math.max(.10,a.atr*.15);
                if(z.validSide(quote.mid())&&distance<=threshold&&p.armed){
                    p.armed=false;
                    if(now-p.last>=600_000&&now-s.lastApproach>=120_000){
                        p.last=now;s.lastApproach=now;
                        out.add(new Alert("approach:"+id+":"+quote.time,f.label+(pinned?" · Pinned approach":" · Zone approach"),band(z)+" · MID "+usd(quote.mid())+" · "+(distance==0?"inside band":"near edge")+"; reaction not confirmed",f));
                    }
                }
            }
            s.zones.keySet().retainAll(active);
        }
        return out;
    }
    private static String band(ZoneEngine.Zone z){return (z.support?"Support ":"Resistance ")+usd(z.low)+"–"+usd(z.high);}
    private static String usd(double p){return String.format(Locale.US,"%.2f",p);}
}
