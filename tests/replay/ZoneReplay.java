package com.aurum.scalp;

import java.nio.file.Path;
import java.util.List;
import static com.aurum.scalp.ReactionReplay.*;

/** Small integration replay on checked-in Biquote fixtures or recent provider samples. */
public final class ZoneReplay {
    public static void main(String[] inputs) throws Exception {
        System.out.println("Six consecutive closed bars; nearest confirmed band per side within 3 ATR; 20 controls per band.");
        for (String file : inputs) {
            Path path = Path.of(file);
            String[] label = path.getFileName().toString().split("__");
            Market.Frame frame = Market.Frame.valueOf(label[1].split("\\.")[0]);
            List<Market.Candle> bars = read(path, frame);
            Results results = new Results();
            int windows = 0, gaps = 0;
            for (int n = WARMUP; n + HORIZON <= bars.size(); n += HORIZON) {
                if (!consecutive(bars, n, frame.duration())) {
                    gaps++;
                    continue;
                }
                windows++;
                Market.Candle origin = bars.get(n - 1);
                ZoneEngine.Analysis analysis = ZoneEngine.calculate(bars.subList(0, n), frame, origin.endTime);
                List<Market.Candle> future = bars.subList(n, n + HORIZON);
                for (boolean support : new boolean[]{true, false})
                    for (ZoneEngine.Zone zone : ZoneSelection.confirmed(analysis, origin.close, support, 1, LIMIT))
                        results.add(zone, origin.close, analysis.atr, origin.endTime, future);
            }
            System.out.printf("%s %s closed=%d accepted-windows=%d skipped-gaps=%d%n",
                label[0], frame.label, bars.size(), windows, gaps);
            results.print("production");
        }
    }
}
