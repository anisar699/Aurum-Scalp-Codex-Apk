package com.aurum.scalp;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class FlipHistoryTest {
 @Test public void returningToOriginalRoleStillReportsRoleReversalHistory(){Market.Frame f=Market.Frame.M5;long t=1767225600000L;List<Market.Candle>b=new ArrayList<>();for(int i=0;i<80;i++){double c=4000+6*Math.sin(i*Math.PI/6);b.add(new Market.Candle(t+i*f.duration(),c,c+1,c-1,c));}for(double c:new double[]{3990,3989,3991,3992,3990,3989,3996,3997,3995,3994,3996,3997})b.add(new Market.Candle(t+b.size()*f.duration(),c,c+1,c-1,c));ZoneEngine.Analysis a=ZoneEngine.calculate(b,f,t+b.size()*f.duration());ZoneEngine.Zone z=a.zones.stream().filter(q->q.support&&Math.abs(q.center-3993)<.5).findFirst().orElseThrow();assertTrue(z.flipped);assertEquals(1,z.tests);}
}
