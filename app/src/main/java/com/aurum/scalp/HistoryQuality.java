package com.aurum.scalp;

import java.util.List;

final class HistoryQuality {
    private HistoryQuality() {}
    static int gaps(List<Market.Candle> bars, Market.Frame frame, int window) {
        int gaps = 0;
        for (int i = Math.max(1, bars.size() - window); i < bars.size(); i++) {
            if (!ZoneEngine.continuous(bars, i-1, i, frame.duration())) gaps++;
        }
        return gaps;
    }
    static boolean sameBars(List<Market.Candle> before, List<Market.Candle> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            Market.Candle a = before.get(i), b = after.get(i);
            if (a.time != b.time || a.endTime != b.endTime || a.open != b.open || a.high != b.high || a.low != b.low || a.close != b.close) return false;
        }
        return true;
    }
}
