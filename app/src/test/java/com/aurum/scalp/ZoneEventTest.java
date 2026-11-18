package com.aurum.scalp;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class ZoneEventTest {
    static final long T=1767225600000L;static final Market.Frame F=Market.Frame.M5;
    private List<Market.Candle> waves(){List<Market.Candle>b=new ArrayList<>();for(int i=0;i<80;i++){double c=4000+6*Math.sin(i*Math.PI/6);b.add(new Market.Candle(T+i*F.duration(),c,c+1,c-1,c));}return b;}
    private void append(List<Market.Candle>b,double c){b.add(new Market.Candle(T+b.size()*F.duration(),c,c+1,c-1,c));}
    private ZoneEngine.Analysis analyse(List<Market.Candle>b){return ZoneEngine.calculate(b,F,T+b.size()*F.duration());}
    @Test public void breakIsEmittedAtSecondCloseOnly(){List<Market.Candle>b=waves();append(b,3990);assertTrue(analyse(b).events.isEmpty());append(b,3989);ZoneEngine.Event e=analyse(b).events.stream().filter(x->x.kind==ZoneEngine.EventKind.BREAK).findFirst().orElseThrow();assertEquals(T+82*F.duration(),e.time);assertTrue(e.zone.support);}
    @Test public void retestEventWaitsForNewPivotConfirmation(){List<Market.Candle>b=waves();for(double c:new double[]{3990,3989,3991,3992,3990})append(b,c);assertTrue(analyse(b).events.stream().noneMatch(e->e.kind==ZoneEngine.EventKind.RETEST_HELD));append(b,3989);ZoneEngine.Event e=analyse(b).events.stream().filter(x->x.kind==ZoneEngine.EventKind.RETEST_HELD).findFirst().orElseThrow();assertFalse(e.zone.support);assertEquals(T+86*F.duration(),e.time);assertEquals(1,e.zone.tests);}
    @Test public void failedRetestIsEmittedAfterTwoReclaimCloses(){List<Market.Candle>b=waves();for(double c:new double[]{3990,3989,4000})append(b,c);assertTrue(analyse(b).events.stream().noneMatch(e->e.kind==ZoneEngine.EventKind.RETEST_FAILED));append(b,4001);assertTrue(analyse(b).events.stream().anyMatch(e->e.kind==ZoneEngine.EventKind.RETEST_FAILED&&e.time==T+84*F.duration()));}
    @Test public void eventsBeforeCutoffDoNotDependOnFutureBars(){List<Market.Candle>b=waves();append(b,3990);append(b,3989);long now=T+b.size()*F.duration();ZoneEngine.Analysis before=ZoneEngine.calculate(b,F,now);append(b,4050);ZoneEngine.Analysis after=ZoneEngine.calculate(b,F,now);assertEquals(before.events.size(),after.events.size());for(int i=0;i<before.events.size();i++)assertEquals(before.events.get(i).key(),after.events.get(i).key());}
    @Test public void seedIdentitySurvivesConfirmedRoleReversal(){List<Market.Candle>b=waves();ZoneEngine.Zone old=analyse(b).zones.stream().filter(z->z.support).findFirst().orElseThrow();for(double c:new double[]{3990,3989,3991,3992,3990,3989})append(b,c);ZoneEngine.Event e=analyse(b).events.stream().filter(x->x.kind==ZoneEngine.EventKind.RETEST_HELD).findFirst().orElseThrow();assertEquals(old.id,e.zone.id);}
}
