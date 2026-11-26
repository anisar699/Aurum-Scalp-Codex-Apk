package com.aurum.scalp;

import org.junit.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.aurum.scalp.Market.Frame.*;
import static org.junit.Assert.*;

public class ScalpSetupTest {
    private static final long CLOSE=Instant.parse("2026-11-24T14:20:00Z").toEpochMilli(), FIVE=M5.duration();
    private final Map<Market.Frame,ZoneEngine.Analysis> analyses=new EnumMap<>(Market.Frame.class);
    private final Map<Market.Frame,Long> synced=new EnumMap<>(Market.Frame.class);

    private static ZoneEngine.Zone zone(Market.Frame f,double low,double high,boolean support,long known){
        return new ZoneEngine.Zone(f,low,high,4,support,false,false,2,known,known);
    }

    private static List<Market.Candle> candles(Market.Frame f,long close,double price){
        List<Market.Candle> bars=new ArrayList<>();
        long duration=f.duration();
        for(int i=40;i>0;i--)bars.add(new Market.Candle(close-i*duration,price,price+.5,price-.5,price));
        return bars;
    }

    private void set(Market.Frame f,List<Market.Candle> bars,ZoneEngine.Zone... zones){
        analyses.put(f,new ZoneEngine.Analysis(f,bars,List.of(zones),List.of(),3,0,"Mixed"));
        synced.put(f,CLOSE+1000);
    }

    private void setup(boolean support,long known){
        set(H1,candles(H1,CLOSE/H1.duration()*H1.duration(),4000),zone(H1,3998,4001,support,known));
        set(H4,candles(H4,CLOSE/H4.duration()*H4.duration(),4000));
        set(M15,candles(M15,CLOSE/M15.duration()*M15.duration(),4000),zone(M15,3999,4001,support,known));
    }

    private ScalpSetup.Readout read(long now,double price){
        return ScalpSetup.evaluate(analyses,synced,new Market.Quote(now,price-.1,price+.1),true,now);
    }

    @Test public void watchingReclaimPressureAndConfirmedInvalidationAreDistinct(){
        setup(true,CLOSE-H1.duration());
        List<Market.Candle> bars=candles(M5,CLOSE,4003);
        set(M5,bars);
        assertEquals(ScalpSetup.Stage.WATCHING,read(CLOSE+1000,4000).stage());
        bars.set(39,new Market.Candle(CLOSE-FIVE,4003,4004,3999,4002));
        set(M5,bars);
        var reclaim=read(CLOSE+1000,4002);
        assertEquals(ScalpSetup.Stage.RECLAIMED,reclaim.stage());
        assertEquals(CLOSE,reclaim.eventTime());
        assertEquals(M15,reclaim.context().frame);
        assertEquals(3997.95,ScalpSetup.invalidation(reclaim.anchor()),.0001);
        assertEquals(ScalpSetup.Stage.PRESSURED,read(CLOSE+1000,3997).stage());
        bars.add(new Market.Candle(CLOSE,4002,4003,3996,3997));
        set(M5,bars);
        assertEquals(ScalpSetup.Stage.INVALIDATED,read(CLOSE+FIVE+1000,3997).stage());
    }

    @Test public void resistanceReclaimRequiresACloseBelowTheBand(){
        setup(false,CLOSE-H1.duration());
        List<Market.Candle> bars=candles(M5,CLOSE,3996);
        bars.set(39,new Market.Candle(CLOSE-FIVE,3996,4000,3995,3999));
        set(M5,bars);
        assertEquals(ScalpSetup.Stage.WATCHING,read(CLOSE+1000,3999).stage());
        bars.set(39,new Market.Candle(CLOSE-FIVE,3996,4000,3995,3997));
        set(M5,bars);
        assertEquals(ScalpSetup.Stage.RECLAIMED,read(CLOSE+1000,3997).stage());
    }

    @Test public void noReclaimBeforeAnchorWasKnownOrAcrossMissingCandle(){
        setup(true,CLOSE-FIVE+1000);
        List<Market.Candle> bars=candles(M5,CLOSE,4003);
        bars.set(39,new Market.Candle(CLOSE-FIVE,4003,4004,3999,4002));
        set(M5,bars);
        assertEquals(ScalpSetup.Stage.WATCHING,read(CLOSE+1000,4002).stage());
        setup(true,CLOSE-H1.duration());
        bars.add(0,new Market.Candle(CLOSE-41*FIVE,4003,4004,4002,4003));
        bars.remove(39);
        set(M5,bars);
        assertEquals(ScalpSetup.Stage.WATCHING,read(CLOSE+1000,4002).stage());
    }

    @Test public void savedHistoryAndAnUnconfirmedNewBarCannotAdvanceSetup(){
        setup(true,CLOSE-H1.duration());
        set(M5,candles(M5,CLOSE,4003));
        assertEquals(ScalpSetup.Stage.WAITING,read(CLOSE+FIVE+6_000,4000).stage());
        synced.remove(H4);
        assertEquals(ScalpSetup.Stage.WAITING,read(CLOSE+1000,4000).stage());
    }
}
