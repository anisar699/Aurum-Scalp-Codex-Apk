package com.aurum.scalp;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Compare candidate rankings on identical closed-candle prefixes and future observation windows. */
public final class CompareStrength {
    private static final int WARMUP = 80;
    private static final int HORIZON = 6;
    private static final double MAX_EDGE_DISTANCE = 3;
    private static final Comparator<ZoneEngine.Zone> REACTIONS =
        Comparator.comparingInt((ZoneEngine.Zone zone) -> zone.tests).reversed();

    private static final class Results {
        int eligible, touches, reactions, invalidations, unresolved, changed;
        double distance;
        void print(String label) {
            System.out.printf(Locale.US,
                "%s eligible=%d touches=%d reactions=%d invalidations=%d unresolved=%d changed=%d meanEdgeATR=%.3f%n",
                label, eligible, touches, reactions, invalidations, unresolved, changed, distance / Math.max(1, eligible));
        }
    }

    public static void main(String[] args) throws Exception {
        Market.Frame frame = Market.Frame.valueOf(args[0]);
        long split = Long.parseLong(args[1]), until = Long.parseLong(args[2]);
        int stride = Integer.parseInt(args[3]), history = Integer.parseInt(args[4]);
        List<Market.Candle> bars = read(Path.of(args[5]), frame);
        double[] weights = Arrays.stream(args[6].split(",")).mapToDouble(Double::parseDouble).toArray();
        Results[] summary = Arrays.stream(weights).mapToObj(ignored -> new Results()).toArray(Results[]::new);
        int evaluated = 0, gaps = 0;
        for (int n = WARMUP; n + HORIZON <= bars.size(); n += stride) {
            long origin = bars.get(n - 1).endTime;
            if (origin < split || bars.get(n + HORIZON - 1).endTime >= until) continue;
            if (bars.get(n + HORIZON - 1).time - bars.get(n - 1).time != HORIZON * frame.duration()) {
                gaps++;
                continue;
            }
            var analysis = ZoneEngine.calculate(bars.subList(Math.max(0, n - history), n), frame, origin);
            if (analysis.atr <= 0) continue;
            evaluated++;
            for (boolean support : new boolean[]{true, false}) {
                double price = bars.get(n - 1).close;
                List<ZoneEngine.Zone> candidates = analysis.zones.stream()
                    .filter(z -> z.support == support && z.validSide(price) && z.distance(price) <= MAX_EDGE_DISTANCE * analysis.atr)
                    .toList();
                ZoneEngine.Zone nearest = pick(candidates, price, analysis.atr, 0);
                if (nearest == null) continue;
                for (int i = 0; i < weights.length; i++) {
                    ZoneEngine.Zone selected = weights[i] == 0 ? nearest : pick(candidates, price, analysis.atr, weights[i]);
                    if (selected != nearest) summary[i].changed++;
                    summary[i].eligible++;
                    summary[i].distance += selected.distance(price) / analysis.atr;
                    score(summary[i], selected, bars.subList(n, n + HORIZON), analysis.atr);
                }
            }
        }
        System.out.printf(Locale.US, "%s candles=%d evaluated=%d skippedGaps=%d historyCap=%d stride=%d%n",
            frame.label, bars.size(), evaluated, gaps, history, stride);
        for (int i = 0; i < weights.length; i++) summary[i].print("weight=" + weights[i]);
    }

    private static ZoneEngine.Zone pick(List<ZoneEngine.Zone> zones,double price,double atr,double weight) {
        return zones.stream().min(Comparator.comparingDouble((ZoneEngine.Zone zone) -> zone.distance(price)/atr-weight*strength(zone))
            .thenComparing(REACTIONS).thenComparingDouble(zone -> Math.abs(zone.center-price))).orElse(null);
    }

    private static double strength(ZoneEngine.Zone zone) {
        int evidence = Math.max(zone.tests, 1 + zone.defendedVisits);
        double repeats = evidence / (evidence + 1.5);
        double rejection = Math.min(1, zone.rejectionAtr / 1.5);
        double recency = 1 / (1 + zone.barsSinceReaction / 36.0);
        return (.42 * repeats + .33 * rejection + .25 * recency)
            / (1 + .2 * zone.failedVisits) * (zone.breakPending ? .55 : 1);
    }

    private static void score(Results result, ZoneEngine.Zone zone, List<Market.Candle> next, double atr) {
        boolean touched = false;
        int beyond = 0;
        double margin = Math.max(.05, atr * .05);
        for (Market.Candle candle : next) {
            if (!touched && candle.low <= zone.high && candle.high >= zone.low) {
                touched = true;
                result.touches++;
            }
            if (!touched) continue;
            beyond = (zone.support ? candle.close < zone.low - margin : candle.close > zone.high + margin) ? beyond + 1 : 0;
            if (beyond >= 2) {
                result.invalidations++;
                return;
            }
            if (zone.support ? candle.close >= zone.high + .50 * atr : candle.close <= zone.low - .50 * atr) {
                result.reactions++;
                return;
            }
        }
        if (touched) result.unresolved++;
    }

    private static List<Market.Candle> read(Path file, Market.Frame frame) throws Exception {
        List<Market.Candle> bars = new ArrayList<>();
        try (BufferedReader input = Files.newBufferedReader(file)) {
            for (String line; (line = input.readLine()) != null;) {
                String[] col = line.split(",");
                long t = Long.parseLong(col[0]);
                bars.add(new Market.Candle(t, Double.parseDouble(col[1]), Double.parseDouble(col[2]),
                    Double.parseDouble(col[3]), Double.parseDouble(col[4]), t + frame.duration()));
            }
        }
        return bars;
    }
}
