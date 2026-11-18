package com.aurum.scalp;

import java.util.*;

final class ZoneSelection {
    enum Unit { ALL, USD, ATR }
    record Distance(Unit unit, double limit) {
        static final Distance ALL = new Distance(Unit.ALL, 0);
        boolean includes(ZoneEngine.Zone zone, double price, double atr) {
            return unit == Unit.ALL || (unit != Unit.ATR || atr > 0) && zone.distance(price) <= limit * (unit == Unit.ATR ? atr : 1);
        }
        String label() {
            if (unit == Unit.ALL) return "All distances";
            return unit == Unit.USD ? "Within $" + money(limit) : "Within " + money(limit) + " ATR";
        }
    }
    enum Trigger { APPROACH, BREAK, RETEST }
    record Pin(Market.Frame frame, String id, double low, double high, boolean support, Trigger trigger) {
        boolean matches(ZoneEngine.Zone zone) { return zone.frame == frame && id.equals(zone.id); }
    }
    private static final Comparator<ZoneEngine.Zone> MOST_REACTIONS =
        Comparator.comparingInt((ZoneEngine.Zone zone) -> zone.tests).reversed();

    private ZoneSelection() {}
    static List<ZoneEngine.Zone> confirmed(ZoneEngine.Analysis a, double price, boolean support, int count, Distance limit) {
        if (a == null || !Market.finite(price) || price <= 0) return Collections.emptyList();
        List<ZoneEngine.Zone> matches = new ArrayList<>();
        for (ZoneEngine.Zone z : a.zones) {
            if (!z.reference && z.support == support && z.validSide(price) && limit.includes(z, price, a.atr)) matches.add(z);
        }
        matches.sort(Comparator.comparingDouble((ZoneEngine.Zone z) -> z.distance(price))
            .thenComparing(MOST_REACTIONS).thenComparingDouble(z -> Math.abs(z.center - price)));
        List<ZoneEngine.Zone> distinct = new ArrayList<>();
        for (ZoneEngine.Zone z : matches) {
            if (distinct.stream().noneMatch(previous -> ZoneEngine.overlap(previous, z) > .5)) distinct.add(z);
            if (distinct.size() == count) break;
        }
        return distinct;
    }
    static List<ZoneEngine.Zone> watches(ZoneEngine.Analysis a, double price, boolean support, int count, Distance limit) {
        if (a == null || !Market.finite(price) || price <= 0) return Collections.emptyList();
        List<ZoneEngine.Zone> result = new ArrayList<>(a.watch(price, support, count));
        if (result.isEmpty()) {
            List<ZoneEngine.Zone> references = a.nearest(price, support, 1, true);
            if (!references.isEmpty() && references.get(0).reference) result.add(references.get(0));
        }
        result.removeIf(z -> !limit.includes(z, price, a.atr));
        return result;
    }
    static String money(double value) { return String.format(Locale.US, "%.2f", value); }
    static String band(ZoneEngine.Zone z) { return money(z.low) + "–" + money(z.high); }
}
