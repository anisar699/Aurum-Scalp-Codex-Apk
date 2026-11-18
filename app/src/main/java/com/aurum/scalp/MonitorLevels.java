package com.aurum.scalp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Notification content derived from the same quote and zone model used in the app. */
final class MonitorLevels {
    private static final Market.Frame[] FRAMES = {
        Market.Frame.M5, Market.Frame.M15, Market.Frame.H1, Market.Frame.H4
    };
    private static final int COLLAPSED_FRAME = 2;

    record Status(String title, List<String> rows, String legend) {
        String preview() { return rows.get(COLLAPSED_FRAME); }
    }

    static Status from(Market.Quote quote, boolean online, long now,
                       Map<Market.Frame, ZoneEngine.Analysis> analyses, Map<Market.Frame, Long> synced) {
        double price = Market.referencePrice(quote, analyses.values());
        String state = Market.priceState(quote, online, now, analyses.values()).replace("● ", "");
        String title = "Aurum · " + state + (price > 0 ? " · " + MainActivity.money(price) + (state.equals("CANDLE REFERENCE") ? " MID close" : " MID") : "");
        List<String> rows = new ArrayList<>(FRAMES.length);
        boolean unconfirmed = false;
        boolean savedCandles = false, staleCandles = false;
        for (Market.Frame frame : FRAMES) {
            ZoneEngine.Analysis analysis = analyses.get(frame);
            if (analysis == null || analysis.candles.isEmpty()) {
                rows.add(frame.label + " · Waiting for candles");
                continue;
            }
            ZoneEngine.Zone support = nearest(analysis, price, true);
            ZoneEngine.Zone resistance = nearest(analysis, price, false);
            String freshness = DataFreshness.compact(analysis, now, synced.getOrDefault(frame, 0L), quote);
            boolean stale = "stale".equals(freshness);
            String marker = freshness.isEmpty() ? "" : stale ? "†" : "*";
            rows.add(frame.label + marker + " · " + band("S", support) + "  " + band("R", resistance));
            unconfirmed |= isReference(support) || isReference(resistance);
            savedCandles |= !freshness.isEmpty() && !stale;
            staleCandles |= stale;
        }
        List<String> labels = new ArrayList<>();
        if (unconfirmed) labels.add("? Watch");
        if (savedCandles) labels.add("* Saved candles");
        if (staleCandles) labels.add("† Stale candles");
        String legend = String.join(" · ", labels);
        return new Status(title, Collections.unmodifiableList(rows), legend);
    }

    private static ZoneEngine.Zone nearest(ZoneEngine.Analysis analysis, double price, boolean support) {
        List<ZoneEngine.Zone> zones = analysis.nearest(price, support, 1, false);
        if (zones.isEmpty()) zones = ZoneSelection.watches(analysis, price, support, 1, ZoneSelection.Distance.ALL);
        return zones.isEmpty() ? null : zones.get(0);
    }

    private static boolean isReference(ZoneEngine.Zone zone) {
        return zone != null && zone.reference;
    }

    private static String band(String side, ZoneEngine.Zone zone) {
        if (zone == null) return side + " —";
        String bounds = String.format(Locale.US, "%.2f–%.2f", zone.low, zone.high);
        return side + (zone.reference ? "? " : " ") + bounds;
    }
}
