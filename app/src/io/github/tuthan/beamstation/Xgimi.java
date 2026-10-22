package io.github.tuthan.beamstation;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.tv.TvContract;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * XGIMI projectors: auto focus, auto keystone, speaker mode and HDMI through XGIMI's own services (exported, with no
 * permission), the Play 6's battery, eco mode, corrections after a move and HDMI game mode, and the system language. XGIMI's screens are Chinese only, and its Settings switches the system to
 * Chinese each time it opens, with a notice whose OK closes the panel. So in our mode the system language
 * follows the app's: Chinese just before XGIMI's panel opens (no notice), ours again when our windows get focus back.
 */
final class Xgimi {
    private Xgimi() {}

    /** XGIMI SystemUI's focus and keystone request; `type` 8 focuses, 10 squares the picture. */
    private static final String AF_AK = "com.xgimi.systemui.action.AF_AK", SYSTEM_UI = "com.xgimi.systemui";
    private static final int FOCUS = 8, KEYSTONE = 10;
    private static final ComponentName SPEAKER = new ComponentName("com.xgimi.soundermodeservice",
            "com.xgimi.soundermodeservice.SounderModeService");
    /** IGimiSound's calls (1 and 2); 3 is what XGIMI's own button passes to turn speaker mode on, 0 turns it off. */
    private static final String SOUND = "com.xgimi.aidl.IGimiSound";
    private static final int GET_MODE = IBinder.FIRST_CALL_TRANSACTION, SET_MODE = GET_MODE + 1, SPEAKER_ON = 3;
    private static final ComponentName SETTINGS = new ComponentName("com.android.newsettings",
            "com.xgimi.newsettings.SettingService");
    /** How long XGIMI's panel has to show up (and take focus) after the system switches to Chinese for it. */
    private static final long LEND_MS = 3000;

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    /** The stock home check (ADB), apart from WORK so the language never waits on it. */
    private static final ExecutorService CHECK = Executors.newSingleThreadExecutor();
    private static final long HOME_CHECK_MS = 60_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean focused, lent, verifyAsked;
    private static volatile long lentAt, homeCheckedAt;

    static boolean hasFocusKeystone(Context c) {
        return HomeMode.XGIMI && c.getPackageManager().resolveService(afAk(0), 0) != null;
    }

    static boolean hasSpeaker(Context c) {
        return HomeMode.XGIMI && c.getPackageManager().resolveService(new Intent().setComponent(SPEAKER), 0) != null;
    }

    static boolean autoFocus(Context c) {
        return start(c, afAk(FOCUS));
    }

    static boolean autoKeystone(Context c) {
        return start(c, afAk(KEYSTONE));
    }

    private static Intent afAk(int type) {
        return new Intent(AF_AK).setPackage(SYSTEM_UI).putExtra("type", type);
    }

    /**
     * An HDMI input in XGIMI's player (com.xgimi.xhplayer), as XGIMI Home opens it: Android's own request for the input
     * goes to Live Channels. The type is given, so the match doesn't wait on the TV provider.
     */
    static Intent hdmi(String inputId) {
        return new Intent("com.xgimi.action.hdmiPlayer")
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(inputId), TvContract.Channels.CONTENT_ITEM_TYPE);
    }

    private static boolean start(Context c, Intent service) {
        try {
            return c.startService(service) != null;
        } catch (IllegalStateException | SecurityException e) {
            AppLog.w("XGIMI focus and keystone: " + e.getMessage());
            return false;
        }
    }

    /**
     * Speaker mode on or off (the projector plays a phone's Bluetooth audio). `done` gets, on the main thread, whether
     * it is on now, or null when it didn't change (XGIMI refuses it while casting, for one). False: the service isn't there.
     */
    static boolean toggleSpeaker(Context c, Consumer<Boolean> done) {
        Context app = c.getApplicationContext();
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                ServiceConnection self = this;
                WORK.execute(() -> {
                    Boolean on = null;
                    try {
                        boolean was = mode(binder) != 0;
                        setMode(binder, was ? 0 : SPEAKER_ON);
                        SystemClock.sleep(1500);   // XGIMI applies it on its own thread
                        boolean now = mode(binder) != 0;
                        AppLog.w("speaker mode " + (now ? "on" : "off"));
                        if (now != was) on = now;
                    } catch (RemoteException | RuntimeException e) {
                        AppLog.w("speaker mode: " + e);
                    }
                    try {
                        app.unbindService(self);
                    } catch (IllegalArgumentException ignored) {
                    }
                    Boolean result = on;
                    MAIN.post(() -> done.accept(result));
                });
            }

            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        try {
            if (app.bindService(new Intent("xgimi.service.SOUNDERMODESERVICE").setComponent(SPEAKER), conn,
                    Context.BIND_AUTO_CREATE)) {
                return true;
            }
            app.unbindService(conn);
        } catch (SecurityException | IllegalArgumentException e) {
            AppLog.w("speaker mode: " + e.getMessage());
        }
        return false;
    }

    // MARK: - Battery and eco mode

    /**
     * The Play 6's battery, from XGIMI's platform library (com.xgimi.api, on the boot class path, so this app can call
     * it as XGIMI's own apps do). Android's battery service doesn't know this battery: it says there is none.
     */
    static final class Battery {
        int level;
        boolean adapter, full;
    }

    /** XGIMI's Play 6 (code name dayao): the model whose battery and eco mode were checked. */
    static final boolean PLAY_6 = HomeMode.XGIMI && "Product_dayao".equals(android.os.Build.PRODUCT);

    /** The battery now, or null where there is none to read. Blocks briefly: not on the UI thread. */
    static Battery battery() {
        if (!PLAY_6) return null;
        try {
            Object power = platform("com.xgimi.gmpf.api.PowerManager");
            Battery b = new Battery();
            b.level = ((Number) call(power, "getBatteryLevel")).intValue();
            b.adapter = (Boolean) call(power, "isAdapterPowered");
            b.full = (Boolean) call(power, "isBatteryChargedFull");
            return b.level >= 0 && b.level <= 100 ? b : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            AppLog.w("battery: " + e);
            return null;
        }
    }

    static boolean hasEco() {
        return PLAY_6;
    }

    /**
     * Eco mode on or off: lower brightness and sound for a longer battery, as XGIMI's Settings sets it. `done` gets,
     * on the main thread, whether it is on now, or null when it didn't change.
     */
    static void toggleEco(Consumer<Boolean> done) {
        WORK.execute(() -> {
            Boolean on = null;
            try {
                Object system = platform("com.xgimi.gmpf.api.SystemManager");
                boolean was = (Boolean) call(system, "getEcoState");
                system.getClass().getMethod("setEcoState", boolean.class).invoke(system, !was);
                boolean now = (Boolean) call(system, "getEcoState");
                AppLog.w("eco mode " + (now ? "on" : "off"));
                if (now != was) on = now;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                AppLog.w("eco mode: " + e);
            }
            Boolean result = on;
            MAIN.post(() -> done.accept(result));
        });
    }

    // MARK: - When the projector moves

    /**
     * What the Play 6 does when it's moved: square the picture again (XGIMI's "real-time picture correction") and
     * focus it again ("smart auto focus"). XGIMI's own calibration screens turn the first off while they run.
     */
    static final class Moves {
        boolean keystone, focus;
    }

    /** Now, or null where it can't be read. Blocks briefly: not on the UI thread. */
    static Moves moves() {
        if (!PLAY_6) return null;
        try {
            Object motion = platform("com.xgimi.gmpf.api.MotionDetectionManager");
            Moves m = new Moves();
            m.keystone = (Boolean) call(motion, "getAccTriggerAK");
            m.focus = (Boolean) call(motion, "getAngTriggerAF");
            return m;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            AppLog.w("moves: " + e);
            return null;
        }
    }

    /** Keystone after a move, on or off. On, the picture is also squared now (XGIMI starts from a reset picture). */
    static Moves setMoveKeystone(Context c, boolean on) {
        try {
            Object motion = platform("com.xgimi.gmpf.api.MotionDetectionManager");
            motion.getClass().getMethod("setAccTriggerAK", boolean.class).invoke(motion, on);
            AppLog.w("keystone when moved " + (on ? "on" : "off"));
            if (on) autoKeystone(c);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            AppLog.w("keystone when moved: " + e);
        }
        return moves();
    }

    /** Focus after a move, on or off: the two switches XGIMI's "smart auto focus" sets together. */
    static Moves setMoveFocus(boolean on) {
        try {
            Object motion = platform("com.xgimi.gmpf.api.MotionDetectionManager");
            motion.getClass().getMethod("setAngTriggerAF", boolean.class).invoke(motion, on);
            Object focus = platform("com.xgimi.gmpf.api.ProjectorFocusManager");
            focus.getClass().getMethod("setPowerOnAFUseDistance", boolean.class).invoke(focus, on);
            AppLog.w("focus when moved " + (on ? "on" : "off"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            AppLog.w("focus when moved: " + e);
        }
        return moves();
    }

    /** HDMI game mode, as XGIMI's HDMI menu sets it; auto turns it on when XGIMI recognizes a console. */
    static final int GAME_OFF = 0, GAME_ON = 1, GAME_AUTO = 2;

    /** Now, or -1 where it can't be read: XGIMI keeps a type (1: auto) and a state (0: on, 1: off). Not on the UI thread. */
    static int gameMode() {
        if (!PLAY_6) return -1;
        try {
            Object display = platform("com.xgimi.gmpf.api.DisplayManager");
            Object prop = Class.forName("com.xgimi.gmpf.rp.GameModeProp").getConstructor().newInstance();
            if (!(Boolean) display.getClass().getMethod("getGameModeProp", prop.getClass()).invoke(display, prop)) return -1;
            int type = prop.getClass().getField("type").getInt(prop), state = prop.getClass().getField("state").getInt(prop);
            return type == 1 ? GAME_AUTO : state == 0 ? GAME_ON : GAME_OFF;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            AppLog.w("game mode: " + e);
            return -1;
        }
    }

    /** The calls XGIMI's own menu makes (turning it on applies the latency option again); returns what it reads after. */
    static int setGameMode(int mode) {
        try {
            Object display = platform("com.xgimi.gmpf.api.DisplayManager");
            java.lang.reflect.Method type = display.getClass().getMethod("setGameModeType", int.class);
            java.lang.reflect.Method state = display.getClass().getMethod("setGameModeState", int.class);
            if (mode == GAME_AUTO) {
                type.invoke(display, 1);
            } else {
                if (mode == GAME_ON) {
                    display.getClass().getMethod("setGameModeOption", int.class).invoke(display, call(display, "getGameModeOption"));
                }
                type.invoke(display, 0);
                state.invoke(display, mode == GAME_ON ? 0 : 1);
            }
            AppLog.w("game mode " + mode);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            AppLog.w("game mode: " + e);
        }
        return gameMode();
    }

    private static Object platform(String manager) throws ReflectiveOperationException {
        return Class.forName(manager).getMethod("getInstance").invoke(null);
    }

    private static Object call(Object o, String getter) throws ReflectiveOperationException {
        return o.getClass().getMethod(getter).invoke(o);
    }

    private static int mode(IBinder b) throws RemoteException {
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            in.writeInterfaceToken(SOUND);
            b.transact(GET_MODE, in, out, 0);
            out.readException();
            return out.readInt();
        } finally {
            in.recycle();
            out.recycle();
        }
    }

    private static void setMode(IBinder b, int mode) throws RemoteException {
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            in.writeInterfaceToken(SOUND);
            in.writeInt(mode);
            b.transact(SET_MODE, in, out, 0);
            out.readException();
        } finally {
            in.recycle();
            out.recycle();
        }
    }

    // MARK: - Settings and the system language

    /** XGIMI's Settings panel (a service the remote's key starts), opened in Chinese so it has nothing to switch. */
    static boolean openSettings(Context c) {
        Context app = c.getApplicationContext();
        Intent panel = new Intent("com.xgimi.settings.SETTINGS").setComponent(SETTINGS);
        if (app.getPackageManager().resolveService(panel, 0) == null) return false;
        WORK.execute(() -> {
            if (canKeep(app) && !Locale.SIMPLIFIED_CHINESE.equals(systemLocale()) && setLocale(Locale.SIMPLIFIED_CHINESE)) {
                lent = true;
                lentAt = SystemClock.elapsedRealtime();
                // The panel didn't take focus (it didn't open, or closed at once): take the language back anyway.
                MAIN.postDelayed(() -> {
                    if (focused && lent) keepLanguage(app);
                }, LEND_MS + 500);
            }
            try {
                app.startForegroundService(panel);
            } catch (IllegalStateException | SecurityException e) {
                AppLog.w("couldn't open XGIMI's settings: " + e.getMessage());
            }
        });
        return true;
    }

    /**
     * One of our windows (home screen, settings) got or lost focus. Back from the panel we opened in Chinese, the
     * language is ours again. Not when XGIMI switched it itself (the remote's Settings key): its notice closes the
     * panel, and taking the language back would bring the notice back on every press.
     */
    static void focusChanged(Context c, boolean hasFocus) {
        focused = hasFocus;
        if (!HomeMode.XGIMI || !hasFocus) return;
        if (!lent) heal(c.getApplicationContext());
        else if (SystemClock.elapsedRealtime() - lentAt >= LEND_MS) keepLanguage(c);
        homeCheck(c.getApplicationContext());
    }

    /** XGIMI Home stays off (HomeMode.xgimiHomeCheck): at most once a minute, when our windows get focus. */
    private static void homeCheck(Context app) {
        long now = SystemClock.elapsedRealtime();
        if (homeCheckedAt != 0 && now - homeCheckedAt < HOME_CHECK_MS) return;
        homeCheckedAt = now;
        CHECK.execute(() -> HomeMode.xgimiHomeCheck(app));
    }

    /** One of our screens goes to the background (an app is opening): the app gets our language. */
    static void paused(Context c) {
        if (HomeMode.XGIMI) keepLanguage(c);
    }

    private static void keepLanguage(Context c) {
        Context app = c.getApplicationContext();
        WORK.execute(() -> {
            if (!canKeep(app)) {
                heal(app);
                return;
            }
            lent = false;
            Locale want = Ui.locale(), now = systemLocale();
            if (now != null && !now.getLanguage().equals(want.getLanguage())) setLocale(want);
        });
    }

    /**
     * XGIMI force-stops apps a few seconds after they leave the screen, so the check after an update or a boot may
     * never have run, and the permissions it grants may be missing. Asked once per process, in our mode.
     */
    private static void heal(Context app) {
        if (verifyAsked) return;
        WORK.execute(() -> {
            boolean all = granted(app) && app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (verifyAsked || all || !HomeMode.FREE.equals(HomeMode.state(app).optString("mode"))) return;
            verifyAsked = true;
            HomeMode.verify(app);
        });
    }

    /** Only in our mode, once the switch has let this app change the system configuration (HomeMode). */
    private static boolean canKeep(Context app) {
        return HomeMode.XGIMI && granted(app) && HomeMode.FREE.equals(HomeMode.state(app).optString("mode"));
    }

    private static boolean granted(Context app) {
        return app.checkSelfPermission(Manifest.permission.CHANGE_CONFIGURATION) == PackageManager.PERMISSION_GRANTED
                && Settings.System.canWrite(app);
    }

    private static Object activityManager() throws ReflectiveOperationException {
        return Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
    }

    private static Locale systemLocale() {
        try {
            Object am = activityManager();
            Configuration config = (Configuration) Class.forName("android.app.IActivityManager")
                    .getMethod("getConfiguration").invoke(am);
            return config.getLocales().isEmpty() ? null : config.getLocales().get(0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            AppLog.w("couldn't read the system language: " + e);
            return null;
        }
    }

    /** The system language, as Settings sets it (Android has no public call for it). */
    private static boolean setLocale(Locale locale) {
        try {
            Object am = activityManager();
            Class<?> iam = Class.forName("android.app.IActivityManager");
            Configuration config = (Configuration) iam.getMethod("getConfiguration").invoke(am);
            config.setLocales(new LocaleList(locale));
            Configuration.class.getField("userSetLocale").setBoolean(config, true);
            iam.getMethod("updatePersistentConfiguration", Configuration.class).invoke(am, config);
            AppLog.w("system language: " + locale.toLanguageTag());
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            AppLog.w("couldn't set the system language: " + e);
            return false;
        }
    }
}
