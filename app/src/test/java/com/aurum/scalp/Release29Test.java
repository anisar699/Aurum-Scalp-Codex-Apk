package com.aurum.scalp;

import static org.junit.Assert.*;
import java.net.URL;
import java.io.IOException;
import java.util.*;
import org.junit.Test;

public class Release29Test {
    private static final long CLOSE=java.time.Instant.parse("2026-11-18T16:00:00Z").toEpochMilli();

    private static ZoneEngine.Zone zone(Market.Frame frame,long pivot,double low,double high,boolean support,boolean flipped){
        return new ZoneEngine.Zone(frame,low,high,4,support,false,flipped,3,pivot,pivot);
    }

    private static ZoneEngine.Analysis snapshot(Market.Frame frame,long close,ZoneEngine.Zone zone,ZoneEngine.Event... events){
        Market.Candle bar=new Market.Candle(close-frame.duration(),4000,4010,3990,4000,close);
        return new ZoneEngine.Analysis(frame,List.of(bar),List.of(zone),List.of(),10,0,"Mixed",List.of(events));
    }

    private static List<SmartAlerts.Alert> evaluate(SmartAlerts alerts,ZoneEngine.Analysis snapshot,long now,boolean live){
        return alerts.evaluate(new Market.Quote(now,4010,4010.1),live,now,
            Map.of(snapshot.frame,snapshot),Map.of(snapshot.frame,now),Set.of(snapshot.frame));
    }

    @Test public void delayedHourlyHistorySendsExactlyOneVisibleConfirmation(){
        var frame=Market.Frame.H1;
        var level=zone(frame,CLOSE-2*frame.duration(),3999,4001,true,false);
        var before=snapshot(frame,CLOSE-frame.duration(),level);
        var after=snapshot(frame,CLOSE,level,new ZoneEngine.Event(ZoneEngine.EventKind.BREAK,level,CLOSE));
        SmartAlerts alerts=new SmartAlerts();
        evaluate(alerts,before,CLOSE-1000,true);
        evaluate(alerts,before,CLOSE+5_000,false);
        long arrived=CLOSE+170_000;
        List<SmartAlerts.Alert> result=evaluate(alerts,after,arrived,true);
        assertEquals(1,result.size());
        assertTrue(result.get(0).detail.contains("confirmed 170s ago"));
        assertTrue(evaluate(alerts,after,arrived+1000,true).isEmpty());
    }

    @Test public void persistedWatermarkRecoversRecentButNotExpiredEvents(){
        var frame=Market.Frame.H4;
        var level=zone(frame,CLOSE-2*frame.duration(),3999,4001,true,false);
        Map<Market.Frame,Long> persisted=new EnumMap<>(Market.Frame.class);
        SmartAlerts.Watermarks store=new SmartAlerts.Watermarks(){
            public long load(Market.Frame key){return persisted.getOrDefault(key,0L);}
            public void store(Market.Frame key,long close){persisted.put(key,close);}
        };
        evaluate(new SmartAlerts(store),snapshot(frame,CLOSE-frame.duration(),level),CLOSE-2_000,true);
        var after=snapshot(frame,CLOSE,level,new ZoneEngine.Event(ZoneEngine.EventKind.RETEST_HELD,level,CLOSE));
        assertEquals(1,evaluate(new SmartAlerts(store),after,CLOSE+25_000,true).size());
        assertTrue(evaluate(new SmartAlerts(store),after,CLOSE+26_000,true).isEmpty());
        var next=snapshot(frame,CLOSE+frame.duration(),level,new ZoneEngine.Event(ZoneEngine.EventKind.BREAK,level,CLOSE+frame.duration()));
        assertTrue(evaluate(new SmartAlerts(store),next,CLOSE+frame.duration()+HistorySchedule.eventWindow(frame)+1,true).isEmpty());
    }

    @Test public void closeScheduleFindsEverySampledMacroClose(){
        Random rng=new Random(29);
        for(var frame:List.of(Market.Frame.H1,Market.Frame.H4)){
            for(int sample=0;sample<1000;sample++){
                HistorySchedule schedule=new HistorySchedule();
                long before=CLOSE-1-rng.nextInt((int)frame.duration()-6000);
                long next=before+schedule.afterResponse(frame,before,CLOSE-frame.duration(),false,true);
                while(next<CLOSE)next+=schedule.afterResponse(frame,next,CLOSE-frame.duration(),false,true);
                assertEquals(CLOSE+HistorySchedule.GRACE,next);
                int lag=rng.nextInt(20_001);
                int tries=0;
                while(next<CLOSE+lag){
                    next+=schedule.afterResponse(frame,next,CLOSE-frame.duration(),false,false);
                    assertTrue(++tries<=3);
                }
                assertTrue(next-CLOSE<=HistorySchedule.eventWindow(frame));
            }
        }
    }

    @Test public void shortTailAndHourlyResyncAreBounded(){
        HistorySchedule schedule=new HistorySchedule();
        var frame=Market.Frame.M5;
        assertEquals(720,schedule.requestLimit(frame,0,0,CLOSE,720));
        assertEquals(frame.duration(),schedule.afterResponse(frame,CLOSE+5000,CLOSE,false,true));
        assertEquals(5,schedule.requestLimit(frame,288,CLOSE,CLOSE+frame.duration(),720));
        assertEquals(720,schedule.requestLimit(frame,288,CLOSE,CLOSE+HistorySchedule.RESYNC+5000,720));
    }

    @Test public void backgroundShadeAndDefaultFramesUseAtMostThirtyHistoryCallsPerWarmHour(){
        int requests=0,estimatedBytes=0;
        long hour=CLOSE+2*HistorySchedule.RESYNC+5_000;
        for(Market.Frame frame:List.of(Market.Frame.M5,Market.Frame.M15,Market.Frame.H1,Market.Frame.H4)){
            HistorySchedule schedule=new HistorySchedule();
            long next=hour;
            long duration=frame.duration();
            schedule.afterResponse(frame,hour-10*Market.MINUTE,hour/duration*duration-duration,false,true);
            while(next<hour+HistorySchedule.RESYNC){
                long close=(next-HistorySchedule.GRACE)/duration*duration;
                int limit=schedule.requestLimit(frame,168,close,next,168);
                requests++;
                estimatedBytes+=160*(limit+1);
                next+=schedule.afterResponse(frame,next,close,false,limit==168);
            }
        }
        assertTrue(requests<=30);
        assertTrue(estimatedBytes<200_000);
    }

    @Test public void restingStreamSurvivesRestLimitAndAccessBackoffsStayScoped(){
        PollPolicy policy=new PollPolicy();
        policy.limit(PollPolicy.REST,10_000,120_000,429);
        assertFalse(policy.claim("H4",129_999,true));
        assertTrue(policy.claim(PollPolicy.STREAM,10_001,false));
        assertTrue(policy.claim("H4",130_000,false));
        assertEquals(120_000,policy.refusal("H4",130_001,403,0));
        assertTrue(policy.claim("quote",130_002,false));
        assertEquals(240_000,policy.refusal("H4",250_001,403,0));
        assertEquals(480_000,policy.refusal("H4",490_001,403,0));
    }

    @Test public void hubHeartbeatPingsAndDetectsSilence(){
        SignalRHeartbeat hub=new SignalRHeartbeat();
        hub.start(1000);
        assertEquals(SignalRHeartbeat.Action.WAIT,hub.poll(15999));
        assertEquals(SignalRHeartbeat.Action.PING,hub.poll(16000));
        assertEquals("{\"type\":6}\u001e",SignalRHeartbeat.PING);
        hub.received(29000);
        assertEquals(SignalRHeartbeat.Action.PING,hub.poll(31000));
        assertEquals(SignalRHeartbeat.Action.RECONNECT,hub.poll(64000));
        hub.stop();
        assertEquals(SignalRHeartbeat.Action.WAIT,hub.poll(99999));
    }

    @Test public void truncatedDateSamplesKeepTimeMonotonicAndTicksInRange(){
        MarketClock clock=new MarketClock();
        long last=0;
        for(int i=0;i<120;i++){
            long began=1_000+i*1573L,rtt=50+(i*213)%751;
            long actual=CLOSE+began+rtt;
            long serverTime=actual-rtt/2;
            clock.sample(serverTime/1000*1000,began,began+rtt);
            long estimated=clock.now(began+rtt);
            assertTrue(estimated>=last);
            assertTrue(Math.abs(estimated-actual)<=1000);
            assertEquals("● LIVE",Market.quoteState(new Market.Quote(actual-200,4000,4000.5),true,estimated));
            last=estimated;
        }
    }

    @Test public void rolledAndFlippedBandCanKeepPinIdentity(){
        var frame=Market.Frame.H1;
        var original=zone(frame,CLOSE-frame.duration(),3999,4001,true,false);
        var pin=new ZoneSelection.Pin(frame,original.id,original.low,original.high,true,ZoneSelection.Trigger.RETEST);
        var reformed=zone(frame,CLOSE-2*frame.duration(),3999.3,4001.4,false,true);
        assertSame(reformed,ZoneSelection.resolve(pin,snapshot(frame,CLOSE,reformed)));
        assertNull(ZoneSelection.resolve(pin,snapshot(frame,CLOSE,zone(frame,CLOSE-3*frame.duration(),4020,4023,true,false))));
    }

    @Test public void fridayGapDoesNotInflateAtr(){
        long friday=java.time.Instant.parse("2026-11-13T20:00:00Z").toEpochMilli();
        long sunday=java.time.Instant.parse("2026-11-15T23:00:00Z").toEpochMilli();
        List<Market.Candle> bars=List.of(new Market.Candle(friday,4000,4001,3999,4000),
            new Market.Candle(sunday,4050,4051,4049,4050));
        assertEquals(2,ZoneEngine.atrSeries(bars,Market.Frame.H1.duration())[1],.001);
    }

    @Test public void redirectStaysOnCanonicalHttpsOrigin()throws Exception{
        URL source=new URL("https://biquote.io/api/XAUUSD");
        assertEquals("https://biquote.io/api/next",MarketRepository.redirect(source,"/api/next").toString());
        assertThrows(IOException.class,()->MarketRepository.redirect(source,"http://biquote.io/api/next"));
        assertThrows(IOException.class,()->MarketRepository.redirect(source,"https://biquote.io.other.test/api/next"));
        assertThrows(IOException.class,()->MarketRepository.redirect(source,"https://biquote.io:8443/api/next"));
    }

    @Test public void narrowAlertBandRoundsOutwards(){
        assertEquals("4000.12",PriceFormat.low(4000.129));
        assertEquals("4000.13",PriceFormat.high(4000.129));
    }
}
