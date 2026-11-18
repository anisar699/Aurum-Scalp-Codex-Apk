package com.aurum.scalp;

import java.util.*;

public final class Market {
    private Market() {}
    public static final long MINUTE=60_000L;
    public enum Frame {
        M1(1,"1m",2,360,.20,.55,.00025,10),
        M5(5,"5m",2,288,.40,.45,.0006,10),
        M15(15,"15m",2,192,.60,.40,.0009,10),
        H1(60,"1H",3,480,1.00,.32,.0012,18),
        H4(240,"4H",2,180,1.50,.25,.0018,24);
        public final int minutes,wing,lookback;
        public final String label;
        public final double minWidth,widthAtr,widthPrice,nearAtr;
        Frame(int m,String l,int w,int n,double min,double wa,double wp,double near){minutes=m;label=l;wing=w;lookback=n;minWidth=min;widthAtr=wa;widthPrice=wp;nearAtr=near;}
        public long duration(){return minutes*MINUTE;}
        public boolean scalp(){return minutes<60;}
    }
    public static final class Candle {
        public final long time,endTime;
        public final double open,high,low,close;
        public Candle(long t,double o,double h,double l,double c){this(t,o,h,l,c,0);}
        public Candle(long t,double o,double h,double l,double c,long end){time=t;open=o;high=h;low=l;close=c;endTime=end;}
        public boolean valid(){return time>0&&(endTime==0||endTime>time)&&finite(open)&&finite(high)&&finite(low)&&finite(close)&&low>0&&high>=Math.max(open,close)&&low<=Math.min(open,close);}
    }
    public static final class Quote {
        public final long time;
        public final double bid,ask;
        public final boolean marketClosed,providerStale;
        public Quote(long t,double b,double a){this(t,b,a,false,false);}
        public Quote(long t,double b,double a,boolean closed,boolean stale){time=t;bid=b;ask=a;marketClosed=closed;providerStale=stale;}
        public double mid(){return (bid+ask)/2;}
        public boolean valid(){return time>0&&finite(bid)&&finite(ask)&&bid>0&&ask>=bid&&ask-bid<bid*.02;}
        public double spread(){return ask-bid;}
    }
    public static boolean finite(double d){return !Double.isNaN(d)&&!Double.isInfinite(d);}
    public static long closeTime(Candle c,long duration){
        if(c.endTime>c.time)return c.endTime;
        // Native 4H reopen fragments end at the next regular UTC boundary.
        return duration==Frame.H4.duration()?(c.time/duration+1)*duration:c.time+duration;
    }
    public static List<Candle> closed(List<Candle> input,long duration,long now){
        TreeMap<Long,Candle> out=new TreeMap<>();
        long alignment=duration==Frame.H4.duration()?Frame.H1.duration():duration;
        for(Candle c:input)if(c.valid()&&c.time<=now&&c.time%alignment==0&&closeTime(c,duration)<=now)out.put(c.time,c);
        return Collections.unmodifiableList(new ArrayList<>(out.values()));
    }
    public static String quoteState(Quote quote,boolean connectedThisSession,long now){
        if(quote==null)return "CONNECTING";
        if(quote.marketClosed)return connectedThisSession?"● MARKET CLOSED":"● CLOSED / SAVED";
        if(!connectedThisSession)return "● SAVED";
        if(now<=0||quote.time>now+1000)return "● TIME UNVERIFIED";
        long age=Math.max(0,now-quote.time);
        if(quote.providerStale)return "● STALE";
        return age<=30_000?"● LIVE":age<=90_000?"● DELAYED":"● STALE";
    }
    public static String priceState(Quote q,boolean online,long now,Collection<ZoneEngine.Analysis> analyses){
        return referencePrice(q,analyses)>0&&(q==null||referenceTime(q,analyses)>q.time)?"● CANDLE REFERENCE":quoteState(q,online,now);
    }
    public static String historyState(ZoneEngine.Analysis a,long now,Quote q){
        if(now<=0)return "TIME UNVERIFIED";
        if(a==null||a.candles.isEmpty())return "Waiting for history";
        if(Market.closeTime(a.candles.get(a.candles.size()-1),a.frame.duration())>now)return "FUTURE CANDLE · awaiting verified history";
        if(q!=null&&q.marketClosed)return "MARKET CLOSED · last available session";
        return a.historyFresh(now)?"Recent closed candles":"OUTDATED CANDLES · refresh pending";
    }
    public static long referenceTime(Quote quote,Collection<ZoneEngine.Analysis> analyses){long time=quote==null?0:quote.time;for(ZoneEngine.Analysis a:analyses)if(!a.candles.isEmpty())time=Math.max(time,closeTime(a.candles.get(a.candles.size()-1),a.frame.duration()));return time;}
    public static double referencePrice(Quote quote,Collection<ZoneEngine.Analysis> analyses){
        long time=quote==null?0:quote.time;double price=quote==null?0:quote.mid();
        for(ZoneEngine.Analysis a:analyses)if(!a.candles.isEmpty()){Candle c=a.candles.get(a.candles.size()-1);long close=closeTime(c,a.frame.duration());if(close>time){time=close;price=c.close;}}
        return price;
    }
    public static final class PriorSession {
        public final long day,firstTime,lastClose;
        public final Frame frame;
        public final int bars;
        public final double high,low;
        PriorSession(long d,Frame f,List<Candle> candles){
            day=d;frame=f;bars=candles.size();double h=0,l=Double.MAX_VALUE;
            long first=Long.MAX_VALUE,last=0;
            for(Candle c:candles){h=Math.max(h,c.high);l=Math.min(l,c.low);first=Math.min(first,c.time);last=Math.max(last,closeTime(c,f.duration()));}
            high=h;low=l;firstTime=first;lastClose=last;
        }
    }
    public static PriorSession priorSession(Map<Frame,List<Candle>> data,long now){
        long today=now/86_400_000L,latest=-1;
        Frame[] sources={Frame.M5,Frame.M15,Frame.H1};
        for(Frame f:sources)for(Candle c:closed(data.getOrDefault(f,Collections.emptyList()),f.duration(),now)){
            long day=c.time/86_400_000L;if(c.valid()&&day<today&&closeTime(c,f.duration())<=now)latest=Math.max(latest,day);
        }
        if(latest<0)return null;
        PriorSession best=null;
        for(Frame f:sources){
            List<Candle> day=new ArrayList<>();for(Candle c:closed(data.getOrDefault(f,Collections.emptyList()),f.duration(),now))if(c.time/86_400_000L==latest)day.add(c);
            if(!day.isEmpty()&&(best==null||day.size()*f.minutes>best.bars*best.frame.minutes))best=new PriorSession(latest,f,day);
        }
        return best;
    }
}
