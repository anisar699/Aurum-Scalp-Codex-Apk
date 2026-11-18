package com.aurum.scalp;

import java.math.BigInteger;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class RetryGate {
    private RetryGate() {}

    /** Delta-seconds and HTTP dates establish the minimum wait. */
    static long retryAfter(String header, long now) {
        return retryAfter(header, now, 60_000);
    }

    static long retryAfter(String header, long now, long fallback) {
        if (header == null) return fallback;
        String value = header.trim();
        try {
            if (value.matches("[0-9]+")) {
                return new BigInteger(value).multiply(BigInteger.valueOf(1_000))
                    .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            }
            return Math.max(0, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - now);
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }
}
