package io.freemytv;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rokid Station (the box for Rokid's AR glasses): the glasses' 2D/3D, refresh rate, picture scene and speaker mode,
 * through Rokid's `rokid_display` service (IRokidDisplayManager in framework.jar; its setters check no permission).
 * The service has no SELinux label of its own, and Android doesn't let apps look up unlabelled services, so when the
 * binder can't be had the same calls go through the TV's own ADB (`service call`), as the shell's are allowed. The
 * calls run one at a time, in the order asked, on one thread; read and the setters wait for theirs: never on the main
 * thread.
 */
final class Rokid {
    private Rokid() {}

    private static final String SERVICE = "rokid_display", DESCRIPTOR = "com.rokid.display.IRokidDisplayManager";
    /** IRokidDisplayManager's transaction codes. */
    private static final int SET_MODE = 4, GET_MODE = 6, PLUGGED = 7, SET_AUDIO = 12, GET_AUDIO = 13, GET_FPS = 14,
            SET_FPS = 15, SET_SCENE = 16, GET_SCENE = 17;
    /** Display modes as getAirDisplayMode reports them; 3 is 2D at 120 Hz, which is what setting 120 Hz reads back as. */
    static final int MODE_2D = 0, MODE_3D = 1, MODE_2D_120 = 3;
    /** Refresh rates the Max takes (it refuses 90). */
    static final int[] FPS = {60, 120};
    /** Picture scenes: standard, movie, reading, eye care. */
    static final int[] SCENES = {1, 2, 3, 4};
    /** Speaker mode: private (quieter, so others hear less) or public. */
    static final int[] AUDIO = {0, 1};
    /** `service call`'s answer: the exception code (0 = none), then the int the call returned. */
    private static final Pattern PARCEL = Pattern.compile("Result: Parcel\\(([0-9a-f]{8}) ([0-9a-f]{8})");

    static final class State {
        int mode, fps, scene, audio;

        boolean is3d() {
            return mode == MODE_3D;
        }
    }

    private interface Calls {
        int call(int code, Integer arg) throws IOException;
    }

    private interface Work<T> {
        T run(Calls calls) throws IOException;
    }

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static IBinder binder;
    private static boolean binderTried;

    static boolean available() {
        return HomeMode.ROKID;
    }

    /** The glasses' settings now, or null when no glasses are plugged in or the service can't be reached. */
    static State read(Context c) {
        if (!available()) return null;
        return with(c, calls -> calls.call(PLUGGED, null) == 0 ? null : readAll(calls));
    }

    /** The glasses' display mode, or -1 when no glasses are plugged in or the service can't be reached. */
    static int mode(Context c) {
        if (!available()) return -1;
        Integer mode = with(c, calls -> calls.call(PLUGGED, null) == 0 ? -1 : calls.call(GET_MODE, null));
        return mode == null ? -1 : mode;
    }

    static State setMode(Context c, int mode) {
        return set(c, SET_MODE, mode);
    }

    static State setFps(Context c, int fps) {
        return set(c, SET_FPS, fps);
    }

    static State setScene(Context c, int scene) {
        return set(c, SET_SCENE, scene);
    }

    static State setAudio(Context c, int audio) {
        return set(c, SET_AUDIO, audio);
    }

    /**
     * The home screen is back: glasses left in 3D go back to 2D, as Rokid's own launcher does (a 3D player leaves them
     * in 3D, and our home screen isn't made for it). Nothing is set when no glasses are plugged in. Doesn't wait; a
     * read asked after it (the drawer's 2D/3D button) gets the mode it leaves.
     */
    static void homeResumed(Context c) {
        if (!available()) return;
        Context app = c.getApplicationContext();
        WORK.execute(() -> run(app, calls -> {
            if (calls.call(PLUGGED, null) == 0 || calls.call(GET_MODE, null) != MODE_3D) return null;
            AppLog.w("glasses: back to 2D on the home screen (" + (calls.call(SET_MODE, MODE_2D) == 1 ? "done" : "refused") + ")");
            return null;
        }));
    }

    /** Changes one setting when glasses are plugged in, then reads them all back (120 Hz changes the mode too). */
    private static State set(Context c, int code, int value) {
        if (!available()) return null;
        return with(c, calls -> {
            if (calls.call(PLUGGED, null) == 0) return null;
            int ok = calls.call(code, value);
            AppLog.w("glasses: call " + code + " = " + value + (ok == 1 ? "" : " refused"));
            SystemClock.sleep(300);   // the mode and refresh rate are applied on Rokid's own thread
            return readAll(calls);
        });
    }

    private static State readAll(Calls calls) throws IOException {
        State s = new State();
        s.mode = calls.call(GET_MODE, null);
        s.fps = calls.call(GET_FPS, null);
        s.scene = calls.call(GET_SCENE, null);
        s.audio = calls.call(GET_AUDIO, null);
        return s;
    }

    /** Runs `work` on the calls' thread and waits for it. Null when any of it fails. */
    private static <T> T with(Context c, Work<T> work) {
        Context app = c.getApplicationContext();
        try {
            return WORK.submit(() -> run(app, work)).get();
        } catch (ExecutionException e) {
            AppLog.w("glasses: " + e.getCause());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Runs `work` over the binder, or else over one ADB connection. Null when any of it fails. */
    private static <T> T run(Context app, Work<T> work) {
        IBinder b = binder();
        try {
            if (b != null) return work.run((code, arg) -> transact(b, code, arg));
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                return work.run((code, arg) -> shell(adb, code, arg));
            }
        } catch (IOException | RuntimeException e) {
            AppLog.w("glasses: " + e.getMessage());
            return null;
        }
    }

    /** Looked up once per process (on the calls' thread); the log says which way the calls go. */
    private static IBinder binder() {
        if (binderTried) return binder != null && binder.isBinderAlive() ? binder : null;
        binderTried = true;
        try {
            binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                    .invoke(null, SERVICE);
        } catch (ReflectiveOperationException | RuntimeException e) {
            binder = null;
        }
        AppLog.w("glasses: rokid_display " + (binder != null ? "over the binder" : "over ADB (the app can't look it up)"));
        return binder;
    }

    private static int transact(IBinder b, int code, Integer arg) throws IOException {
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            in.writeInterfaceToken(DESCRIPTOR);
            if (arg != null) in.writeInt(arg);
            b.transact(code, in, out, 0);
            out.readException();
            return out.readInt();   // a boolean comes back as an int
        } catch (RemoteException | RuntimeException e) {
            throw new IOException("rokid_display call " + code + ": " + e);
        } finally {
            in.recycle();
            out.recycle();
        }
    }

    private static int shell(Adb adb, int code, Integer arg) throws IOException {
        String reply = adb.shell("service call " + SERVICE + " " + code + (arg == null ? "" : " i32 " + arg));
        Matcher m = PARCEL.matcher(reply);
        if (!m.find() || Long.parseLong(m.group(1), 16) != 0) throw new IOException("rokid_display call " + code + ": " + reply.trim());
        return (int) Long.parseLong(m.group(2), 16);
    }
}
