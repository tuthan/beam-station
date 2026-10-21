package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Rokid Station: its battery (the box runs unplugged, behind a fuel gauge), the screensaver and sleep timeouts, and a
 * sleep timer. Only there: many TV boxes report a stand-in battery at 100 %, which would read as a real one. Rokid's
 * Settings has no timeouts; they're set through the TV's own ADB (`settings put`), as Android TV's Settings would, and
 * read back. The sleep timer is an alarm; when it rings, the TV's ADB presses the sleep key.
 */
final class Power {
    private Power() {}

    static final class Battery {
        int level;          // percent
        boolean plugged;
    }

    static final class Timeouts {
        int screensaver, sleep;   // seconds idle; sleep -1: never
        boolean stayOnPlugged;    // Android keeps the screen on while plugged in (stay_on_while_plugged_in)
    }

    /** Seconds idle before the screensaver starts: 30 s to 30 min. */
    static final int[] SCREENSAVER = {30, 60, 120, 300, 600, 900, 1800};
    /** Seconds idle (the screensaver counts) before the Station sleeps: 5 min to 4 h; -1: never. */
    static final int[] SLEEP = {300, 600, 900, 1800, 3600, 7200, 14400, -1};
    /** The sleep timer's steps, in minutes; 0: off. */
    static final int[] TIMER = {0, 15, 30, 60, 90, 120};
    /** The sleep timer's alarm (to HomeReceiver, in the main process). */
    static final String SLEEP_TIMER = "io.freemytv.SLEEP_TIMER";
    /** When the sleep timer goes off (System.currentTimeMillis), shared by the processes; empty: off. */
    private static final String TIMER_FILE = "sleep-timer.txt";

    static boolean available() {
        return HomeMode.ROKID;
    }

    // MARK: - Battery

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

    // MARK: - Timeouts (blocking: never on the main thread)

    static Timeouts timeouts(Context c) throws IOException {
        try (Adb adb = Adb.connect(c.getApplicationContext(), 10_000, null)) {
            return timeouts(adb);
        }
    }

    static Timeouts setScreensaver(Context c, int seconds) throws IOException {
        return set(c, "system screen_off_timeout", seconds * 1000L);
    }

    static Timeouts setSleep(Context c, int seconds) throws IOException {
        return set(c, "secure sleep_timeout", seconds < 0 ? -1 : seconds * 1000L);
    }

    private static Timeouts set(Context c, String setting, long ms) throws IOException {
        try (Adb adb = Adb.connect(c.getApplicationContext(), 10_000, null)) {
            adb.shell("settings put " + setting + " " + ms);
            AppLog.w("power: " + setting + " = " + ms);
            return timeouts(adb);
        }
    }

    private static Timeouts timeouts(Adb adb) throws IOException {
        Timeouts t = new Timeouts();
        t.screensaver = seconds(adb.shell("settings get system screen_off_timeout"));
        t.sleep = seconds(adb.shell("settings get secure sleep_timeout"));
        t.stayOnPlugged = !adb.shell("settings get global stay_on_while_plugged_in").trim().equals("0");
        return t;
    }

    /** Milliseconds as `settings get` prints them, in seconds; -1 for never (negative, or unset: "null"). */
    private static int seconds(String ms) {
        try {
            long v = Long.parseLong(ms.trim());
            return v < 0 ? -1 : (int) Math.round(v / 1000.0);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A timeout in seconds, as the settings show it: 30 s, 5 min, 2 h. */
    static String seconds(int s) {
        return s < 60 ? s + (Ui.ru ? " с" : " s") : duration(Math.round(s / 60f));
    }

    /** A length of time, as the settings show it: 30 min, 2 h, 1 h 30 min. */
    static String duration(int minutes) {
        String min = Ui.ru ? " мин" : " min", h = Ui.ru ? " ч" : " h";
        if (minutes < 60) return minutes + min;
        return minutes % 60 == 0 ? minutes / 60 + h : minutes / 60 + h + " " + minutes % 60 + min;
    }

    // MARK: - Sleep timer

    /** When the sleep timer goes off (System.currentTimeMillis), or 0 when it's off. */
    static long timerEnd(Context c) {
        long end = readEnd(c);
        return end > System.currentTimeMillis() ? end : 0;
    }

    /** Minutes left on the sleep timer at `end` (from timerEnd), rounded up; 0 when it's off. */
    static int left(long end) {
        long ms = end - System.currentTimeMillis();
        return end == 0 || ms <= 0 ? 0 : (int) ((ms + 59_999) / 60_000);
    }

    /** The timer step after (dir 1) or before (dir -1) what's left now: off, 15, 30, 60, 90, 120 min. */
    static int nextTimer(int left, int dir) {
        int at = 0;
        while (at < TIMER.length - 1 && TIMER[at] < left) at++;
        if (left > TIMER[at]) at = TIMER.length - 1;
        return TIMER[(at + dir + TIMER.length) % TIMER.length];
    }

    /** Sets the sleep timer to `minutes` from now; 0 turns it off. */
    static void setTimer(Context c, int minutes) {
        Context app = c.getApplicationContext();
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        PendingIntent ring = PendingIntent.getBroadcast(app, 0, new Intent(SLEEP_TIMER).setClass(app, HomeReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (minutes <= 0) {
            alarms.cancel(ring);
            writeEnd(app, 0);
            AppLog.w("sleep timer: off");
            return;
        }
        long ms = minutes * 60_000L;
        // Not a wakeup alarm: asleep already, there's nothing to do (timerRang sees it's late).
        alarms.setWindow(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + ms, 30_000, ring);
        writeEnd(app, System.currentTimeMillis() + ms);
        AppLog.w("sleep timer: " + minutes + " min");
    }

    /** The sleep timer rang: the Station goes to sleep. Rang late (it slept meanwhile and has just woken): nothing. */
    static void timerRang(Context c) {
        long end = readEnd(c);
        writeEnd(c, 0);
        if (end == 0 || System.currentTimeMillis() - end > 120_000) return;
        try (Adb adb = Adb.connect(c.getApplicationContext(), 10_000, null)) {
            adb.shell("input keyevent KEYCODE_SLEEP");
            AppLog.w("sleep timer: the Station goes to sleep");
        } catch (IOException e) {
            AppLog.w("sleep timer: " + e.getMessage());
        }
    }

    private static long readEnd(Context c) {
        try {
            File f = new File(c.getFilesDir(), TIMER_FILE);
            return f.exists() ? Long.parseLong(new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim()) : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private static void writeEnd(Context c, long end) {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), TIMER_FILE))) {
            out.write((end == 0 ? "" : String.valueOf(end)).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            AppLog.w("sleep timer: " + e.getMessage());
        }
    }
}
