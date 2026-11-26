package com.aurum.scalp;

import java.util.List;
import java.util.Map;

final class ScalpSetup {
    private static final Market.Frame M5=Market.Frame.M5;
    private static final long FIVE=M5.duration();
    private static final double BUFFER=.05;
    private static final int WINDOW=6;

    enum Stage {
        WAITING("Waiting for live data"),
        NO_NEARBY_ZONE("No nearby confirmed 1H / 4H zone"),
        WATCHING("Watching"),
        RECLAIMED("Reclaim confirmed"),
        PRESSURED("Beyond invalidation · awaiting a 5m close"),
        INVALIDATED("Invalidated");

        final String label;
        Stage(String label){this.label=label;}
    }

    record Readout(Stage stage,ZoneEngine.Zone anchor,ZoneEngine.Zone context,long eventTime) {}
    private record Confirmation(Stage stage,long time) {}

    private ScalpSetup() {}

    static Readout evaluate(Map<Market.Frame,ZoneEngine.Analysis> analyses,Map<Market.Frame,Long> synced,
                            Market.Quote quote,boolean online,long now) {
        if(!SmartAlerts.usableQuote(quote,online,now))return empty(Stage.WAITING);
        for(Market.Frame frame:List.of(M5,Market.Frame.M15,Market.Frame.H1,Market.Frame.H4)){
            ZoneEngine.Analysis analysis=analyses.get(frame);
            if(analysis==null||analysis.atr<=0||analysis.candles.size()<HistorySchedule.MIN_BARS
                ||!analysis.historyFresh(now)||!DataFreshness.verified(now,synced.getOrDefault(frame,0L),frame))
                return empty(Stage.WAITING);
        }
        ZoneEngine.Analysis five=analyses.get(M5);
        long close=DataFreshness.lastClose(five);
        if(close<Math.floorDiv(now-HistorySchedule.GRACE,FIVE)*FIVE||quote.time<close)return empty(Stage.WAITING);

        double price=quote.mid(),bestDistance=Double.POSITIVE_INFINITY;
        Readout best=empty(Stage.NO_NEARBY_ZONE);
        for(Market.Frame frame:List.of(Market.Frame.H1,Market.Frame.H4)){
            for(ZoneEngine.Zone zone:analyses.get(frame).zones){
                if(zone.reference||zone.breakPending||zone.confirmedAt<=0||zone.confirmedAt>close)continue;
                double distance=zone.distance(price);
                if(distance>2*five.atr||distance>=bestDistance)continue;
                Confirmation confirmation=confirmation(zone,five.candles);
                Stage stage=confirmation.stage;
                if(stage==Stage.WATCHING&&(!zone.validSide(price)||distance>five.atr))continue;
                if(stage==Stage.RECLAIMED&&beyond(zone,price))stage=Stage.PRESSURED;
                bestDistance=distance;
                best=new Readout(stage,zone,overlap(zone,analyses.get(Market.Frame.M15)),confirmation.time);
            }
        }
        return best;
    }

    private static Readout empty(Stage stage){return new Readout(stage,null,null,0);}

    private static Confirmation confirmation(ZoneEngine.Zone zone,List<Market.Candle> bars) {
        Stage state=Stage.WATCHING;
        long event=0;
        for(int i=Math.max(1,bars.size()-WINDOW);i<bars.size();i++){
            Market.Candle prior=bars.get(i-1),bar=bars.get(i);
            if(Market.closeTime(prior,FIVE)!=bar.time||bar.time<zone.confirmedAt){
                state=Stage.WATCHING;
                event=0;
                continue;
            }
            long close=Market.closeTime(bar,FIVE);
            if(bar.low<=zone.high&&bar.high>=zone.low&&reclaims(zone,bar,prior.close)){
                state=Stage.RECLAIMED;
                event=close;
            }else if(state==Stage.RECLAIMED&&beyond(zone,bar.close)){
                state=Stage.INVALIDATED;
                event=close;
            }
        }
        return new Confirmation(state,event);
    }

    private static boolean reclaims(ZoneEngine.Zone zone,Market.Candle candle,double previousClose){
        return zone.support
            ?previousClose>=zone.high&&candle.open>=zone.low&&candle.close>=zone.high+BUFFER
            :previousClose<=zone.low&&candle.open<=zone.high&&candle.close<=zone.low-BUFFER;
    }

    private static boolean beyond(ZoneEngine.Zone zone,double price){
        double edge=invalidation(zone);
        return zone.support?price<=edge:price>=edge;
    }

    static double invalidation(ZoneEngine.Zone zone){return zone.support?zone.low-BUFFER:zone.high+BUFFER;}

    private static ZoneEngine.Zone overlap(ZoneEngine.Zone anchor,ZoneEngine.Analysis intraday){
        ZoneEngine.Zone best=null;
        double shared=0;
        for(ZoneEngine.Zone zone:intraday.zones){
            if(zone.reference||zone.breakPending||zone.support!=anchor.support)continue;
            double ratio=ZoneEngine.overlap(anchor,zone);
            if(ratio>shared){shared=ratio;best=zone;}
        }
        return best;
    }
}
