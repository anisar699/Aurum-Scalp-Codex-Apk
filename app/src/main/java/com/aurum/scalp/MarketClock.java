package com.aurum.scalp;

/** HTTPS clock estimated from Date granularity and request latency, advanced on elapsed time. */
final class MarketClock {
    static final long FUTURE_TOLERANCE = 3_000;
    private long offset, desiredOffset, anchor, adjustedAt, uncertainty, last;
    private boolean ready;

    synchronized void sync(long date, long mono) {
        if (date > 0) accept(date - mono, mono, 0);
    }

    synchronized void sample(long date, long started, long received) {
        long rtt = received - started;
        if (date <= 0 || rtt < 0 || rtt > 4_000) return;
        long error = 500 + rtt / 2;
        if (!ready || error <= uncertainty || received - anchor > 600_000) {
            accept(date + error - received, received, error);
        }
    }

    private void accept(long next, long mono, long error) {
        if (!ready || mono - anchor >= 86_400_000) {
            offset = next;
            adjustedAt = mono;
        } else {
            advance(mono);
        }
        ready = true;
        desiredOffset = next;
        anchor = mono;
        uncertainty = error;
    }

    synchronized long now(long mono) {
        if (!ready || mono < anchor || mono - anchor >= 86_400_000) return 0;
        advance(mono);
        last = Math.max(last, mono + offset);
        return last;
    }

    private void advance(long mono) {
        // Move at most one tenth of elapsed time so small corrections never reverse time.
        long adjustment = (mono - adjustedAt) / 10;
        if (adjustment <= 0) return;
        offset += Math.max(-adjustment, Math.min(adjustment, desiredOffset - offset));
        adjustedAt = mono;
    }
}
