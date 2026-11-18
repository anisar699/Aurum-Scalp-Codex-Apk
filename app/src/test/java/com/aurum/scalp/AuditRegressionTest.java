package com.aurum.scalp;

import org.junit.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

/** User-visible failure scenarios from the external v2.0 audit. */
public class AuditRegressionTest {
    private static final Market.Frame F=Market.Frame.M5;
    private static final long T=Instant.parse("2026-11-09T00:00:00Z").toEpochMilli();
    private static Market.Candle candle(long t,double c){return new Market.Candle(t,c,c+1,c-1,c);}
    private static void add(List<Market.Candle> b,double c){b.add(candle(b.get(b.size()-1).time+F.duration(),c));}
    private static ZoneEngine.Analysis analyse(List<Market.Candle> b){return ZoneEngine.calculate(b,F,b.get(b.size()-1).time+F.duration());}
    private static List<Market.Candle> support(){
        List<Market.Candle> b=new ArrayList<>();for(int i=0;i<80;i++)b.add(new Market.Candle(T+i*F.duration(),4000,4001,i==45?3995:3999,4000));return b;
    }
    private static List<Market.Candle> broken(){List<Market.Candle> b=support();add(b,3990);add(b,3989);return b;}
    private static boolean watches(ZoneEngine.Analysis a){return a.watchZones.stream().anyMatch(z->!z.support&&Math.abs(z.center-3995)<.05);}
    @Test public void brokenZoneStaysVisibleWhenLastCloseIsInsideRetestBand(){
        List<Market.Candle> b=broken();assertTrue(watches(analyse(b)));add(b,3995);
        ZoneEngine.Analysis a=analyse(b);assertTrue(watches(a));
        ZoneEngine.Zone watch=a.watch(3995,false,5).stream().filter(z->Math.abs(z.center-3995)<.05).findFirst().orElseThrow();
        assertTrue(watch.contains(3995));assertEquals(0,watch.tests);assertTrue(watch.reference);
    }
    @Test public void pendingWatchSurvivesFirstBarAfterSessionBreak(){
        List<Market.Candle> b=broken();b.add(candle(b.get(b.size()-1).time+60*Market.MINUTE,3990));assertTrue(watches(analyse(b)));
    }
    @Test public void oppositeSideReclaimNeedsTwoCloses(){
        List<Market.Candle> b=broken();add(b,3996);assertTrue(watches(analyse(b)));add(b,3997);assertFalse(watches(analyse(b)));
    }
    @Test public void reclaimClosesMustBeContinuous(){
        List<Market.Candle> b=broken();add(b,3996);b.add(candle(b.get(b.size()-1).time+60*Market.MINUTE,3997));assertTrue(watches(analyse(b)));add(b,3998);assertFalse(watches(analyse(b)));
    }
    @Test public void oppositeRoleSwingIsNotSwallowedByAnActiveSupport(){
        List<Market.Candle> b=support();
        for(int i=0;i<40;i++){long t=b.get(b.size()-1).time+F.duration();b.add(new Market.Candle(t,3994.9,3995,3994.8,3994.9));}
        long t=b.get(b.size()-1).time+F.duration();b.add(new Market.Candle(t,3994.9,3995.02,3994.8,3994.9));
        for(int i=0;i<2;i++){t+=F.duration();b.add(new Market.Candle(t,3994.9,3995,3994.8,3994.9));}
        ZoneEngine.Analysis a=analyse(b);
        assertTrue(a.zones.stream().anyMatch(z->z.support&&Math.abs(z.center-3995)<.05));
        assertTrue(a.zones.stream().anyMatch(z->!z.support&&Math.abs(z.center-3995.02)<.005));
    }
    @Test public void pivotRetestCanConfirmAfterPreviousCloseInsideOldBand(){
        List<Market.Candle> b=broken();long t=b.get(b.size()-1).time+F.duration();
        b.add(new Market.Candle(t,3994,3995,3993,3994.8));
        b.add(new Market.Candle(t+F.duration(),3994.8,3994.95,3994,3994.9));
        b.add(new Market.Candle(t+2*F.duration(),3993.5,3994,3993,3993.5));
        ZoneEngine.Zone z=analyse(b).zones.stream().filter(q->q.flipped&&!q.support&&Math.abs(q.center-3995)<.05).findFirst().orElseThrow();
        assertEquals(1,z.tests);assertFalse(z.reference);
    }
    @Test public void departureDuringConfirmationWindowCountsForLaterReaction(){
        List<Market.Candle> b=new ArrayList<>();
        for(int i=0;i<48;i++)b.add(new Market.Candle(T+i*F.duration(),3996,3996.2,i==45?3995:3995.8,3996));
        b.add(new Market.Candle(T+48*F.duration(),3995.2,3995.3,3995.05,3995.15));
        b.add(new Market.Candle(T+49*F.duration(),3995.3,3995.35,3995.2,3995.3));
        b.add(new Market.Candle(T+50*F.duration(),3995.3,3995.35,3995.2,3995.3));
        assertTrue(analyse(b).zones.stream().anyMatch(z->z.support&&Math.abs(z.center-3995.025)<.05&&z.tests==2));
    }
    @Test public void newlyMergedBandNeedsTwoFreshBreakCloses(){
        List<Market.Candle> b=support();long t=b.get(b.size()-1).time+F.duration();
        b.add(new Market.Candle(t,3995.5,3996,3994.5,3995.5));
        b.add(new Market.Candle(t+F.duration(),3996.5,3997,3996,3996.5));
        b.add(new Market.Candle(t+2*F.duration(),3994.6,3995,3994.55,3994.6));
        ZoneEngine.Zone merged=analyse(b).zones.stream().filter(z->z.support&&z.tests==2).findFirst().orElseThrow();
        add(b,3994);assertTrue(analyse(b).zones.stream().anyMatch(z->z.support&&Math.abs(z.center-merged.center)<.01));
        add(b,3993.9);assertFalse(analyse(b).zones.stream().anyMatch(z->z.support&&Math.abs(z.center-merged.center)<.01));
    }
    @Test public void earliestEligiblePivotInLookbackIsIncluded(){
        Market.Frame f=Market.Frame.M1;List<Market.Candle> b=new ArrayList<>();
        for(int i=0;i<400;i++)b.add(new Market.Candle(T+i*f.duration(),4000,4001,i==40?3900:3999,4000));
        ZoneEngine.Analysis a=ZoneEngine.calculate(b,f,T+400*f.duration());assertTrue(a.zones.stream().anyMatch(z->Math.abs(z.center-3900)<.01));
    }
    @Test public void nativeFourHourReopenFragmentsCloseAtNextUtcBoundary(){
        long midnight=Instant.parse("2026-11-09T00:00:00Z").toEpochMilli();
        for(int h:new int[]{22,23}){
            Market.Candle c=candle(midnight-(24-h)*60*Market.MINUTE,4000);
            assertTrue(Market.closed(Collections.singletonList(c),Market.Frame.H4.duration(),midnight-1).isEmpty());
            assertEquals(1,Market.closed(Collections.singletonList(c),Market.Frame.H4.duration(),midnight).size());
        }
    }
    @Test public void nativeFourHourTimestampsStillRequireWholeHours(){
        assertTrue(Market.closed(Collections.singletonList(candle(T+Market.MINUTE,4000)),Market.Frame.H4.duration(),T+24*60*Market.MINUTE).isEmpty());
    }
    @Test public void fragmentConfirmationTimestampNeverLiesInTheFuture(){
        Market.Frame f=Market.Frame.H4;long friday=Instant.parse("2026-11-06T20:00:00Z").toEpochMilli(),sunday=Instant.parse("2026-11-08T23:00:00Z").toEpochMilli(),cutoff=Instant.parse("2026-11-09T00:00:00Z").toEpochMilli();
        List<Market.Candle> b=new ArrayList<>();for(int i=0;i<80;i++)b.add(new Market.Candle(friday-(79-i)*f.duration(),4000,i==78?4050:4001,3999,4000));b.add(candle(sunday,4000));
        ZoneEngine.Zone z=ZoneEngine.calculate(b,f,cutoff).zones.stream().filter(q->Math.abs(q.center-4050)<.01).findFirst().orElseThrow();assertEquals(cutoff,z.confirmedAt);
    }
    @Test public void fourHourWinterWeekendAndFragmentContinuityAreAccepted(){
        List<Market.Candle> b=Arrays.asList(candle(Instant.parse("2026-11-06T20:00:00Z").toEpochMilli(),4000),candle(Instant.parse("2026-11-08T23:00:00Z").toEpochMilli(),4000),candle(Instant.parse("2026-11-09T00:00:00Z").toEpochMilli(),4000));
        assertTrue(ZoneEngine.continuous(b,0,2,Market.Frame.H4.duration()));
    }
    @Test public void unexpectedFourHourDaytimeHoleIsRejected(){
        List<Market.Candle> b=Arrays.asList(candle(T+4*60*Market.MINUTE,4000),candle(T+12*60*Market.MINUTE,4000));assertFalse(ZoneEngine.continuous(b,0,1,Market.Frame.H4.duration()));
    }
    @Test public void quoteStatesAgeAfterConnectionInsteadOfBecomingSaved(){
        Market.Quote q=new Market.Quote(T,4000,4000.5);
        assertEquals("● LIVE",Market.quoteState(q,true,T+6000));assertEquals("● DELAYED",Market.quoteState(q,true,T+31_000));assertEquals("● STALE",Market.quoteState(q,true,T+91_000));assertEquals("● SAVED",Market.quoteState(q,false,T+6000));assertEquals("CONNECTING",Market.quoteState(null,false,T));
    }
    @Test public void sundayReopeningBelongsToMondayAndPriorIsFriday(){
        for(String[] date:new String[][]{
            {"2026-11-06T17:00:00Z","2026-11-08T23:00:00Z","2026-11-09T10:00:00Z"},
            {"2026-07-10T16:00:00Z","2026-07-12T22:00:00Z","2026-07-13T10:00:00Z"}}){
            long friday=Instant.parse(date[0]).toEpochMilli(),sunday=Instant.parse(date[1]).toEpochMilli(),monday=Instant.parse(date[2]).toEpochMilli();
            List<Market.Candle> bars=new ArrayList<>();
            for(int i=0;i<4;i++)bars.add(candle(friday+i*Market.Frame.H1.duration(),4000));
            for(int i=0;i<6;i++)bars.add(candle(sunday+i*Market.Frame.H1.duration(),4100));
            Market.PriorSession prior=Market.priorSession(Map.of(Market.Frame.H1,bars),monday);
            assertEquals(Market.tradingDay(friday),prior.day);
            assertEquals(4,prior.bars);
            assertEquals(4001,prior.high,0);
            assertEquals(Market.tradingDay(monday),Market.tradingDay(sunday));
        }
    }
    @Test public void priorSessionChoosesWiderCoverageAndSkipsTinySessions(){
        long friday=Instant.parse("2026-11-06T12:00:00Z").toEpochMilli(),now=Instant.parse("2026-11-09T10:00:00Z").toEpochMilli();
        List<Market.Candle> hourly=new ArrayList<>(),five=new ArrayList<>();
        for(int i=0;i<5;i++)hourly.add(candle(friday+i*Market.Frame.H1.duration(),4010));
        for(int i=0;i<48;i++)five.add(candle(friday+i*F.duration(),4000));
        assertNull(Market.priorSession(Map.of(F,List.of(five.get(0))),now));
        assertEquals(Market.Frame.H1,Market.priorSession(Map.of(F,five,Market.Frame.H1,hourly),now).frame);
    }
    @Test public void manualRefreshPreservesProviderHoldsAndItsMinimumSpacing(){
        PollPolicy policy=new PollPolicy();
        assertTrue(policy.claim("quote",T,false));
        policy.complete("quote",T,30_000,false);
        assertFalse(policy.claim("quote",T+9_999,true));
        assertTrue(policy.claim("quote",T+10_000,true));
        policy.complete("quote",T+10_000,60_000,true);
        assertFalse(policy.claim("H1",T+69_999,true));
        assertTrue(policy.claim("H1",T+70_000,true));
    }
    @Test public void retryAfterSupportsSecondsDatesAndSafeFallback(){
        assertEquals(120_000,RetryGate.retryAfter("120",T));assertEquals(120_000,RetryGate.retryAfter("Mon, 9 Nov 2026 00:02:00 GMT",T));assertEquals(60_000,RetryGate.retryAfter("invalid",T));assertEquals(60_000,RetryGate.retryAfter(null,T));
        assertEquals(2_000,RetryGate.retryAfter("2",T));
    }
}
