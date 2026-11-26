package com.aurum.scalp;

import org.junit.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SessionLevelsTest {
    private static final long FIVE=Market.Frame.M5.duration(), HOUR=Market.Frame.H1.duration();
    private static long time(String utc){return Instant.parse(utc).toEpochMilli();}

    private static List<Market.Candle> bars(long start,int count,long duration,double high,double low){
        List<Market.Candle> out=new ArrayList<>();
        for(int i=0;i<count;i++){
            long t=start+i*duration;
            out.add(new Market.Candle(t,4000,high,low,4000,t+duration));
        }
        return out;
    }

    private static SessionLevels.Level level(SessionLevels.Snapshot s,String label){
        return s.levels().stream().filter(l->l.label().equals(label)).findFirst().orElse(null);
    }

    @Test public void londonAndNewYorkOpeningsFollowLocalDstCalendars(){
        long asia=time("2026-04-06T00:00:00Z"), london=time("2026-04-06T07:00:00Z"), ny=time("2026-04-06T12:00:00Z");
        List<Market.Candle> five=bars(asia,72,FIVE,4005,3998);
        five.addAll(bars(london,12,FIVE,4008,3997));
        five.addAll(bars(ny,12,FIVE,4011,3996));
        var s=SessionLevels.calculate(five,List.of(),ny+HOUR);
        assertEquals(4005,level(s,"Asia H").price(),0);
        assertEquals(london+HOUR,level(s,"London OR H").availableAt());
        assertEquals(4008,level(s,"London OR H").price(),0);
        assertEquals(ny+HOUR,level(s,"NY OR L").availableAt());
        assertEquals(3996,level(s,"NY OR L").price(),0);
    }

    @Test public void incompleteOrStillFormingRangeIsOmitted(){
        long start=time("2026-11-24T00:00:00Z");
        List<Market.Candle> five=bars(start,72,FIVE,4002,3999);
        assertNull(level(SessionLevels.calculate(five,List.of(),start+6*HOUR-1),"Asia L"));
        five.remove(23);
        assertNull(level(SessionLevels.calculate(five,List.of(),start+6*HOUR),"Asia L"));
    }

    @Test public void priorDayAndWeekSkipWeekendAndUseCompleteTradingHours(){
        long sunday=time("2026-11-15T23:00:00Z");
        List<Market.Candle> hour=new ArrayList<>();
        for(int day=0;day<5;day++)hour.addAll(bars(sunday+day*24*HOUR,23,HOUR,4005+day,3996-day));
        long monday=time("2026-11-23T14:00:00Z");
        var s=SessionLevels.calculate(List.of(),hour,monday);
        assertEquals(4009,level(s,"PDH").price(),0);
        assertEquals(time("2026-11-20T22:00:00Z"),level(s,"PWH").availableAt());
        assertEquals(3992,level(s,"PWL").price(),0);
        hour.remove(12);
        var incomplete=SessionLevels.calculate(List.of(),hour,monday);
        assertNull(level(incomplete,"PWH"));
        assertNotNull(level(incomplete,"PDH"));
    }

    @Test public void sweepUsesKnownRangeAndClosedCandleAndRejectsGaps(){
        long start=time("2026-11-24T00:00:00Z"),afterAsia=start+6*HOUR;
        List<Market.Candle> five=bars(start,72,FIVE,4002,3999);
        five.add(new Market.Candle(afterAsia,4000,4001,3997,4000));
        assertTrue(SessionLevels.calculate(five,List.of(),afterAsia+FIVE-1).sweeps().isEmpty());
        var events=SessionLevels.calculate(five,List.of(),afterAsia+FIVE).sweeps();
        assertEquals(1,events.size());
        assertEquals(afterAsia,events.get(0).barTime());
        assertEquals("Asia L",events.get(0).level().label());
        five.remove(72);
        five.add(new Market.Candle(afterAsia+FIVE,4000,4001,3997,4000));
        assertTrue(SessionLevels.calculate(five,List.of(),afterAsia+2*FIVE).sweeps().isEmpty());
    }

    @Test public void upperSweepIsDistinctFromACloseOutsideTheRange(){
        long start=time("2026-11-24T00:00:00Z"),afterAsia=start+6*HOUR;
        List<Market.Candle> five=bars(start,72,FIVE,4002,3999);
        five.add(new Market.Candle(afterAsia,4000,4005,4000,4004));
        assertTrue(SessionLevels.calculate(five,List.of(),afterAsia+FIVE).sweeps().isEmpty());
        five.set(72,new Market.Candle(afterAsia,4000,4005,4000,4001));
        var events=SessionLevels.calculate(five,List.of(),afterAsia+FIVE).sweeps();
        assertEquals(1,events.size());
        assertTrue(events.get(0).level().high());
        assertEquals("Asia H",events.get(0).level().label());
    }
}
