package com.aurum.scalp;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Candle timing is independent of the quote's live or saved badge. */
final class DataFreshness {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.US).withZone(ZoneOffset.UTC);
    private DataFreshness() {}

    static long lastClose(ZoneEngine.Analysis a) {
        return a == null || a.candles.isEmpty() ? 0 : Market.closeTime(a.candles.get(a.candles.size() - 1), a.frame.duration());
    }
    static boolean verified(long now, long lastFetch, Market.Frame frame) {
        return now > 0 && lastFetch > 0 && lastFetch <= now + MarketClock.FUTURE_TOLERANCE && now - lastFetch <= Math.max(180_000, frame.duration() + HistorySchedule.eventWindow(frame));
    }
    static String state(ZoneEngine.Analysis a, long now, long verifiedFetch, Market.Quote q) {
        long close = lastClose(a);
        if (close == 0) return "Waiting for candles";
        if (now <= 0) return "Saved · time unverified";
        if (close > now) return "Awaiting verified close";
        if (q != null && q.marketClosed) return "Last market session";
        if (!a.historyFresh(now)) return "Stale candles";
        return verified(now, verifiedFetch, a.frame) ? "Current candles" : "Saved candles";
    }
    static String summary(ZoneEngine.Analysis a, long now, long verifiedFetch, Market.Quote q) {
        long close = lastClose(a);
        return state(a, now, verifiedFetch, q) + (close > 0 ? " · close " + date(close) + " UTC" : "");
    }
    static String compact(ZoneEngine.Analysis a, long now, long verifiedFetch, Market.Quote q) {
        long close = lastClose(a);
        if (close == 0) return "";
        String state = state(a, now, verifiedFetch, q);
        if ("Current candles".equals(state)) return "";
        return state.startsWith("Stale") ? "stale" : state.startsWith("Awaiting") ? "pending" : "saved";
    }
    static String date(long utcMs) { return utcMs > 0 ? DATE.format(Instant.ofEpochMilli(utcMs)) : "—"; }
}
