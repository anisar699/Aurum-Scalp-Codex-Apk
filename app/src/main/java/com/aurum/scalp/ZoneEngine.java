package com.aurum.scalp;

import java.util.*;
import java.time.*;

/** Closed-bar, chronological support/resistance events. No projected price grid or win percentages. */
public final class ZoneEngine {
    private ZoneEngine() {}
    public static final class Zone {
        public final Market.Frame frame;
        public final double low,high,center,cap;
        public final String id;
        public final boolean support,reference,flipped,retestWatch;
        public final int tests;
        public final long lastTest,confirmedAt;
        public final double rejectionAtr;
        public final int barsSinceReaction, defendedVisits, failedVisits;
        public final boolean breakPending;
        Zone(Market.Frame f,double l,double h,double cap,boolean s,boolean ref,boolean flip,int tests,long last,long confirmed){
            this(f,l,h,cap,s,ref,flip,tests,last,confirmed,false);
        }
        Zone(Market.Frame f,double l,double h,double cap,boolean s,boolean ref,boolean flip,int tests,long last,long confirmed,boolean watch){
            this(f,l,h,cap,s,ref,flip,tests,last,confirmed,watch,f.name()+":"+last+":"+s);
        }
        Zone(Market.Frame f,double l,double h,double cap,boolean s,boolean ref,boolean flip,int tests,long last,long confirmed,boolean watch,String identity){
            this(f,l,h,cap,s,ref,flip,tests,last,confirmed,watch,identity,0,0,false,0,0);
        }
        Zone(Market.Frame f,double l,double h,double cap,boolean s,boolean ref,boolean flip,int tests,
             long last,long confirmed,boolean watch,String identity,double rejectionAtr,int barsSinceReaction,boolean breakPending,int defendedVisits,int failedVisits){
            id=identity;frame=f;low=l;high=h;center=(l+h)/2;this.cap=cap;support=s;reference=ref;flipped=flip;
            this.tests=tests;lastTest=last;confirmedAt=confirmed;retestWatch=watch;
            this.rejectionAtr=rejectionAtr;this.barsSinceReaction=barsSinceReaction;this.breakPending=breakPending;this.defendedVisits=defendedVisits;this.failedVisits=failedVisits;
        }
        Zone asPendingWatch(){
            return new Zone(frame,low,high,cap,support,true,flipped,tests,lastTest,confirmedAt,false,id,
                rejectionAtr,barsSinceReaction,true,defendedVisits,failedVisits);
        }
        public double width(){return high-low;}
        public boolean contains(double p){return p>=low&&p<=high;}
        public double distance(double p){return Math.max(0,Math.max(low-p,p-high));}
        public boolean validSide(double p){return support?p>=low:p<=high;}
        public String evidence(){return breakPending?"Break pending":reference?(retestWatch?"Retest needed":"Unconfirmed"):"Confirmed swing";}
    }
    public enum EventKind { BREAK, RETEST_HELD, RETEST_FAILED }
    public static final class Event {
        public final EventKind kind;
        public final Zone zone;
        public final long time;
        Event(EventKind kind,Zone zone,long time){this.kind=kind;this.zone=zone;this.time=time;}
        public String key(){return zone.frame.name()+":"+kind+":"+time+":"+zone.support+":"+Math.round(zone.center*100);}
    }
    public static final class Analysis {
        public final Market.Frame frame;
        public final List<Market.Candle> candles;
        public final List<Zone> zones,watchZones;
        public final List<Event> events;
        public final double atr,ema20,ema50;
        public final int brokenCount;
        public final String structure;
        Analysis(Market.Frame f,List<Market.Candle> c,List<Zone> z,List<Zone> watch,double a,int broken,String structure){
            this(f,c,z,watch,a,broken,structure,Collections.emptyList());
        }
        Analysis(Market.Frame f,List<Market.Candle> c,List<Zone> z,List<Zone> watch,double a,int broken,String structure,List<Event> events){
            this.events=Collections.unmodifiableList(new ArrayList<>(events));frame=f;candles=Collections.unmodifiableList(new ArrayList<>(c));zones=Collections.unmodifiableList(new ArrayList<>(z));watchZones=Collections.unmodifiableList(new ArrayList<>(watch));atr=a;brokenCount=broken;this.structure=structure;ema20=ema(c,20);ema50=ema(c,50);
        }
        public List<Zone> nearest(double price,boolean support,int count,boolean references){
            List<Zone> candidates=new ArrayList<>();
            if(!Market.finite(price)||price<=0||atr<=0)return candidates;
            for(Zone z:zones)if(!z.breakPending&&z.support==support&&z.validSide(price)&&z.distance(price)<=frame.nearAtr*atr)candidates.add(z);
            if(references)candidates.addAll(watch(price,support,Math.max(count,4)));
            candidates.sort(Comparator.comparingDouble((Zone z)->z.distance(price)).thenComparing(Comparator.comparingInt((Zone z)->z.tests).reversed()).thenComparingDouble(z->Math.abs(z.center-price)));
            List<Zone> out=new ArrayList<>();
            for(Zone z:candidates){
                boolean duplicate=false;
                for(Zone other:out)if(overlap(z,other)>.50)duplicate=true;
                if(!duplicate)out.add(z);if(out.size()==count)break;
            }
            if(out.isEmpty()&&references&&candles.size()>=40){
                int from=Math.max(0,candles.size()-(frame.scalp()?48:32));Market.Candle extreme=candles.get(from);
                for(int i=from+1;i<candles.size();i++){Market.Candle c=candles.get(i);if(support?c.low<=extreme.low:c.high>=extreme.high)extreme=c;}
                double p=support?extreme.low:extreme.high;
                if((support?p<=price:p>=price)&&Math.abs(p-price)<=frame.nearAtr*atr){
                    double half=Math.min(widthCap(frame,atr,p)/2,Math.max(frame.minWidth/2,atr*.09));
                    out.add(new Zone(frame,p-half,p+half,widthCap(frame,atr,p),support,true,false,0,extreme.time,0));
                }
            }
            return out;
        }
        public List<Zone> watch(double price,boolean support,int count){
            List<Zone> out=new ArrayList<>();
            for(Zone z:watchZones)if(z.support==support&&z.validSide(price)&&z.distance(price)<=frame.nearAtr*atr)out.add(z);
            for(Zone z:zones)if(z.breakPending&&z.support==support&&z.distance(price)<=frame.nearAtr*atr)out.add(z.asPendingWatch());
            out.sort(Comparator.comparingDouble(z->z.distance(price)));return new ArrayList<>(out.subList(0,Math.min(count,out.size())));
        }
        public boolean historyFresh(long now){return now>0&&!candles.isEmpty()&&Market.closeTime(candles.get(candles.size()-1),frame.duration())<=now&&now-Market.closeTime(candles.get(candles.size()-1),frame.duration())<=frame.duration()*2;}
    }
    private static final class Pivot {
        final int index;final double price,atr,rejection;final boolean support;
        Pivot(int i,double p,double a,boolean s,double rejection){
            index=i;price=p;atr=a;support=s;this.rejection=Math.min(2,rejection);
        }
    }
    private static final class Working {
        double min,max,low,high,rejectionTotal;
        final double cap,pad,initialHalf,seedAtr;
        final boolean origin;
        final String id;
        final ZoneVisits visits = new ZoneVisits();
        boolean support,active=true,departed=false,watchSupport,everFlipped;
        int tests=1,lastPivot,confirmed,breaks=0,breakAt=-1,departureAt=-1;
        Working(Pivot p,int j,Market.Frame frame,List<Market.Candle> bars,double[] atr){
            id=frame.name()+":"+bars.get(p.index).time+":"+p.support;min=max=p.price;support=origin=p.support;lastPivot=p.index;confirmed=j;seedAtr=p.atr;rejectionTotal=p.rejection;
            cap=widthCap(frame,p.atr,p.price);pad=Math.min(cap*.22,Math.max(frame.minWidth*.25,p.atr*.06));
            initialHalf=Math.min(cap/2,Math.max(frame.minWidth/2,p.atr*.09));bounds();syncDeparture(p.index+1,j,bars,atr);
        }
        void bounds(){double center=(min+max)/2,half=Math.max(initialHalf,(max-min)/2+pad);low=center-half;high=center+half;}
        boolean fits(Pivot p){double l=Math.min(min,p.price),h=Math.max(max,p.price);return h-l+2*pad<=cap+1e-8&&Math.abs(p.price-(min+max)/2)<=Math.min(cap*.6,p.atr*.24);}
        void noteDeparture(int k,Market.Candle c,double atr){
            if(!departed&&(support?c.close>high+atr*.35:c.close<low-atr*.35)){departed=true;departureAt=k;}
        }
        void syncDeparture(int from,int to,List<Market.Candle> bars,double[] atr){
            departed=false;departureAt=-1;for(int k=from;k<=to;k++)noteDeparture(k,bars.get(k),atr[k]);
        }
        void add(Pivot p,int j,List<Market.Candle> bars,double[] atr){
            min=Math.min(min,p.price);max=Math.max(max,p.price);lastPivot=p.index;confirmed=j;rejectionTotal+=p.rejection;breaks=0;bounds();syncDeparture(p.index+1,j,bars,atr);
        }
    }
    private static Zone snapshot(Working z,Market.Frame f,List<Market.Candle> bars,boolean watch,int asOf){
        int latest=watch?z.lastPivot:Math.max(z.lastPivot,z.visits.lastDefended());
        return new Zone(f,z.low,z.high,z.cap,watch?z.watchSupport:z.support,watch,z.everFlipped,watch?0:z.tests,
            bars.get(latest).time,watch?0:Market.closeTime(bars.get(z.confirmed),f.duration()),watch,z.id,
            watch?0:z.rejectionTotal/Math.max(1,z.tests),asOf-latest,!watch&&z.breaks==1,
            watch?0:z.visits.defended(),watch?0:z.visits.failed());
    }
    public static double widthCap(Market.Frame frame,double atr,double price){return Math.max(frame.minWidth,Math.min(atr*frame.widthAtr,price*frame.widthPrice));}
    public static double[] atrSeries(List<Market.Candle> bars,long duration){
        double[] out=new double[bars.size()];double avg=0;
        for(int i=0;i<bars.size();i++){
            Market.Candle c=bars.get(i);double tr=c.high-c.low;
            if(i>0&&Market.closeTime(bars.get(i-1),duration)==c.time)tr=Math.max(tr,Math.max(Math.abs(c.high-bars.get(i-1).close),Math.abs(c.low-bars.get(i-1).close)));
            avg=i<14?(avg*i+tr)/(i+1):(avg*13+tr)/14;out[i]=avg;
        }return out;
    }
    public static double ema(List<Market.Candle> bars,int period){if(bars.isEmpty())return 0;double a=bars.get(0).close,k=2.0/(period+1);for(int i=1;i<bars.size();i++)a=bars.get(i).close*k+a*(1-k);return a;}
    static boolean continuous(List<Market.Candle> bars,int from,int to,long duration){
        for(int i=from+1;i<=to;i++){
            long previous=bars.get(i-1).time,next=bars.get(i).time,gap=next-previous;
            if(gap==duration)continue;
            // XAU/USD macro bars are adjacent trading bars across known provider session closures.
            // Unexpected holes remain disallowed; missing prices are never interpolated.
            ZonedDateTime p=Instant.ofEpochMilli(previous).atZone(ZoneOffset.UTC),q=Instant.ofEpochMilli(next).atZone(ZoneOffset.UTC);
            boolean allowed=false;
            if(duration==Market.Frame.H1.duration()){
                allowed=gap==2*duration&&p.toLocalDate().equals(q.toLocalDate())&&((p.getHour()==20&&q.getHour()==22)||(p.getHour()==21&&q.getHour()==23));
                allowed|=p.getDayOfWeek()==DayOfWeek.FRIDAY&&q.getDayOfWeek()==DayOfWeek.SUNDAY&&(p.getHour()==20||p.getHour()==21)&&(q.getHour()==22||q.getHour()==23)&&gap>=49*duration&&gap<=51*duration;
            }else if(duration==Market.Frame.H4.duration()){
                long hour=Market.Frame.H1.duration();
                // Native session fragments may start at reopen (22/23 UTC), then
                // return to the regular UTC grid. Restrict exceptions to session edges.
                allowed=gap>0&&gap<duration&&p.getHour()>=20&&q.getHour()==0&&q.toLocalDate().equals(p.toLocalDate().plusDays(1));
                allowed|=gap>=hour&&gap<=7*hour&&p.toLocalDate().equals(q.toLocalDate())&&p.getHour()>=16&&p.getHour()<=21&&(q.getHour()==22||q.getHour()==23);
                allowed|=p.getDayOfWeek()==DayOfWeek.FRIDAY&&p.getHour()>=16&&
                    ((q.getDayOfWeek()==DayOfWeek.SUNDAY&&q.getHour()>=20)||(q.getDayOfWeek()==DayOfWeek.MONDAY&&q.getHour()==0))&&gap>=48*hour&&gap<=56*hour;
            }
            if(!allowed)return false;
        }return true;
    }
    public static Analysis calculate(List<Market.Candle> input,Market.Frame frame,long now){
        return calculate(input,frame,now,frame.wing,.25);
    }
    static Analysis calculate(List<Market.Candle> input,Market.Frame frame,long now,int wing,double minimumRejectionAtr){
        List<Market.Candle> bars=Market.closed(input,frame.duration(),now);double[] atr=atrSeries(bars,frame.duration());int n=bars.size();
        if(n<40)return new Analysis(frame,bars,Collections.emptyList(),Collections.emptyList(),n==0?0:atr[n-1],0,"Insufficient closed history");
        int minPivot=Math.max(wing,n-frame.lookback),start=minPivot+wing,broken=0;List<Working> work=new ArrayList<>();List<Pivot> events=new ArrayList<>();List<Event> lifecycle=new ArrayList<>();
        for(int j=start;j<n;j++){
            Market.Candle current=bars.get(j);
            Market.Candle previous=bars.get(j-1);
            boolean uninterrupted=Market.closeTime(previous,frame.duration())==current.time;
            for(Working z:work)if(z.active&&j>z.confirmed){
                z.visits.observe(j,current,previous.close,uninterrupted,z.low,z.high,z.support,atr[j]);
                double buffer=Math.max(.05,z.seedAtr*.05);
                boolean beyond=z.support?current.close<z.low-buffer:current.close>z.high+buffer;
                if(beyond){if(j>0&&!continuous(bars,j-1,j,frame.duration()))z.breaks=0;z.breaks++;}else z.breaks=0;
                if(z.breaks>=2){lifecycle.add(new Event(EventKind.BREAK,snapshot(z,frame,bars,false,j),Market.closeTime(current,frame.duration())));z.active=false;z.breakAt=j;z.watchSupport=!z.support;z.tests=0;z.breaks=0;broken++;}
                else z.noteDeparture(j,current,atr[j]);
            }
            for(Working z:work)if(!z.active&&z.breakAt>=0&&j>z.breakAt){
                double buffer=Math.max(.05,z.seedAtr*.05);
                boolean reclaimed=z.watchSupport?current.close<z.low-buffer:current.close>z.high+buffer;
                if(reclaimed){if(!continuous(bars,j-1,j,frame.duration()))z.breaks=0;if(++z.breaks>=2){lifecycle.add(new Event(EventKind.RETEST_FAILED,snapshot(z,frame,bars,true,j),Market.closeTime(current,frame.duration())));z.breakAt=-1;z.breaks=0;}}
                else z.breaks=0;
            }
            int i=j-wing;
            if(i<minPivot||!continuous(bars,i-wing,j,frame.duration())||atr[i]<=0)continue;
            Market.Candle p=bars.get(i);boolean hi=true,lo=true;double rightHigh=p.close,rightLow=p.close;
            for(int k=1;k<=wing;k++){
                hi&=p.high>=bars.get(i-k).high&&p.high>bars.get(i+k).high;
                lo&=p.low<=bars.get(i-k).low&&p.low<bars.get(i+k).low;
                rightHigh=Math.max(rightHigh,bars.get(i+k).close);rightLow=Math.min(rightLow,bars.get(i+k).close);
            }
            if(hi&&(p.high-rightLow)/atr[i]>=minimumRejectionAtr){Pivot pivot=new Pivot(i,p.high,atr[i],false,(p.high-rightLow)/atr[i]);events.add(pivot);register(pivot,j,work,bars,atr,frame,wing,lifecycle);}
            if(lo&&(rightHigh-p.low)/atr[i]>=minimumRejectionAtr){Pivot pivot=new Pivot(i,p.low,atr[i],true,(rightHigh-p.low)/atr[i]);events.add(pivot);register(pivot,j,work,bars,atr,frame,wing,lifecycle);}
        }
        List<Zone> zones=new ArrayList<>(),watchZones=new ArrayList<>();
        for(Working z:work)if(z.active&&z.tests>0&&n-1-z.lastPivot<=frame.lookback){
            zones.add(snapshot(z,frame,bars,false,n-1));
        }else if(!z.active&&z.breakAt>=0&&n-1-z.lastPivot<=frame.lookback){
            watchZones.add(snapshot(z,frame,bars,true,n-1));
        }
        zones.sort(Comparator.comparingDouble(z->z.center));
        List<Pivot> highs=new ArrayList<>(),lows=new ArrayList<>();for(Pivot p:events)(p.support?lows:highs).add(p);
        String structure="Mixed / range structure";
        if(highs.size()>=2&&lows.size()>=2){
            double dh=highs.get(highs.size()-1).price-highs.get(highs.size()-2).price,dl=lows.get(lows.size()-1).price-lows.get(lows.size()-2).price,margin=atr[n-1]*.10;
            if(dh>margin&&dl>margin)structure="Higher highs & higher lows";else if(dh<-margin&&dl<-margin)structure="Lower highs & lower lows";
        }
        return new Analysis(frame,bars,zones,watchZones,atr[n-1],broken,structure,lifecycle);
    }
    private static void register(Pivot p,int j,List<Working> work,List<Market.Candle> bars,double[] atr,Market.Frame frame,int wing,List<Event> lifecycle){
        Working best=null;double distance=Double.MAX_VALUE;
        for(Working z:work){
            if(!z.fits(p))continue;
            double buffer=Math.max(.05,z.seedAtr*.05),close=bars.get(j).close;
            boolean compatible=z.active?z.support==p.support:z.breakAt>=0&&p.index>z.breakAt&&z.watchSupport==p.support&&(p.support?close>z.high+buffer:close<z.low-buffer);
            if(!compatible)continue;
            double d=Math.abs(p.price-(z.min+z.max)/2);if(d<distance){distance=d;best=z;}
        }
        if(best==null){work.add(new Working(p,j,frame,bars,atr));return;}
        if(best.active){
            if(p.index-best.lastPivot>=wing+1&&best.departed&&best.departureAt<p.index){best.tests++;best.add(p,j,bars,atr);}
        }else{
            best.everFlipped=true;best.support=p.support;best.active=true;best.tests=1;best.visits.reset();best.rejectionTotal=0;best.breakAt=-1;best.add(p,j,bars,atr);lifecycle.add(new Event(EventKind.RETEST_HELD,snapshot(best,frame,bars,false,j),Market.closeTime(bars.get(j),frame.duration())));
        }
    }
    public static double overlap(Zone a,Zone b){double common=Math.max(0,Math.min(a.high,b.high)-Math.max(a.low,b.low));return common/Math.max(.00001,Math.min(a.width(),b.width()));}
    public static String context(Zone zone,Collection<Analysis> all){
        if(zone.reference||zone.breakPending)return "";
        long latest=0;
        for(Analysis analysis:all)if(!analysis.candles.isEmpty()){
            latest=Math.max(latest,Market.closeTime(analysis.candles.get(analysis.candles.size()-1),analysis.frame.duration()));
        }
        List<String> tags=new ArrayList<>();
        for(Analysis analysis:all){
            if(analysis.frame==zone.frame||!analysis.historyFresh(latest))continue;
            for(Zone other:analysis.zones)if(other.support==zone.support&&!other.reference&&!other.breakPending&&overlap(zone,other)>=.50){
                tags.add(analysis.frame.label);
                break;
            }
        }
        return String.join(" + ",tags);
    }
}
