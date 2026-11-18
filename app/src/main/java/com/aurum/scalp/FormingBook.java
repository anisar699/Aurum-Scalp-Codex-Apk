package com.aurum.scalp;

import java.util.*;

/** Display-only observations. Never supplies candles to ZoneEngine or SmartAlerts. */
final class FormingBook {
    static final class Bar {
        final Market.Candle candle;final boolean partial;
        Bar(Market.Candle c,boolean partial){candle=c;this.partial=partial;}
    }
    private static final class State {
        long time,lastTick;double open,high,low,close;boolean seeded;
        State(long time,double p){this.time=time;open=high=low=close=p;}
        Market.Candle candle(Market.Frame f){return new Market.Candle(time,open,high,low,close,time+f.duration());}
    }
    private final Map<Market.Frame,TreeMap<Long,State>> frames=new EnumMap<>(Market.Frame.class);
    synchronized void tick(Market.Quote q,boolean online,long now){
        if(!online||q==null||!q.valid()||q.marketClosed||q.providerStale||now<=0||q.time>now+MarketClock.FUTURE_TOLERANCE||now-q.time>30_000)return;
        for(Market.Frame f:Market.Frame.values()){
            TreeMap<Long,State> map=frames.computeIfAbsent(f,k->new TreeMap<>());long bucket=q.time/f.duration()*f.duration();
            if(!map.isEmpty()&&bucket<map.lastKey())continue;
            State s=map.computeIfAbsent(bucket,k->new State(k,q.mid()));if(q.time<s.lastTick)continue;
            s.lastTick=q.time;s.high=Math.max(s.high,q.mid());s.low=Math.min(s.low,q.mid());s.close=q.mid();
            while(map.size()>3)map.pollFirstEntry();
        }
    }
    synchronized void seed(Market.Frame f,Market.Candle c){seed(f,c,0);}
    synchronized void seed(Market.Frame f,Market.Candle c,long asOf){
        if(c==null||!c.valid())return;TreeMap<Long,State> map=frames.computeIfAbsent(f,k->new TreeMap<>());
        if(!map.isEmpty()&&c.time<map.lastKey())return;
        State s=map.get(c.time);
        if(s==null){s=new State(c.time,c.close);s.high=c.high;s.low=c.low;s.close=c.close;map.put(c.time,s);}
        else{s.high=Math.max(s.high,c.high);s.low=Math.min(s.low,c.low);if(s.lastTick==0||asOf>=s.lastTick)s.close=c.close;}
        s.open=c.open;s.lastTick=Math.max(s.lastTick,asOf);s.seeded=true;while(map.size()>3)map.pollFirstEntry();
    }
    synchronized void confirm(Market.Frame f,List<Market.Candle> closed){
        TreeMap<Long,State> map=frames.get(f);if(map==null||closed.isEmpty())return;
        long time=closed.get(closed.size()-1).time;map.headMap(time,true).clear();
    }
    synchronized List<Bar> visible(Market.Frame f,long lastClosed,long now,Market.Quote q,boolean online){
        List<Bar> out=new ArrayList<>();if(now<=0||q==null||q.marketClosed)return out;TreeMap<Long,State> map=frames.get(f);if(map==null)return out;
        for(State s:map.values())if(s.time>lastClosed&&s.time<=now&&now-s.time<f.duration()*2)out.add(new Bar(s.candle(f),!s.seeded));return out;
    }
}
