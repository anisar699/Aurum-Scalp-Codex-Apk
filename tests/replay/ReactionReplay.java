package com.aurum.scalp;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/** Shared scoring for offline replay. Only the scoring step can see future candles. */
final class ReactionReplay {
    static final int WARMUP = 80, HORIZON = 6;
    static final ZoneSelection.Distance LIMIT = new ZoneSelection.Distance(ZoneSelection.Unit.ATR, 3);
    private static final int CONTROLS = 20;
    private static final long WEEK_MS = 7 * 24 * 60 * 60 * 1000L;

    private enum Outcome { UNTOUCHED, REACTED, BROKEN, UNRESOLVED }

    interface Evaluation {
        Outcome outcome(double low, double high, boolean support);
    }

    static boolean consecutive(List<Market.Candle> bars, int start, long duration) {
        for (int i = start; i < start + HORIZON; i++)
            if (bars.get(i).time != bars.get(i - 1).time + duration) return false;
        return true;
    }

    private static Outcome score(double low, double high, boolean support, double atr, List<Market.Candle> future) {
        boolean touched = false;
        int adverse = 0;
        double buffer = Math.max(.05, .05 * atr);
        for (Market.Candle candle : future) {
            boolean alreadyTouched = touched;
            touched |= candle.low <= high && candle.high >= low;
            if (!touched) continue;
            adverse = (support ? candle.close < low - buffer : candle.close > high + buffer) ? adverse + 1 : 0;
            if (adverse >= 2) return Outcome.BROKEN;
            // Require a later close so OHLC on the touch candle doesn't establish a reaction.
            if (alreadyTouched && (support ? candle.close >= high + .5 * atr : candle.close <= low - .5 * atr))
                return Outcome.REACTED;
        }
        return touched ? Outcome.UNRESOLVED : Outcome.UNTOUCHED;
    }

    private static Outcome control(double u, double offset, ZoneEngine.Zone zone, double price, Evaluation evaluation) {
        double width = zone.width();
        boolean below = zone.high < price || zone.contains(price) && zone.support;
        double low = offset == 0 ? price - u * width : below ? price - offset - width : price + offset;
        return evaluation.outcome(low, low + width, zone.support);
    }

    static Evaluation turns(List<Market.Candle> future, double atr, long duration) {
        int wing = 5;
        List<Double> pivots = new ArrayList<>();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < future.size(); i++) {
            Market.Candle bar = future.get(i);
            min = Math.min(min, bar.low);
            max = Math.max(max, bar.high);
            if (i < wing || i >= future.size() - wing) continue;
            boolean high = true, low = true;
            for (int k = 1; k <= wing; k++) {
                high &= bar.high >= future.get(i-k).high && bar.high > future.get(i+k).high;
                low &= bar.low <= future.get(i-k).low && bar.low < future.get(i+k).low;
            }
            if ((high || low) && ZoneEngine.continuous(future, i-wing, i+wing, duration)) {
                if (high) pivots.add(bar.high);
                if (low) pivots.add(bar.low);
            }
        }
        double windowLow = min, windowHigh = max, tolerance = .3 * atr;
        return (low, high, role) -> {
            if (windowLow > high || windowHigh < low) return Outcome.UNTOUCHED;
            if (future.stream().noneMatch(bar -> bar.low <= high && bar.high >= low)) return Outcome.UNTOUCHED;
            for (double pivot : pivots)
                if (pivot >= low - tolerance && pivot <= high + tolerance) return Outcome.REACTED;
            return Outcome.UNRESOLVED;
        };
    }

    private static final class Counts {
        double selected, touches, reactions, breaks;
        void add(Outcome outcome, double weight) {
            selected += weight;
            if (outcome != Outcome.UNTOUCHED) touches += weight;
            if (outcome == Outcome.REACTED) reactions += weight;
            if (outcome == Outcome.BROKEN) breaks += weight;
        }
        void merge(Counts counts) {
            selected += counts.selected;
            touches += counts.touches;
            reactions += counts.reactions;
            breaks += counts.breaks;
        }
    }

    private static final class Block {
        final Counts real = new Counts(), matched = new Counts(), uniform = new Counts();
    }

    static final class Results {
        private final Map<Long, Block> weeks = new TreeMap<>();
        private final boolean turnProbe;

        Results() { this(false); }
        Results(boolean turnProbe) { this.turnProbe = turnProbe; }

        void add(ZoneEngine.Zone zone, double price, double atr, long origin, List<Market.Candle> future) {
            add(zone, price, atr, origin, (low, high, role) -> score(low, high, role, atr, future));
        }

        void add(ZoneEngine.Zone zone, double price, double atr, long origin, Evaluation evaluation) {
            if (zone == null) return;
            Block block = weeks.computeIfAbsent(Math.floorDiv(origin, WEEK_MS), ignored -> new Block());
            block.real.add(evaluation.outcome(zone.low, zone.high, zone.support), 1);
            double edge = zone.distance(price);
            double bin = Math.min(5, Math.floor(edge / (.5 * atr))) * .5 * atr;
            double weight = 1.0 / CONTROLS;
            SplittableRandom random = new SplittableRandom(origin ^ (zone.support ? 957331L : 611257L));
            for (int i = 0; i < CONTROLS; i++) {
                double u = random.nextDouble();
                // Bands containing price are matched to other bands containing it.
                block.matched.add(control(u, edge == 0 ? 0 : bin + u * .5 * atr, zone, price, evaluation), weight);
                block.uniform.add(control(u, 3 * atr * u, zone, price, evaluation), weight);
            }
        }

        void print(String label) {
            Block totals = new Block();
            for (Block block : weeks.values()) {
                totals.real.merge(block.real);
                totals.matched.merge(block.matched);
                totals.uniform.merge(block.uniform);
            }
            Counts real = totals.real;
            if (turnProbe) {
                System.out.printf(Locale.US, "%s selected=%.0f touches=%.0f nearby-turns=%.0f weeks=%d%n",
                    label, real.selected, real.touches, real.reactions, weeks.size());
            } else {
                System.out.printf(Locale.US, "%s selected=%.0f touches=%.0f reactions=%.0f breaks=%.0f unresolved=%.0f weeks=%d%n",
                    label, real.selected, real.touches, real.reactions, real.breaks, real.touches-real.reactions-real.breaks, weeks.size());
            }
            printControl("distance-matched", totals, true);
            printControl("uniform 0–3 ATR", totals, false);
        }

        private void printControl(String label, Block totals, boolean matched) {
            Counts control = matched ? totals.matched : totals.uniform;
            if (turnProbe) {
                System.out.printf(Locale.US, "  %s control-touches=%.1f | turn/touch %s | turn/selection %s%n", label, control.touches,
                    compare(totals.real, control, matched, false, true), compare(totals.real, control, matched, false, false));
                return;
            }
            System.out.printf(Locale.US, "  %s control-touches=%.1f | reacted/touch %s | broken/touch %s | reacted/selection %s%n",
                label, control.touches, compare(totals.real, control, matched, false, true),
                compare(totals.real, control, matched, true, true), compare(totals.real, control, matched, false, false));
        }

        private static double count(Counts c, boolean breaks) { return breaks ? c.breaks : c.reactions; }
        private static double exposure(Counts c, boolean perTouch) { return perTouch ? c.touches : c.selected; }

        private String compare(Counts real, Counts control, boolean matched, boolean breaks, boolean perTouch) {
            double nr = exposure(real, perTouch), nc = exposure(control, perTouch);
            if (nr == 0 || nc == 0) return "n/a";
            double pr = count(real, breaks) / nr, pc = count(control, breaks) / nc;
            double variance = 0;
            for (Block block : weeks.values()) {
                Counts r = block.real, c = matched ? block.matched : block.uniform;
                double influence = (count(r, breaks) - pr * exposure(r, perTouch)) / nr
                    - (count(c, breaks) - pc * exposure(c, perTouch)) / nc;
                variance += influence * influence;
            }
            int n = weeks.size();
            String se = n < 8 ? "n/a" : String.format(Locale.US, "%.1fpp", 100 * Math.sqrt(variance * n / (n - 1)));
            return String.format(Locale.US, "%.1f%% vs %.1f%% (%+.1fpp, weekly SE %s)", pr*100, pc*100, (pr-pc)*100, se);
        }
    }

    static List<Market.Candle> read(Path file, Market.Frame frame) throws Exception {
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
