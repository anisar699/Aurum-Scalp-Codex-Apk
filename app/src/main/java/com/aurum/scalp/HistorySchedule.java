package com.aurum.scalp;

/** UTC bucket scheduling for the provider's explicitly closed candles. */
final class HistorySchedule {
    static final int MIN_BARS = 40;
    static final int TAIL_BARS = 5;
    static final long GRACE = 5_000;
    static final long RESYNC = 60 * Market.MINUTE;
    private static final long[] RETRIES = {5_000, 10_000, 20_000, 60_000};
    private long lastFull;
    private long pendingClose;
    private int retries;

    int requestLimit(Market.Frame frame, int count, long lastClose, long now, int fullLimit) {
        // The tail can fill up to five bars. Larger gaps need a full window.
        boolean needsBackfill = now > 0 && lastClose > 0 && now - lastClose > 5 * frame.duration() && lastFull < lastClose + 5 * frame.duration();
        return count < MIN_BARS || lastFull == 0 || now - lastFull >= RESYNC || needsBackfill
            ? fullLimit : TAIL_BARS;
    }

    long afterResponse(Market.Frame frame, long now, long close, boolean marketClosed, boolean full) {
        if (full) lastFull = now;
        if (marketClosed) {
            pendingClose = 0;
            return RESYNC;
        }
        long duration = frame.duration();
        long expected = Math.floorDiv(now - GRACE, duration) * duration;
        if (close < expected) {
            if (pendingClose != expected) {
                pendingClose = expected;
                retries = 0;
            }
            if (retries < RETRIES.length) return RETRIES[retries++];
        } else {
            pendingClose = 0;
        }
        long nextClose = expected + duration + GRACE;
        return Math.max(1_000, Math.min(nextClose - now, lastFull + RESYNC - now));
    }

    static long eventWindow(Market.Frame frame) { return frame.scalp() ? 90_000 : 300_000; }
}
