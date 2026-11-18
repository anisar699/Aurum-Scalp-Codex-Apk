package com.aurum.scalp;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class MarketRegimeTest {
    private static final long T=1767225600000L;
    private List<Market.Candle> bars(int count,int direction){List<Market.Candle>b=new ArrayList<>();for(int i=0;i<count;i++){double c=4000+direction*i;b.add(new Market.Candle(T+i*60000,c,c+1,c-1,c));}return b;}
    private MarketRegime.Readout read(List<Market.Candle>b){return MarketRegime.calculate(ZoneEngine.calculate(b,Market.Frame.M1,T+b.size()*60000L));}
    @Test public void noReadoutWithoutWarmup(){assertFalse(read(bars(79,1)).available);assertFalse(MarketRegime.calculate(null).available);}
    @Test public void cleanRisingSeriesIsUptrend(){MarketRegime.Readout r=read(bars(200,1));assertEquals("Uptrend",r.trend);assertEquals(100,r.adx,.001);assertEquals(1,r.efficiency,.001);}
    @Test public void cleanFallingSeriesIsDowntrend(){MarketRegime.Readout r=read(bars(200,-1));assertEquals("Downtrend",r.trend);assertEquals(100,r.adx,.001);}
    @Test public void alternatingFlatEnvelopeIsRange(){List<Market.Candle>b=new ArrayList<>();for(int i=0;i<160;i++){double c=4000+(i%2==0?1:-1);b.add(new Market.Candle(T+i*60000,4000,4002,3998,c));}MarketRegime.Readout r=read(b);assertEquals("Range",r.trend);assertEquals(0,r.efficiency,0);assertEquals(3998,r.rangeLow,0);assertEquals(4002,r.rangeHigh,0);assertEquals(25,r.position,0);}
    @Test public void highVolatilityIsRelativeToPriorMedian(){List<Market.Candle>b=bars(160,0);for(int i=150;i<160;i++)b.set(i,new Market.Candle(T+i*60000,4000,4020,3980,4000));MarketRegime.Readout r=read(b);assertEquals("High",r.volatility);assertTrue(r.atrRatio>1.5);}
    @Test public void lowVolatilityCanFollowAQuieterSession(){List<Market.Candle>b=new ArrayList<>();for(int i=0;i<160;i++){double h=i<120?20:.1;b.add(new Market.Candle(T+i*60000,4000,4000+h,4000-h,4000));}assertEquals("Low",read(b).volatility);}
    @Test public void unfinishedSpikeCannotAffectReadout(){List<Market.Candle>b=bars(160,1);long cutoff=T+160*60000L;MarketRegime.Readout before=MarketRegime.calculate(ZoneEngine.calculate(b,Market.Frame.M1,cutoff));b.add(new Market.Candle(cutoff,4000,6000,3000,5000));MarketRegime.Readout after=MarketRegime.calculate(ZoneEngine.calculate(b,Market.Frame.M1,cutoff));assertEquals(before.atr,after.atr,0);assertEquals(before.trend,after.trend);assertEquals(before.adx,after.adx,0);}
    @Test public void zeroTrueRangeIsUnavailableRatherThanNan(){List<Market.Candle>b=new ArrayList<>();for(int i=0;i<90;i++)b.add(new Market.Candle(T+i*60000L,4000,4000,4000,4000));assertFalse(read(b).available);}
}
