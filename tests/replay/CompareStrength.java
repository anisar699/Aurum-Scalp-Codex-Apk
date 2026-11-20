package com.aurum.scalp;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import static com.aurum.scalp.ReactionReplay.*;

/** Compare pivot definitions and lifecycle states on identical closed-candle prefixes. */
public final class CompareStrength {
    public static void main(String[] args) throws Exception {
        Market.Frame frame = Market.Frame.valueOf(args[0]);
        long split = Long.parseLong(args[1]), until = Long.parseLong(args[2]);
        int stride = Integer.parseInt(args[3]), history = Integer.parseInt(args[4]);
        List<Market.Candle> bars = read(Path.of(args[5]), frame);
        Results current = new Results(), retest = new Results();
        Results confirmedTurns = new Results(true), pendingTurns = new Results(true), retestTurns = new Results(true);
        int turnHorizon = switch (frame) { case M5 -> 72; case M15 -> 64; case H1 -> 48; default -> 24; };
        int nextTurnOrigin = 0;
        double[] thresholds = {.5, .75, 1};
        Results[] strict = {new Results(), new Results(), new Results()};
        Results recent = new Results();
        int evaluated = 0, gaps = 0;
        for (int n = WARMUP; n + HORIZON <= bars.size(); n += stride) {
            Market.Candle origin = bars.get(n - 1);
            if (origin.endTime < split || bars.get(n + HORIZON - 1).endTime >= until) continue;
            if (!consecutive(bars, n, frame.duration())) {
                gaps++;
                continue;
            }
            List<Market.Candle> prefix = bars.subList(Math.max(0, n - history), n);
            List<Market.Candle> future = bars.subList(n, n + HORIZON);
            ZoneEngine.Analysis production = ZoneEngine.calculate(prefix, frame, origin.endTime);
            if (production.atr <= 0) continue;
            evaluated++;
            ZoneEngine.Analysis[] alternatives = new ZoneEngine.Analysis[thresholds.length];
            for (int i = 0; i < thresholds.length; i++)
                alternatives[i] = ZoneEngine.calculate(prefix, frame, origin.endTime, 5, thresholds[i]);

            Evaluation turnWindow = null;
            if (n >= nextTurnOrigin && n + turnHorizon <= bars.size() && bars.get(n + turnHorizon - 1).endTime < until) {
                turnWindow = turns(bars.subList(n, n + turnHorizon), production.atr, frame.duration());
                nextTurnOrigin = n + turnHorizon;
            }
            for (boolean support : new boolean[]{true, false}) {
                double price = origin.close, atr = production.atr;
                ZoneEngine.Zone nearest = first(ZoneSelection.confirmed(production, price, support, 1, LIMIT));
                current.add(nearest, price, atr, origin.endTime, future);
                List<ZoneEngine.Zone> watches = ZoneSelection.lifecycleWatches(production, price, support, Integer.MAX_VALUE, LIMIT);
                ZoneEngine.Zone retestWatch = watches.stream().filter(z -> z.retestWatch).findFirst().orElse(null);
                retest.add(retestWatch, price, atr, origin.endTime, future);
                if (turnWindow != null) {
                    ZoneEngine.Zone breakPending = watches.stream().filter(z -> z.breakPending).findFirst().orElse(null);
                    confirmedTurns.add(nearest, price, atr, origin.endTime, turnWindow);
                    pendingTurns.add(breakPending, price, atr, origin.endTime, turnWindow);
                    retestTurns.add(retestWatch, price, atr, origin.endTime, turnWindow);
                }
                for (int i = 0; i < thresholds.length; i++) {
                    boolean compareRecency = thresholds[i] == .75;
                    List<ZoneEngine.Zone> candidates = ZoneSelection.confirmed(alternatives[i], price, support,
                        compareRecency ? Integer.MAX_VALUE : 1, LIMIT);
                    strict[i].add(first(candidates), price, atr, origin.endTime, future);
                    if (compareRecency) {
                        // At most 0.25 ATR of ranking credit, halved every 24 bars.
                        ZoneEngine.Zone best = candidates.stream().min(Comparator.comparingDouble(z ->
                            z.distance(price) / atr - .25 * Math.pow(.5, z.barsSinceReaction / 24.0))).orElse(null);
                        recent.add(best, price, atr, origin.endTime, future);
                    }
                }
            }
        }
        System.out.printf(Locale.US, "%s candles=%d evaluated=%d skippedGaps=%d historyCap=%d stride=%d%n",
            frame.label, bars.size(), evaluated, gaps, history, stride);
        current.print("production");
        retest.print("retest watch, proposed new role");
        for (int i = 0; i < thresholds.length; i++) strict[i].print("5-wing, min rejection=" + thresholds[i] + " ATR");
        recent.print("5-wing, min rejection=0.75 ATR, bounded recency");
        System.out.printf("%d-bar turn location probe (nearest band per state and side)%n", turnHorizon);
        confirmedTurns.print("confirmed turns");
        pendingTurns.print("break-pending turns");
        retestTurns.print("retest-watch turns");
    }

    private static ZoneEngine.Zone first(List<ZoneEngine.Zone> zones) {
        return zones.isEmpty() ? null : zones.get(0);
    }
}
