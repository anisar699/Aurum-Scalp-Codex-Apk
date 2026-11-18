package com.aurum.scalp;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** One independently configured alert per timeframe, stored on this device. */
final class ZonePins {
    private final SharedPreferences settings;
    private final Map<Market.Frame, ZoneSelection.Pin> pins = new EnumMap<>(Market.Frame.class);
    ZonePins(SharedPreferences settings) {
        this.settings = settings;
        for (Market.Frame f : Market.Frame.values()) {
            String value = settings.getString(key(f), null);
            if (value == null) continue;
            try {
                JSONObject j = new JSONObject(value);
                double low = j.getDouble("low"), high = j.getDouble("high");
                if (low > 0 && high >= low && Market.finite(high)) pins.put(f, new ZoneSelection.Pin(f,
                    j.getString("id"), low, high, j.getBoolean("support"), ZoneSelection.Trigger.valueOf(j.getString("trigger"))));
            } catch (IllegalArgumentException | JSONException ignored) {}
        }
    }
    Map<Market.Frame, ZoneSelection.Pin> current() { return Collections.unmodifiableMap(pins); }
    void put(ZoneEngine.Zone zone, ZoneSelection.Trigger trigger) {
        if (zone.reference && (!zone.retestWatch || trigger != ZoneSelection.Trigger.RETEST)) return;
        try {
            JSONObject j = new JSONObject().put("id", zone.id).put("low", zone.low).put("high", zone.high)
                .put("support", zone.support).put("trigger", trigger.name());
            settings.edit().putString(key(zone.frame), j.toString()).apply();
            pins.put(zone.frame, new ZoneSelection.Pin(zone.frame, zone.id, zone.low, zone.high, zone.support, trigger));
        } catch (JSONException ignored) {}
    }
    void remove(Market.Frame frame) {
        pins.remove(frame);
        settings.edit().remove(key(frame)).apply();
    }
    private static String key(Market.Frame f) { return "zone-pin-" + f.name(); }
}
