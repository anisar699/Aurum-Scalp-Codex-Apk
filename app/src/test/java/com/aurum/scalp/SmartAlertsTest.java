package com.aurum.scalp;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class SmartAlertsTest {
    static final long T=1767225600000L;static final Market.Frame F=Market.Frame.M5;
    private final SmartAlerts alerts=new SmartAlerts();
    private ZoneEngine.Zone zone(boolean ref){return new ZoneEngine.Zone(F,3999,4001,2,true,ref,false,3,T,T);}
    private ZoneEngine.Analysis snapshot(long close,ZoneEngine.Zone z,ZoneEngine.Event...events){return new ZoneEngine.Analysis(F,Collections.singletonList(new Market.Candle(close-F.duration(),4005,4010,3990,4005)),Collections.singletonList(z),Collections.emptyList(),10,0,"Mixed",Arrays.asList(events));}
    private List<SmartAlerts.Alert> at(long now,double price,ZoneEngine.Analysis a){return alerts.evaluate(new Market.Quote(now,price,price+.1),true,now,Collections.singletonMap(F,a),Collections.singletonMap(F,now),EnumSet.of(F));}
    @Test public void initialNearSnapshotIsSilent(){assertTrue(at(T+1000,4001,snapshot(T,zone(false))).isEmpty());}
    @Test public void departureThenApproachFiresOnce(){ZoneEngine.Analysis a=snapshot(T,zone(false));assertTrue(at(T+1000,4010,a).isEmpty());assertEquals(1,at(T+6000,4002,a).size());assertTrue(at(T+11000,4001,a).isEmpty());assertTrue(at(T+16000,4002,a).isEmpty());}
    @Test public void repeatedPayloadAndOutOfOrderTickCannotTrigger(){ZoneEngine.Analysis a=snapshot(T,zone(false));at(T+1000,4010,a);assertTrue(at(T+1000,4010,a).isEmpty());assertTrue(alerts.evaluate(new Market.Quote(T+500,4001,4001.1),true,T+1500,Collections.singletonMap(F,a),Collections.singletonMap(F,T+1500),EnumSet.of(F)).isEmpty());assertEquals(1,at(T+2000,4001,a).size());}
    @Test public void departureRearmsButCooldownPreventsSpam(){ZoneEngine.Analysis a=snapshot(T,zone(false));at(T+1000,4010,a);at(T+6000,4001,a);at(T+11000,4010,a);assertTrue(at(T+16000,4001,a).isEmpty());}
    @Test public void brokenWrongSideBidDoesNotGetApproach(){ZoneEngine.Analysis a=snapshot(T,zone(false));at(T+1000,4010,a);assertTrue(at(T+6000,3998,a).isEmpty());}
    @Test public void rangeReferenceNeverAlerts(){ZoneEngine.Analysis a=snapshot(T,zone(true));at(T+1000,4010,a);assertTrue(at(T+6000,4001,a).isEmpty());}
    @Test public void staleQuoteAndCachedHistoryNeverAlert(){ZoneEngine.Analysis a=snapshot(T,zone(false));at(T+1000,4010,a);assertTrue(alerts.evaluate(new Market.Quote(T,4001,4001.1),true,T+31000,Collections.singletonMap(F,a),Collections.singletonMap(F,T+31000),EnumSet.of(F)).isEmpty());assertTrue(at(T+32000,4001,a).isEmpty());assertTrue(alerts.evaluate(new Market.Quote(T+33000,4010,4010.1),true,T+33000,Collections.singletonMap(F,a),Collections.emptyMap(),EnumSet.of(F)).isEmpty());}
    @Test public void untrustedClockOrSavedQuoteNeverAlerts(){ZoneEngine.Analysis a=snapshot(T,zone(false));at(T+1000,4010,a);assertTrue(alerts.evaluate(new Market.Quote(T+2000,4001,4001.1),false,T+2000,Collections.singletonMap(F,a),Collections.singletonMap(F,T),EnumSet.of(F)).isEmpty());assertTrue(alerts.evaluate(new Market.Quote(T+2000,4001,4001.1),true,0,Collections.singletonMap(F,a),Collections.singletonMap(F,T),EnumSet.of(F)).isEmpty());}
    @Test public void futureQuoteIsRejected(){ZoneEngine.Analysis a=snapshot(T,zone(false));at(T+1000,4010,a);assertTrue(alerts.evaluate(new Market.Quote(T+60000,4001,4001.1),true,T+2000,Collections.singletonMap(F,a),Collections.singletonMap(F,T),EnumSet.of(F)).isEmpty());}
    @Test public void disconnectedGapPrimesSilently(){ZoneEngine.Analysis a=snapshot(T,zone(false));at(T+1000,4010,a);assertTrue(at(T+100000,4001,a).isEmpty());}
    @Test public void reconnectPreservesTenMinuteZoneCooldown(){
        ZoneEngine.Zone z=zone(false);
        ZoneEngine.Analysis a=snapshot(T,z);
        at(T+1_000,4010,a);
        assertEquals(1,at(T+6_000,4001,a).size());
        alerts.pause();
        assertFalse(alerts.armed(F));
        assertTrue(at(T+140_000,4001,a).isEmpty());
        assertTrue(at(T+145_000,4010,a).isEmpty());
        assertTrue(at(T+150_000,4001,a).isEmpty());
        assertTrue(at(T+300_000,4010,snapshot(T+300_000,z)).isEmpty());
        assertTrue(at(T+305_000,4001,snapshot(T+300_000,z)).isEmpty());
        assertTrue(at(T+605_000,4010,snapshot(T+600_000,z)).isEmpty());
        assertEquals(1,at(T+610_000,4001,snapshot(T+600_000,z)).size());
    }
    @Test public void temporarilyMissingZoneDoesNotLoseItsCooldown(){
        ZoneEngine.Zone z=zone(false);
        ZoneEngine.Analysis original=snapshot(T,z);
        ZoneEngine.Analysis missing=new ZoneEngine.Analysis(F,original.candles,List.of(),List.of(),10,0,"Mixed");
        at(T+1_000,4010,original);
        assertEquals(1,at(T+6_000,4001,original).size());
        assertTrue(at(T+130_000,4010,missing).isEmpty());
        assertTrue(at(T+135_000,4010,original).isEmpty());
        assertTrue(at(T+140_000,4001,original).isEmpty());
    }
    @Test public void pinDoesNotReplaceAutomaticAlertsForTheSameZone(){
        ZoneEngine.Zone z=zone(false);
        ZoneSelection.Pin pin=new ZoneSelection.Pin(F,z.id,z.low,z.high,z.support,ZoneSelection.Trigger.RETEST);
        long next=T+F.duration();
        var initial=snapshot(T,z);
        var after=snapshot(next,z,new ZoneEngine.Event(ZoneEngine.EventKind.BREAK,z,next),
            new ZoneEngine.Event(ZoneEngine.EventKind.RETEST_HELD,z,next));
        alerts.evaluate(new Market.Quote(next-15_000,4010,4010.1),true,next-15_000,
            Map.of(F,initial),Map.of(F,next-15_000),Set.of(F),Map.of(F,pin));
        var approach=alerts.evaluate(new Market.Quote(next-10_000,4001,4001.1),true,next-10_000,
            Map.of(F,initial),Map.of(F,next-10_000),Set.of(F),Map.of(F,pin));
        assertEquals(List.of("5m · Zone approach"),approach.stream().map(a->a.title).toList());
        var events=alerts.evaluate(new Market.Quote(next+1_000,4001,4001.1),true,next+1_000,
            Map.of(F,after),Map.of(F,next+1_000),Set.of(F),Map.of(F,pin));
        assertEquals(List.of("5m · Break confirmed","5m · Pinned · Retest held"),events.stream().map(a->a.title).toList());
    }
    @Test public void httpDatePrecisionCorrectionDoesNotEraseArmedApproach(){ZoneEngine.Analysis a=snapshot(T,zone(false));at(T+1000,4010,a);assertEquals(1,alerts.evaluate(new Market.Quote(T+1500,4001,4001.1),true,T+900,Collections.singletonMap(F,a),Collections.singletonMap(F,T+900),EnumSet.of(F)).size());}
    @Test public void newlyClosedEventsAreDeduplicated(){ZoneEngine.Zone z=zone(false);ZoneEngine.Analysis before=snapshot(T,z);at(T+1000,4010,before);for(long t=T+21000;t<T+300000;t+=20000)at(t,4010,before);ZoneEngine.Event event=new ZoneEngine.Event(ZoneEngine.EventKind.BREAK,z,T+300000);ZoneEngine.Analysis after=snapshot(T+300000,z,event);assertEquals(1,at(T+301000,4010,after).size());assertTrue(at(T+306000,4010,after).isEmpty());}
    @Test public void initialHistoryEventsAreNeverReplayed(){ZoneEngine.Zone z=zone(false);ZoneEngine.Analysis a=snapshot(T,z,new ZoneEngine.Event(ZoneEngine.EventKind.BREAK,z,T));assertTrue(at(T+1000,4010,a).isEmpty());assertTrue(at(T+6000,4010,a).isEmpty());}
    @Test public void disabledFrameCannotAlert(){ZoneEngine.Analysis a=snapshot(T,zone(false));at(T+1000,4010,a);assertTrue(alerts.evaluate(new Market.Quote(T+6000,4001,4001.1),true,T+6000,Collections.singletonMap(F,a),Collections.singletonMap(F,T+6000),EnumSet.of(Market.Frame.H4)).isEmpty());}
    @Test public void tickOlderThanNewCandleCannotDriveAlerts(){ZoneEngine.Analysis a=snapshot(T,zone(false));at(T+1000,4010,a);assertTrue(alerts.evaluate(new Market.Quote(T+2000,4001,4001.1),true,T+4000,Collections.singletonMap(F,snapshot(T+3000,zone(false))),Collections.singletonMap(F,T+4000),EnumSet.of(F)).isEmpty());}
    @Test public void resetSuppressesOldEventReplay(){ZoneEngine.Zone z=zone(false);at(T+1000,4010,snapshot(T,z));alerts.reset();assertTrue(at(T+301000,4010,snapshot(T+300000,z,new ZoneEngine.Event(ZoneEngine.EventKind.RETEST_HELD,z,T+300000))).isEmpty());}
}
