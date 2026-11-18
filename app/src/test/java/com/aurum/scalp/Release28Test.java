package com.aurum.scalp;

import static org.junit.Assert.*;
import java.time.Instant;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class Release28Test {
    private static final long T = 1767225600000L;
    private static final Market.Frame F = Market.Frame.M5;
    private static final long CLOSE = T + F.duration();
    private static final double MID = 4010;
    private final SmartAlerts alerts = new SmartAlerts();

    private ZoneEngine.Zone zone(Market.Frame frame, double low, double high, boolean support, boolean watch, long pivot) {
        return new ZoneEngine.Zone(frame, low, high, 4, support, watch, false, watch ? 0 : 2, pivot, T, watch);
    }
    private ZoneEngine.Analysis snapshot(Market.Frame frame, long close, List<ZoneEngine.Zone> zones,
                                         List<ZoneEngine.Zone> watches, ZoneEngine.Event... events) {
        Market.Candle candle = new Market.Candle(close - frame.duration(), MID, MID + 2, MID - 2, MID);
        return new ZoneEngine.Analysis(frame, List.of(candle), zones, watches, 10, 0, "Mixed", List.of(events));
    }
    private List<SmartAlerts.Alert> evaluate(long quoteTime, long now, boolean online, ZoneEngine.Analysis a) {
        return alerts.evaluate(new Market.Quote(quoteTime, MID - .1, MID + .1), online, now, Map.of(a.frame, a),
            Map.of(a.frame, now), EnumSet.of(a.frame));
    }

    @Test public void delayedQuoteRetainsTheNewBreakExactlyOnce() {
        ZoneEngine.Zone zone = zone(F, 3999, 4001, true, false, T);
        ZoneEngine.Analysis before = snapshot(F, T, List.of(zone), List.of());
        ZoneEngine.Event event = new ZoneEngine.Event(ZoneEngine.EventKind.BREAK, zone, CLOSE);
        ZoneEngine.Analysis after = snapshot(F, CLOSE, List.of(), List.of(), event, event);
        assertTrue(evaluate(CLOSE - 1000, CLOSE - 1000, true, before).isEmpty());
        assertTrue(evaluate(CLOSE - 1000, CLOSE + 500, true, after).isEmpty());
        assertEquals(event.key(), evaluate(CLOSE + 1000, CLOSE + 1100, true, after).get(0).key);
        assertTrue(evaluate(CLOSE + 2000, CLOSE + 2100, true, after).isEmpty());
    }

    @Test public void briefReconnectRecoversRecentPendingRetestOnlyOnce() {
        ZoneEngine.Zone zone = zone(F, 3999, 4001, true, false, T);
        ZoneEngine.Analysis before = snapshot(F, T, List.of(zone), List.of());
        ZoneEngine.Analysis after = snapshot(F, CLOSE, List.of(zone), List.of(),
            new ZoneEngine.Event(ZoneEngine.EventKind.RETEST_HELD, zone, CLOSE));
        evaluate(CLOSE - 1000, CLOSE - 1000, true, before);
        evaluate(CLOSE - 1000, CLOSE + 500, true, after);
        assertTrue(evaluate(CLOSE - 1000, CLOSE + 600, false, after).isEmpty());
        assertEquals(1,evaluate(CLOSE + 1000, CLOSE + 1100, true, after).size());
        assertTrue(evaluate(CLOSE + 2000, CLOSE + 2100, true, after).isEmpty());
    }

    @Test public void lateHistoryDoesNotReplayAnOldEventWithContinuousTicks() {
        ZoneEngine.Zone zone = zone(F, 3999, 4001, true, false, T);
        ZoneEngine.Analysis before = snapshot(F, T, List.of(zone), List.of());
        ZoneEngine.Analysis after = snapshot(F, CLOSE, List.of(zone), List.of(),
            new ZoneEngine.Event(ZoneEngine.EventKind.BREAK, zone, CLOSE));
        long late=CLOSE+HistorySchedule.eventWindow(F)+1;
        for(long t = CLOSE - 1000; t < late; t += 20_000) evaluate(t,t,true,before);
        assertTrue(evaluate(late,late,true,after).isEmpty());
    }

    @Test public void awaitingFiveMinuteQuoteDoesNotResetAnotherTimeframe() {
        Market.Frame other = Market.Frame.H1;
        ZoneEngine.Zone fiveZone = zone(F, 3999, 4001, true, false, T);
        ZoneEngine.Zone hourZone = zone(other, 3994, 3996, true, false, T);
        ZoneEngine.Analysis five = snapshot(F, T, List.of(fiveZone), List.of());
        ZoneEngine.Analysis hour = snapshot(other, T, List.of(hourZone), List.of());
        ZoneEngine.Event event = new ZoneEngine.Event(ZoneEngine.EventKind.BREAK, fiveZone, CLOSE);
        Set<Market.Frame> frames = EnumSet.of(F, other);
        alerts.evaluate(new Market.Quote(CLOSE-1000, MID-.1, MID+.1), true, CLOSE-1000,
            Map.of(F, five, other, hour), Map.of(F, CLOSE-1000, other, CLOSE-1000), frames);
        ZoneEngine.Analysis next = snapshot(F, CLOSE, List.of(), List.of(), event);
        assertTrue(alerts.evaluate(new Market.Quote(CLOSE-1000, 3996.4, 3996.6), true, CLOSE,
            Map.of(F, next, other, hour), Map.of(F, CLOSE, other, CLOSE), frames).stream().anyMatch(a -> a.frame == other));
        List<SmartAlerts.Alert> sent = alerts.evaluate(new Market.Quote(CLOSE+1000, MID-.1, MID+.1), true, CLOSE+1000,
            Map.of(F, next, other, hour), Map.of(F, CLOSE, other, CLOSE), frames);
        assertEquals(List.of(event.key()), sent.stream().map(a -> a.key).toList());
    }

    @Test public void pinnedRetestIgnoresBreakAndNearbyDifferentIdentity() {
        ZoneEngine.Zone pinned = zone(F, 3999, 4001, true, false, T);
        ZoneEngine.Zone nearby = zone(F, 3999.1, 4001.1, true, false, T - F.duration());
        ZoneSelection.Pin pin = new ZoneSelection.Pin(F, pinned.id, pinned.low, pinned.high, true, ZoneSelection.Trigger.RETEST);
        ZoneEngine.Analysis before = snapshot(F, T, List.of(pinned, nearby), List.of());
        ZoneEngine.Event retest = new ZoneEngine.Event(ZoneEngine.EventKind.RETEST_HELD, pinned, CLOSE);
        ZoneEngine.Analysis after = snapshot(F, CLOSE, List.of(pinned, nearby), List.of(), retest,
            new ZoneEngine.Event(ZoneEngine.EventKind.BREAK, pinned, CLOSE),
            new ZoneEngine.Event(ZoneEngine.EventKind.RETEST_HELD, nearby, CLOSE));
        alerts.evaluate(new Market.Quote(CLOSE - 1000, MID - .1, MID + .1), true, CLOSE - 1000,
            Map.of(F, before), Map.of(F, CLOSE - 1000), Set.of(), Map.of(F, pin));
        List<SmartAlerts.Alert> sent = alerts.evaluate(new Market.Quote(CLOSE + 1000, MID - .1, MID + .1), true, CLOSE + 1000,
            Map.of(F, after), Map.of(F, CLOSE + 1000), Set.of(), Map.of(F, pin));
        assertEquals(1, sent.size());
        assertEquals(retest.key(), sent.get(0).key);
        assertTrue(sent.get(0).title.contains("Pinned"));
    }

    @Test public void confirmedAndWatchUseTheSameEdgeFilterButRemainSeparate() {
        ZoneEngine.Zone watch = zone(F, 4008, 4009.5, true, true, T);
        ZoneEngine.Zone near = zone(F, 4005.5, 4008, true, false, T);
        ZoneEngine.Zone far = zone(F, 4000, 4002, true, false, T);
        ZoneEngine.Analysis a = snapshot(F, T, List.of(near, far), List.of(watch));
        ZoneSelection.Distance usd = new ZoneSelection.Distance(ZoneSelection.Unit.USD, 2);
        assertEquals(List.of(near), ZoneSelection.confirmed(a, MID, true, 2, usd));
        assertEquals(List.of(watch), ZoneSelection.watches(a, MID, true, 2, usd));
        assertEquals(List.of(near, far), ZoneSelection.confirmed(a, MID, true, 2, new ZoneSelection.Distance(ZoneSelection.Unit.ATR, .8)));
    }

    @Test public void savedCandlesKeepTheirOwnStatusWithALiveQuote() {
        ZoneEngine.Analysis a = snapshot(F, T, List.of(), List.of());
        Market.Quote q = new Market.Quote(T+1000, MID-.1, MID+.1);
        assertEquals("saved", DataFreshness.compact(a, T+2000, 0, q));
        assertEquals("saved", DataFreshness.compact(a, 0, T+2000, q));
        assertEquals("", DataFreshness.compact(a, T+2000, T+1000, q));
        assertEquals("stale", DataFreshness.compact(a, T+3*F.duration(), T+3*F.duration(), q));
    }

    @Test public void missingStatusDoesNotDeletePreviouslyVerifiedHistory() throws Exception {
        Market.Candle known = new Market.Candle(T, 4000, 4002, 3999, 4001);
        JSONObject row = new JSONObject().put("openTime", Instant.ofEpochMilli(T).toString());
        JSONObject payload = new JSONObject().put("symbol", "XAUUSD").put("interval", "5m")
            .put("bars", new JSONArray().put(row));
        assertEquals(List.of(known), BiquoteCodec.merge(List.of(known), List.of(), payload.toString(), F, 100));
        row.put("isOpen", true);
        assertTrue(BiquoteCodec.merge(List.of(known), List.of(), payload.toString(), F, 100).isEmpty());
    }
}
