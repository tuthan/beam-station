package io.freemytv;

import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The time zone on XGIMI projectors, whose own date and time screen is Chinese only, and whose China ROM keeps
 * Shanghai's zone wherever it is (automatic time zone needs a SIM). Android lets only the system set the zone, so this
 * goes through the TV's own ADB (`cmd alarm set-timezone`), as Wifi does; the date and time come from the network.
 */
final class Zones {
    private Zones() {}

    private static final Set<String> REGIONS = new HashSet<>(Arrays.asList(
            "Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific"));

    /** The zones to pick from (one per place, like Asia/Ho_Chi_Minh), by UTC offset now, then by name. */
    static List<String> all() {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        Map<String, Integer> offsets = new HashMap<>();
        for (String id : TimeZone.getAvailableIDs()) {
            int slash = id.indexOf('/');
            if (slash <= 0 || !REGIONS.contains(id.substring(0, slash))) continue;
            out.add(id);
            offsets.put(id, TimeZone.getTimeZone(id).getOffset(now));
        }
        Collections.sort(out, (a, b) -> {
            int oa = offsets.get(a), ob = offsets.get(b);
            return oa != ob ? Integer.compare(oa, ob) : place(a).compareTo(place(b));
        });
        return out;
    }

    /** "Ho Chi Minh · GMT+7" */
    static String label(String id) {
        return place(id) + " · " + offset(id);
    }

    /** The place in a zone's id: Asia/Ho_Chi_Minh → Ho Chi Minh, America/Argentina/Buenos_Aires → Buenos Aires. */
    static String place(String id) {
        return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
    }

    /** "GMT+7", "GMT+5:30", "GMT" */
    static String offset(String id) {
        int minutes = TimeZone.getTimeZone(id).getOffset(System.currentTimeMillis()) / 60_000;
        if (minutes == 0) return "GMT";
        int h = Math.abs(minutes) / 60, m = Math.abs(minutes) % 60;
        return "GMT" + (minutes < 0 ? "-" : "+") + h + (m == 0 ? "" : String.format(Locale.ROOT, ":%02d", m));
    }

    /** A zone's id only if Android knows it: the ids are letters, digits, '/', '_', '+' and '-', safe for the shell. */
    static boolean known(String id) {
        return id != null && id.matches("[A-Za-z0-9/_+\\-]+") && Arrays.asList(TimeZone.getAvailableIDs()).contains(id);
    }

    /** Sets the zone, as Settings does when one is picked: automatic time zone off (it can't work here anyway). */
    static void set(Context c, String id) throws IOException {
        if (!known(id)) throw new IOException("unknown time zone " + id);
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            adb.shell("settings put global auto_time_zone 0");
            adb.shell("cmd alarm set-timezone " + id);
        }
        AppLog.w("time zone: " + id);
    }
}
