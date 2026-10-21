package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * Rokid Station: its battery (the box runs unplugged, behind a fuel gauge). Only there: many TV boxes report a stand-in
 * battery at 100 %, which would read as a real one.
 */
final class Power {
    private Power() {}

    static final class Battery {
        int level;          // percent
        boolean plugged;
    }

    static boolean available() {
        return HomeMode.ROKID;
    }

    /** The battery now (the sticky broadcast), or null where there's none to show. */
    static Battery battery(Context c) {
        if (!available()) return null;
        return battery(c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
    }

    /** What an ACTION_BATTERY_CHANGED says, or null when it has no battery in it. */
    static Battery battery(Intent i) {
        if (i == null || !i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return null;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return null;
        Battery b = new Battery();
        b.level = Math.round(100f * level / scale);
        b.plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return b;
    }

    /** The battery's symbol: a bolt while plugged in, else how full it is. */
    static String symbol(Battery b) {
        if (b.plugged) return "battery_bolt";
        return b.level >= 90 ? "battery_full" : b.level >= 60 ? "battery_5" : b.level >= 35 ? "battery_3"
                : b.level >= 10 ? "battery_1" : "battery_alert";
    }
}
