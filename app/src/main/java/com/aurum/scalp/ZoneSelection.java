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
    static ZoneEngine.Zone resolve(Pin pin,ZoneEngine.Analysis analysis){
        if(pin==null||analysis==null||pin.frame()!=analysis.frame)return null;
        List<ZoneEngine.Zone> candidates=new ArrayList<>(analysis.zones);
        candidates.addAll(analysis.watchZones);
        ZoneEngine.Zone best=null;
        double bestOverlap=-1,bestDistance=Double.POSITIVE_INFINITY;
        for(ZoneEngine.Zone zone:candidates){
            if(pin.matches(zone))return zone;
            if(zone.support!=pin.support()&&!zone.flipped&&!zone.retestWatch)continue;
            double overlap=Math.max(0,Math.min(pin.high(),zone.high)-Math.max(pin.low(),zone.low))
                /Math.max(.00001,Math.min(pin.high()-pin.low(),zone.width()));
            double distance=Math.abs((pin.low()+pin.high())/2-zone.center);
            if(overlap<.5&&distance>analysis.atr*.25)continue;
            if(overlap>bestOverlap||overlap==bestOverlap&&distance<bestDistance){
                best=zone;bestOverlap=overlap;bestDistance=distance;
            }
        }
        return best;
    }

    private static final Comparator<ZoneEngine.Zone> MOST_REACTIONS =
        Comparator.comparingInt((ZoneEngine.Zone zone) -> zone.tests).reversed();

    private ZoneSelection() {}
    static List<ZoneEngine.Zone> confirmed(ZoneEngine.Analysis a, double price, boolean support, int count, Distance limit) {
        if (a == null || !Market.finite(price) || price <= 0) return Collections.emptyList();
        List<ZoneEngine.Zone> matches = new ArrayList<>();
        for (ZoneEngine.Zone z : a.zones) {
            if (!z.reference && !z.breakPending && z.support == support && z.validSide(price) && limit.includes(z, price, a.atr)) matches.add(z);
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
        List<ZoneEngine.Zone> result = lifecycleWatches(a, price, support, count, limit);
        if (a == null || !Market.finite(price) || price <= 0) return result;
        if (result.isEmpty()) {
            List<ZoneEngine.Zone> references = a.nearest(price, support, 1, true);
            if (!references.isEmpty() && references.get(0).reference && limit.includes(references.get(0), price, a.atr))
                result.add(references.get(0));
        }
        return result;
    }
    static List<ZoneEngine.Zone> lifecycleWatches(ZoneEngine.Analysis a, double price, boolean support, int count, Distance limit) {
        List<ZoneEngine.Zone> watches = new ArrayList<>();
        if (a == null || !Market.finite(price) || price <= 0) return watches;
        for (ZoneEngine.Zone watch : a.watch(price, support, count))
            if (limit.includes(watch, price, a.atr)) watches.add(watch);
        return watches;
    }
    static ZoneEngine.Zone nearestLifecycleWatch(ZoneEngine.Analysis a, double price, Distance limit) {
        List<ZoneEngine.Zone> watches = lifecycleWatches(a, price, true, 1, limit);
        watches.addAll(lifecycleWatches(a, price, false, 1, limit));
        return watches.stream().min(Comparator.comparingDouble(z -> z.distance(price))).orElse(null);
    }
    static String money(double value) { return String.format(Locale.US, "%.2f", value); }
    static String band(ZoneEngine.Zone z) { return PriceFormat.low(z.low) + "–" + PriceFormat.high(z.high); }
}
