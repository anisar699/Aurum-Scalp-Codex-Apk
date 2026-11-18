package com.aurum.scalp;

import java.util.*;

/** REST budget, connection backoff and endpoint reservations use monotonic deadlines. */
final class PollPolicy {
    static final String REST = "rest";
    static final String STREAM = "stream";
    private static final long MANUAL_MINIMUM = 10_000;
    private final Map<String, Long> due = new HashMap<>();
    private final Map<String, Long> manualDue = new HashMap<>();
    private final Map<String, Object> owners = new HashMap<>();
    private final Map<String, Integer> refused = new HashMap<>();
    private final Map<String, Hold> holds = new HashMap<>();

    record Hold(long until, int code) {}

    synchronized boolean claim(String key, long now, boolean manual) {
        return claim(key, now, manual, this);
    }

    synchronized boolean claim(String key, long now, boolean manual, Object owner) {
        long earliest = (manual ? manualDue : due).getOrDefault(key, 0L);
        if (now < earliest || owners.containsKey(key) || holdFor(key, now) != null) return false;
        owners.put(key, owner);
        manualDue.put(key, deadline(now, MANUAL_MINIMUM));
        return true;
    }

    synchronized void complete(String key, long now, long delay, boolean limited) {
        if (limited) limit(key.equals(STREAM) ? STREAM : REST, now, delay, 429);
        due.put(key, deadline(now, delay));
        release(key);
    }

    synchronized void success(String key) { refused.remove(key); }

    synchronized long refusal(String key, long now, int code, long retryAfter) {
        int count = refused.merge(key, 1, (old, one) -> Math.min(6, old + one));
        long delay = Math.max(retryAfter, Math.min(3_600_000, 120_000L << (count - 1)));
        limit(key, now, delay, code);
        return delay;
    }

    synchronized void limit(String bucket, long now, long delay, int code) {
        Hold current = holds.get(bucket);
        long next = deadline(now, delay);
        if (current == null || next >= current.until) holds.put(bucket, new Hold(next, code));
    }

    synchronized Hold holdFor(String key, long now) {
        Hold specific = active(key, now);
        Hold shared = key.equals(STREAM) ? null : active(REST, now);
        if (specific == null) return shared;
        return shared == null || specific.until >= shared.until ? specific : shared;
    }

    synchronized Map<String, Hold> activeHolds(long now) {
        holds.entrySet().removeIf(entry -> entry.getValue().until <= now);
        return new TreeMap<>(holds);
    }

    private Hold active(String key, long now) {
        Hold hold = holds.get(key);
        return hold != null && hold.until > now ? hold : null;
    }

    synchronized long remaining(long now) {
        Hold shared = active(REST, now);
        return shared == null ? 0 : shared.until - now;
    }

    synchronized void marketOpened() {
        due.remove("quote");
        for (Market.Frame frame : Market.Frame.values()) due.remove(frame.name());
    }

    synchronized void releaseOwner(Object owner) {
        owners.values().removeIf(current -> current == owner);
    }

    synchronized void release(String key) { owners.remove(key); }

    static long deadline(long now, long duration) {
        return now + Math.min(Long.MAX_VALUE - now, Math.max(0, duration));
    }
}
