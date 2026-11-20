package com.aurum.scalp;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class ZoneEvidenceTest {
    private static final long START=1767225600000L;
    private static final Market.Frame FRAME=Market.Frame.M5;

    private static Market.Candle bar(int index,double low,double high,double close){
        return new Market.Candle(START+index*FRAME.duration(),close,high,low,close);
    }

    @Test public void oneVisitIsCountedOnceOnBothSides(){
        for(boolean support:new boolean[]{true,false}){
            ZoneVisits evidence=new ZoneVisits();
            // Use mirrored prices to run the same evidence path for resistance.
            double[][] prices={{4005,4007,4006},{3999.8,4002,4000.5},{3999.8,4001.1,4000.8},{4000,4004,4003},{4003,4007,4006}};
            double previous=support?4006:3994;
            for(int i=0;i<prices.length;i++){
                double lo=support?prices[i][0]:8000-prices[i][1];
                double hi=support?prices[i][1]:8000-prices[i][0];
                double c=support?prices[i][2]:8000-prices[i][2];
                evidence.observe(i,bar(i,lo,hi,c),previous,true,3999,4001,support,4);
                previous=c;
                if(i<3)assertEquals(0,evidence.defended());
            }
            assertEquals(1,evidence.defended());
            assertEquals(0,evidence.failed());
            assertEquals(3,evidence.lastDefended());
        }
    }

    @Test public void aGapCannotTurnAnOpenVisitIntoADefense(){
        ZoneVisits evidence=new ZoneVisits();
        evidence.observe(0,bar(0,4004,4006,4005),4005,true,3999,4001,true,4);
        evidence.observe(1,bar(1,3999.5,4003,4000),4005,true,3999,4001,true,4);
        evidence.observe(5,bar(5,4003,4008,4007),4000,false,3999,4001,true,4);
        assertEquals(0,evidence.defended());
        evidence.observe(6,bar(6,3997,4008,3998),4007,true,3999,4001,true,4);
        assertEquals(1,evidence.failed());
        evidence.reset();
        assertEquals(0,evidence.defended());
        assertEquals(0,evidence.failed());
        assertEquals(-1,evidence.lastDefended());
    }

    private static List<Market.Candle> wave(){
        List<Market.Candle> candles=new ArrayList<>();
        for(int n=0;n<80;n++){
            double c=4000+6*Math.sin(n*Math.PI/6);
            candles.add(bar(n,c-1,c+1,c));
        }
        return candles;
    }

    @Test public void unfinishedTouchDoesNotChangeRejectionEvidence(){
        List<Market.Candle> candles=wave();
        long cutoff=START+candles.size()*FRAME.duration();
        ZoneEngine.Analysis before=ZoneEngine.calculate(candles,FRAME,cutoff);
        assertTrue(before.zones.stream().anyMatch(z->z.defendedVisits>0));
        candles.add(bar(80,3985,4050,4040));
        ZoneEngine.Analysis after=ZoneEngine.calculate(candles,FRAME,cutoff+10_000);
        assertEquals(before.zones.size(),after.zones.size());
        for(int n=0;n<before.zones.size();n++){
            ZoneEngine.Zone a=before.zones.get(n),b=after.zones.get(n);
            assertEquals(a.id,b.id);
            assertEquals(a.defendedVisits,b.defendedVisits);
            assertEquals(a.failedVisits,b.failedVisits);
            assertEquals(a.rejectionAtr,b.rejectionAtr,0);
        }
    }

    @Test public void firstAdverseCloseDemotesLevelAndDoesNotArmApproach(){
        List<Market.Candle> candles=wave();
        long close=START+candles.size()*FRAME.duration();
        ZoneEngine.Analysis before=ZoneEngine.calculate(candles,FRAME,close);
        SmartAlerts alerts=new SmartAlerts();
        for(long tick=close+1000;tick<close+FRAME.duration();tick+=15_000)evaluate(alerts,before,tick,4006);
        candles.add(bar(80,3989,3991,3990));
        close+=FRAME.duration();
        ZoneEngine.Analysis pressure=ZoneEngine.calculate(candles,FRAME,close);
        ZoneEngine.Zone zone=pressure.zones.stream().filter(z->z.breakPending&&z.support).findFirst().orElseThrow();
        double quote=zone.high+.01;
        assertTrue(pressure.events.isEmpty());
        assertFalse(ZoneSelection.confirmed(pressure,quote,true,5,ZoneSelection.Distance.ALL)
            .stream().anyMatch(z->z.id.equals(zone.id)));
        assertTrue(ZoneSelection.watches(pressure,quote,true,5,ZoneSelection.Distance.ALL)
            .stream().anyMatch(z->z.id.equals(zone.id)&&z.breakPending&&z.reference));
        assertTrue(ZoneSelection.watches(pressure,zone.low-1,true,5,ZoneSelection.Distance.ALL)
            .stream().anyMatch(z->z.id.equals(zone.id)&&z.breakPending&&z.distance(zone.low-1)==1));
        assertTrue(evaluate(alerts,pressure,close+1000,quote).isEmpty());
        candles.add(bar(81,3988,3990,3989));
        close+=FRAME.duration();
        ZoneEngine.Analysis broken=ZoneEngine.calculate(candles,FRAME,close);
        assertTrue(evaluate(alerts,broken,close+1000,3989).stream()
            .anyMatch(notification->notification.title.contains("Break confirmed")));
    }

    @Test public void overlapRequiresAreaAndRecentClosedFrame(){
        Market.Frame hour=Market.Frame.H1;
        ZoneEngine.Zone zone=new ZoneEngine.Zone(FRAME,4000,4001,2,true,false,false,2,START,START);
        ZoneEngine.Zone touching=new ZoneEngine.Zone(hour,4001,4003,4,true,false,false,2,START,START);
        ZoneEngine.Zone overlapping=new ZoneEngine.Zone(hour,4000.4,4003,4,true,false,false,2,START,START);
        ZoneEngine.Analysis recent=new ZoneEngine.Analysis(FRAME,List.of(bar(0,3999,4006,4005)),
            List.of(zone),List.of(),5,0,"Mixed");
        assertEquals("",ZoneEngine.context(zone,List.of(recent,analysis(hour,touching,START-hour.duration()))));
        assertEquals("1H",ZoneEngine.context(zone,List.of(recent,analysis(hour,overlapping,START-hour.duration()))));
        assertEquals("",ZoneEngine.context(zone,List.of(recent,analysis(hour,overlapping,START-5*hour.duration()))));
    }

    private static ZoneEngine.Analysis analysis(Market.Frame frame,ZoneEngine.Zone zone,long open){
        return new ZoneEngine.Analysis(frame,List.of(new Market.Candle(open,4004,4006,4003,4005)),
            List.of(zone),List.of(),5,0,"Mixed");
    }

    private static List<SmartAlerts.Alert> evaluate(SmartAlerts alerts,ZoneEngine.Analysis snapshot,long now,double mid){
        return alerts.evaluate(new Market.Quote(now,mid-.1,mid+.1),true,now,Map.of(FRAME,snapshot),Map.of(FRAME,now),Set.of(FRAME));
    }
}
