package io.freemytv;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Original ⇄ ours. "free": our home screen and screensaver, and the apps in the lists below turned off
 * (pm disable-user: still installed, back with one button). "original": everything as it came from the factory.
 *
 * It all goes through the TV's own ADB (Adb, 127.0.0.1:5555), which you allow once on the TV. The switches run as
 * background jobs (ModeJob) in the main process; the settings screen (the :home process) asks for them and follows the
 * state file. Only the TV itself asks for switches: nothing here listens to the network.
 */
final class HomeMode {
    private HomeMode() {}

    static final String PKG = "io.freemytv";
    static final String HOME_ACTIVITY = PKG + "/" + PKG + ".HomeActivity";
    static final String SETTINGS_ACTIVITY = PKG + "/" + PKG + ".SettingsActivity";
    /** Our screensaver: on Android 11 the copy that runs in the main process (ScreensaverAlone, #10). */
    static final String DREAM = PKG + "/" + PKG + (Build.VERSION.SDK_INT == 30 ? ".ScreensaverAlone" : ".Screensaver");
    static final String FREE = "free", ORIGINAL = "original";

    /** Classic Android TV's home screen; its recommendations service crashes on the screensaver while it's off (#11). */
    static final String TV_LAUNCHER = "com.google.android.tvlauncher";
    static final String TV_RECOMMENDATIONS = "com.google.android.tvrecommendations";
    /** Google TV's home screen: the fallback when the TV's own one wasn't recorded. */
    static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity";
    /**
     * Google TV has a RecoveryActivity that would take over the home screen without its launcher. Older Android TV
     * (Android 9) has the same setup app without a home screen: there it is left alone.
     */
    static final String GOOGLE_SETUP = "com.google.android.tungsten.setupwraith";

    /**
     * Turned off in our mode, when the TV has them: Google's extras. The TV's own home screen and screensaver are added
     * at switch time. Never on XGIMI projectors or the Rokid Station (see targets).
     */
    static final String[] DEBLOAT = {
        "com.google.android.play.games", "com.google.android.youtube.tvmusic",        // Play Games, YouTube Music
    };

    /**
     * XGIMI projectors: their apps all run as the shared system UID, which also owns every saved Wi-Fi network, so
     * only XGIMI Home is turned off, and PRIVACY when asked for (Android keeps networks saved by a shared UID: their
     * creator matches no package).
     * Its chooser (xgimiframeworkui) opens XGIMI Home whenever it is on, and answers the HOME query itself, so the
     * stock home screen is named here. Only in the XGIMI build (Config.DEVICE), on an XGIMI.
     */
    static final boolean XGIMI = Config.DEVICE.equals("xgimi") && "XGIMI".equalsIgnoreCase(Build.BRAND);
    private static final String XGIMI_HOME = "com.xgimi.home/com.xgimi.module.cellview.home.ui.HomeActivity";

    /**
     * Rokid Station (the box for Rokid's AR glasses): its ROM refuses `pm disable-user` for every system app, so nothing
     * is turned off; the app only takes the home screen. Rokid's launcher stays on and keeps HOME away from us through
     * plugging the glasses and a reboot (tested). Its apps share the system UID. Only in the Rokid build
     * (Config.DEVICE), on a Rokid.
     */
    static final boolean ROKID = Config.DEVICE.equals("rokid") && "Rokid".equalsIgnoreCase(Build.BRAND);

    /** The voice assistant, only with disable_assistant=true in config.properties (the mic button stops working). */
    static final String[] ASSISTANT = {"com.google.android.katniss", "com.android.hotwordenrollment.okgoogle"};

    /**
     * Telemetry, only with disable_telemetry=true in config.properties (never the default). XGIMI's run as the shared
     * system UID, so the Wi-Fi-owner check can't see them: each must pass note 01's check by hand first. Not xrmservice
     * (the resource manager), iot, xgimiiotserver or msgcenter (the phone app's push channel).
     */
    static final String[] PRIVACY = {
        "com.xgimi.datareporter", "com.xgimi.bugreportsender",                       // XGIMI usage reports, bug reports
        "com.xgimi.adservice", "com.xgimi.minitvfactory",                            // ads, factory test
        "com.xgimi.newappmarket",                                                     // app market: posts the installed apps
    };

    /**
     * Never turned off, not even by mistake (Google services, the store, the remote, keyboard, settings, system UI).
     * Checked before every disable.
     */
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.apps.mediashell",
        "com.google.android.tv.remote.service", "com.google.android.tv.googletvremotepairer",
        "com.google.android.inputmethod.latin", "com.google.android.webview", "com.android.tv.settings",
        "com.android.systemui", "android", PKG,
        // Rokid Station (Amlogic): launcher, cursor and quick menu, system config, settings, box and glasses updates,
        // casting, setup, voice, gateway, licence page
        "com.rokid.glass.er.launcher", "com.rokid.glass.er.assistserver", "com.rokid.sysconfig", "com.rokid.erbox.settings",
        "com.rokid.glass.ota", "com.rokid.glass.firmware.upgrade", "com.rokid.erbox.projector", "com.rokid.erbox.provision",
        "com.rokid.os.voice", "com.rokid.vgateway", "com.rokid.erbox.webview", "com.droidlogic", "com.droidlogic.miracast"));

    /** One switch at a time (jobs can overlap: a boot check and a button press). */
    private static final Object RUN = new Object();
    private static final Object FILE_LOCK = new Object();

    // MARK: - Requests (from any process)

    /** Asks the main process to switch (the settings screen calls this). */
    static void request(Context c, String mode) {
        ModeJob.schedule(c, ModeJob.APPLY, mode, 0);
    }

    /** Our home screen crashed 3 times in 5 minutes: give the home screen back to the TV's own (asked by :home). */
    static void requestRevertHome(Context c) {
        ModeJob.schedule(c, ModeJob.REVERT, null, 0);
    }

    /** After the TV boots (and after a TV update): wait for the system to settle and check our mode. */
    static void bootCheck(Context c) {
        ModeJob.schedule(c, ModeJob.VERIFY, null, 45_000);
    }

    /** After an update of this app: complete our mode if anything is missing. */
    static void verify(Context c) {
        ModeJob.schedule(c, ModeJob.VERIFY, null, 0);
    }

    /** The remote buttons' accessibility service of older builds (other brands' TVs), long or short form. */
    private static final Set<String> OLD_REMOTE_SERVICE = new HashSet<>(Arrays.asList(
            PKG + "/" + PKG + ".RemoteKeyService", PKG + "/.RemoteKeyService"));
    private static final Pattern SERVICE = Pattern.compile("[\\w.]+/[\\w.$]+");

    /**
     * Original takes an older build's remote buttons service out of the enabled accessibility services, if it is still
     * listed, keeping the others (like TalkBack); accessibility goes back off if that build found it off.
     */
    private static void dropOldRemoteService(Context c, Adb adb) throws IOException {
        String current = adb.shell("settings get secure enabled_accessibility_services").trim();
        if (current.equals("null")) current = "";
        List<String> others = new ArrayList<>();
        boolean listed = false;
        for (String one : current.split(":")) {
            if (one.isEmpty()) continue;
            if (OLD_REMOTE_SERVICE.contains(one)) {
                listed = true;
                continue;
            }
            // Only plain component names go back into the shell command; anything else is left alone.
            if (!SERVICE.matcher(one).matches()) {
                AppLog.w("accessibility services left as they are: unexpected entry");
                return;
            }
            others.add(one);
        }
        if (!listed) return;
        adb.shell(others.isEmpty() ? "settings delete secure enabled_accessibility_services"
                : "settings put secure enabled_accessibility_services '" + String.join(":", others) + "'");
        if (others.isEmpty() && state(c).optBoolean("accessibilityWasOff")) {
            adb.shell("settings put secure accessibility_enabled 0");
        }
        AppLog.w("old remote buttons service taken out");
    }

    // MARK: - Switches (main process, from ModeJob)

    static void applyNow(Context c, String mode) {
        synchronized (RUN) {
            if (FREE.equals(mode)) applyFree(c); else applyOriginal(c);
        }
    }

    private static void applyFree(Context c) {
        JSONObject s = state(c);
        try (Adb adb = connect(c, s)) {
            // The TV's own home screen and screensaver, recorded once (before ours replace them) so "original" restores
            // exactly what was there, on any brand.
            String home = originalHome(s);
            if (home.isEmpty() || home.startsWith(PKG + "/")) {
                String found = currentHome(adb);
                home = found != null ? realHome(found) : XGIMI ? XGIMI_HOME : "";
            }
            String dream = originalDream(s);
            if (dream.isEmpty()) dream = defaultDream(adb);
            JSONObject latest = state(c);
            latest.put("originalHome", home).put("originalDream", dream);
            String saver = adb.shell("settings get secure screensaver_components").trim();
            if (!saver.contains(PKG + "/")) latest.put("originalScreensaver", safeComponents(saver));
            save(c, latest);
            // Turning off an app makes Android delete the Wi-Fi networks it saved (#14), and Original can't bring them
            // back. The TV's first setup usually saved the network: then stop here, before anything changes.
            Set<String> owners = wifiOwners(adb);
            boolean setupHome = setupIsHome(adb);
            if ((setupHome && owners.contains(GOOGLE_SETUP)) || owners.contains(packageOf(home))) {
                throw new IOException(Ui.tr("a configuração inicial da TV salvou o Wi-Fi, e desligá-la apagaria essas redes. "
                        + "Em Configurações → Rede e Internet, esqueça a rede e conecte de novo (as fora de alcance não aparecem "
                        + "lá: o Claude Code apaga pelo computador) e tente outra vez.",
                        "the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. "
                        + "In Settings → Network & Internet, forget the network and connect again (networks out of range don't "
                        + "show there: Claude Code can remove them from your computer), then try once more.",
                        "la configuración inicial del TV guardó el Wi-Fi, y desactivarla borraría esas redes. "
                        + "En Ajustes → Red e Internet, olvida la red y vuelve a conectarte (las fuera de alcance no aparecen "
                        + "ahí: Claude Code las borra desde la computadora) y vuelve a intentarlo."));
            }
            if (metrics(latest, ORIGINAL) == null && !home.isEmpty()) {
                busy(c, Ui.tr("Medindo o Início original…", "Measuring the original home screen…"));
                measureHomeTime(c, adb, ORIGINAL);
                measureMemory(c, adb, ORIGINAL);
            }
            busy(c, Ui.tr("Trocando o Início e a tela de descanso…", "Switching the home screen and screensaver…"));
            Watchdog.clear(c);
            if (XGIMI) {
                xgimiGrants(adb, true);
                adbSecure(c, adb);
            }
            if (ROKID) rokidGrants(adb, true);
            check(adb.shell("cmd package set-home-activity " + HOME_ACTIVITY), "Success",
                    Ui.tr("o Android não aceitou o novo Início", "Android didn't accept the new home screen"));
            adb.shell("settings put secure screensaver_components " + DREAM);
            JSONObject started = state(c);
            started.put("mode", FREE).put("homeReverted", false).put("setupHome", setupHome);
            save(c, started);
            List<String> targets = targets(home, dream, setupHome);
            Set<String> disabled = new HashSet<>(stringList(state(c), "disabled"));
            Set<String> present = packages(adb, "");
            Set<String> keptOff = new HashSet<>(stringList(state(c), "keptOff"));
            // Off now and not on our list: the person turned it off, unless an earlier install of the app did (its
            // list lives in the TV's settings too, since an uninstall erases our state file).
            Set<String> ours = recorded(adb);
            for (String pkg : packages(adb, "-d")) {
                if (targets(home, dream, true).contains(pkg) && !disabled.contains(pkg) && !ours.contains(pkg)) keptOff.add(pkg);
            }
            keptOff.removeAll(ours);
            JSONObject before = state(c);
            before.put("keptOff", new JSONArray(sorted(keptOff)));
            save(c, before);
            Set<String> ownPkgs = switchTime(home, dream);
            Map<String, Boolean> inputs = new HashMap<>();
            int done = 0;
            for (String pkg : targets) {
                done++;
                busy(c, Ui.tr("Desligando apps (", "Turning off apps (") + done + "/" + targets.size() + ")…");
                if (PROTECTED.contains(pkg) || !present.contains(pkg) || owners.contains(pkg) || keptOff.contains(pkg)) continue;
                if (!ownPkgs.contains(pkg) && tvInput(adb, pkg, inputs)) continue;
                String out = adb.shell("pm disable-user --user 0 " + pkg);
                if (out.contains("disabled-user")) {
                    disabled.add(pkg);
                    JSONObject now = state(c);
                    now.put("disabled", new JSONArray(sorted(disabled)));
                    save(c, now);
                } else {
                    AppLog.w("could not turn off " + pkg + ": " + out.trim());
                }
            }
            protectedBackOn(adb, disabled, present);
            record(adb, disabled);
            s = state(c);
            s.put("mode", FREE).put("since", System.currentTimeMillis()).put("homeReverted", false)
                    .put("disabled", new JSONArray(sorted(disabled))).put("setupHome", setupHome);
            save(c, s);
            notice(c, Ui.tr(Config.APP_NAME + " ligado: Início e tela de descanso novos, " + disabled.size() + " apps desligados.",
                    Config.APP_NAME + " is on: new home screen and screensaver, " + disabled.size() + " apps turned off."));
            busy(c, Ui.tr("Medindo o " + Config.APP_NAME + "…", "Measuring " + Config.APP_NAME + "…"));
            measureHomeTime(c, adb, FREE);
            done(c, null);
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            ModeJob.schedule(c, ModeJob.MEMORY, FREE, 120_000);
        } catch (Exception e) {
            done(c, Ui.tr("Não deu para ligar o " + Config.APP_NAME + ": ", "Couldn't turn on " + Config.APP_NAME + ": ") + (Ui.ru ? Russian.text(e.getMessage() == null ? "" : e.getMessage()) : e.getMessage()));
        }
    }

    private static void applyOriginal(Context c) {
        JSONObject s = state(c);
        try (Adb adb = connect(c, s)) {
            busy(c, Ui.tr("Religando os apps…", "Turning the apps back on…"));
            String home = originalHome(s);
            Set<String> toEnable = new HashSet<>(stringList(s, "disabled"));
            for (String pkg : targets(home, originalDream(s), true)) {
                if (!Arrays.asList(DEBLOAT).contains(pkg) && !Arrays.asList(ASSISTANT).contains(pkg)
                        && !Arrays.asList(PRIVACY).contains(pkg)) toEnable.add(pkg);
            }
            toEnable.removeAll(stringList(s, "keptOff"));
            Set<String> present = packages(adb, "");
            int done = 0;
            List<String> failed = new ArrayList<>();
            for (String pkg : sorted(toEnable)) {
                done++;
                busy(c, Ui.tr("Religando apps (", "Turning apps back on (") + done + "/" + toEnable.size() + ")…");
                if (!present.contains(pkg)) continue;
                // Checked: whatever didn't come back stays on the list (and in the notice); nothing disappears silently.
                String out = adb.shell("pm enable " + pkg);
                if (!out.contains("new state: enabled")) failed.add(pkg);
            }
            record(adb, new HashSet<>(failed));
            busy(c, Ui.tr("Devolvendo o Início original…", "Restoring the original home screen…"));
            boolean homeOk = restoreHome(adb, home);
            if (XGIMI) xgimiGrants(adb, false);
            if (ROKID) rokidGrants(adb, false);
            restoreScreensaver(adb, s);
            try {
                dropOldRemoteService(c, adb);
            } catch (IOException e) {
                AppLog.w("old remote buttons service: " + e.getMessage());
            }
            s = state(c);
            s.put("mode", ORIGINAL).put("since", System.currentTimeMillis()).put("homeReverted", false)
                    .put("disabled", new JSONArray(failed)).remove("keptOff");
            save(c, s);
            if (failed.isEmpty() && homeOk) {
                notice(c, Ui.tr("Modo original: Início, tela de descanso e apps de fábrica de volta.",
                        "Original mode: the factory home screen, screensaver and apps are back."));
            } else {
                List<String> problems = new ArrayList<>(failed);
                if (!homeOk) problems.add(Ui.tr("o Início original", "the original home screen"));
                notice(c, Ui.tr("Modo original, mas não consegui religar: ", "Original mode, but I couldn't turn back on: ")
                        + String.join(", ", problems) + ".");
            }
            busy(c, Ui.tr("Medindo o Início original…", "Measuring the original home screen…"));
            measureHomeTime(c, adb, ORIGINAL);
            done(c, null);
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            ModeJob.schedule(c, ModeJob.MEMORY, ORIGINAL, 120_000);
        } catch (Exception e) {
            done(c, Ui.tr("Não deu para voltar ao original: ", "Couldn't go back to the original: ") + (Ui.ru ? Russian.text(e.getMessage() == null ? "" : e.getMessage()) : e.getMessage()));
        }
    }

    /**
     * XGIMI: lets this app set the system language (Xgimi keeps it in the app's while XGIMI Settings turns it to
     * Chinese) and search for Bluetooth devices (Android 10+ ties that to location, never read here), or takes that
     * back. The language stays as it is; XGIMI Settings switches it again when it opens.
     */
    private static void xgimiGrants(Adb adb, boolean on) {
        try {
            String pm = "pm " + (on ? "grant " : "revoke ") + PKG + " android.permission.";
            adb.shell(pm + "CHANGE_CONFIGURATION");
            adb.shell(pm + "ACCESS_FINE_LOCATION");
            adb.shell("appops set " + PKG + " WRITE_SETTINGS " + (on ? "allow" : "default"));
            adb.shell("appops write-settings");   // app-ops reach the disk lazily: a restart soon after would drop the set
        } catch (IOException e) {
            AppLog.w("XGIMI permissions: " + e.getMessage());
        }
    }

    /**
     * XGIMI: ro.adb.secure, for the Home Screen page. "0" lets any computer on the network into the projector's ADB
     * without asking (no adb_keys at all; note 01); the page says so while it is.
     */
    private static void adbSecure(Context c, Adb adb) {
        try {
            String v = adb.shell("getprop ro.adb.secure").trim();
            JSONObject s = state(c);
            s.put("adbSecure", v);
            save(c, s);
        } catch (IOException | JSONException e) {
            AppLog.w("ro.adb.secure: " + e.getMessage());
        }
    }

    /**
     * XGIMI: XGIMI Home turned back on behind our back (on global ROMs xrmservice does it on some remote keys; note 09)
     * goes off again. Asked when our windows get focus (Xgimi), since verify can't run from a broadcast on this ROM.
     * Not during a switch: Original turns it on itself.
     */
    static void xgimiHomeCheck(Context app) {
        JSONObject s = state(app);
        String home = packageOf(XGIMI_HOME);
        long busyAt = s.optLong("busyAt"), now = SystemClock.elapsedRealtime();
        boolean busy = !s.optString("busy").isEmpty() && now >= busyAt && now - busyAt < 10 * 60_000L;
        if (!XGIMI || busy || !FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted")
                || !stringList(s, "disabled").contains(home)) return;
        try (Adb adb = Adb.connect(app, 10_000, null)) {
            if (packages(adb, "-d").contains(home)) return;
            String out = adb.shell("pm disable-user --user 0 " + home);
            AppLog.w(out.contains("disabled-user") ? "xgimi: stock home came back, turned off again"
                    : "xgimi: stock home came back, could not turn it off: " + out.trim());
        } catch (IOException e) {
            AppLog.w("xgimi stock home check: " + e.getMessage());
        }
    }

    /** Rokid Station: lets this app search for Bluetooth devices (Android 10+ ties that to location, never read here), or takes that back. */
    private static void rokidGrants(Adb adb, boolean on) {
        try {
            adb.shell("pm " + (on ? "grant " : "revoke ") + PKG + " android.permission.ACCESS_FINE_LOCATION");
        } catch (IOException e) {
            AppLog.w("Rokid permissions: " + e.getMessage());
        }
    }

    /** The recorded home screen; without one, Google TV's; without that, the system picks (we leave the role). */
    private static boolean restoreHome(Adb adb, String home) throws IOException {
        if (home.isEmpty() && adb.shell("pm path " + packageOf(GOOGLE_HOME)).contains("package:")) home = GOOGLE_HOME;
        if (!home.isEmpty()) {
            adb.shell("pm enable " + packageOf(home));
            if (adb.shell("cmd package set-home-activity '" + home + "'").contains("Success")) return true;
        }
        adb.shell("cmd role remove-role-holder --user 0 android.app.role.HOME " + PKG);
        return !homeHolder(adb).contains(PKG);
    }

    /** Safety net: our home screen crashed 3 times in a row. The home screen and the screensaver go back; the rest stays. */
    static void revertHomeNow(Context app) {
        synchronized (RUN) {
            JSONObject s = state(app);
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                boolean ours = homeHolder(adb).contains(PKG);
                if (!ours && (!FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted"))) return;
                String home = originalHome(s);
                List<String> keptOff = stringList(s, "keptOff");
                if (!keptOff.contains(GOOGLE_SETUP)) adb.shell("pm enable " + GOOGLE_SETUP);
                if (!restoreHome(adb, home)) {
                    AppLog.w("could not give the home screen back");
                    return;
                }
                String homePkg = packageOf(home), dreamPkg = packageOf(originalDream(s));
                if (dreamPkg != null && !keptOff.contains(dreamPkg)) adb.shell("pm enable " + dreamPkg);
                JSONArray kept = new JSONArray();
                Set<String> stillOff = new HashSet<>();
                for (String pkg : stringList(s, "disabled")) {
                    if (!pkg.equals(GOOGLE_SETUP) && !pkg.equals(homePkg) && !pkg.equals(dreamPkg)) {
                        kept.put(pkg);
                        stillOff.add(pkg);
                    }
                }
                record(adb, stillOff);
                // The screensaver first: if that fails, the revert isn't marked done and the next try does it again.
                restoreScreensaver(adb, s);
                s.put("homeReverted", true).put("disabled", kept);
                save(app, s);
                notice(app, Ui.tr("O Início do " + Config.APP_NAME + " caiu 3 vezes seguidas; voltei para o Início original.",
                        Config.APP_NAME + "'s home screen crashed 3 times in a row; I switched back to the original one."));
                adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            } catch (Exception e) {
                AppLog.w("could not give the home screen back: " + e.getMessage());
            }
        }
    }

    /**
     * In our mode, checks that everything is still as we left it (the listed apps off, our home screen and
     * screensaver) and completes whatever is missing, with a notice. Runs after the TV boots (TV updates can turn
     * things back on) and after an update of this app.
     */
    static void verifyNow(Context app) {
        synchronized (RUN) {
            JSONObject s = state(app);
            String fingerprint = Build.FINGERPRINT;
            boolean updated = !fingerprint.equals(s.optString("fingerprint", fingerprint));
            try {
                s.put("fingerprint", fingerprint);
                s.remove("busy");
                save(app, s);
            } catch (JSONException ignored) {}
            if (!FREE.equals(s.optString("mode"))) return;
            List<String> fixed = new ArrayList<>();
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                Set<String> present = packages(adb, "");
                Set<String> disabledNow = packages(adb, "-d");
                Set<String> owners = wifiOwners(adb);
                boolean reverted = s.optBoolean("homeReverted");
                String home = originalHome(s);
                Set<String> wanted = new HashSet<>(stringList(s, "disabled"));
                String dream = originalDream(s);
                for (String pkg : targets(home, dream, s.optBoolean("setupHome", true))) {
                    if (!reverted || !(pkg.equals(packageOf(home)) || pkg.equals(GOOGLE_SETUP) || pkg.equals(packageOf(dream)))) {
                        wanted.add(pkg);
                    }
                }
                Set<String> disabled = new HashSet<>(stringList(s, "disabled"));
                // Off before our mode: turned off again if a TV update brings one back, but never handed to
                // Original to turn on.
                List<String> keptOff = stringList(s, "keptOff");
                Set<String> ownPkgs = switchTime(home, dream);
                Map<String, Boolean> inputs = new HashMap<>();
                protectedBackOn(adb, disabled, present);
                for (String pkg : sorted(wanted)) {
                    if (PROTECTED.contains(pkg) || !present.contains(pkg) || owners.contains(pkg)) continue;
                    if (disabledNow.contains(pkg)) {
                        if (!keptOff.contains(pkg)) disabled.add(pkg);
                        continue;
                    }
                    if (!ownPkgs.contains(pkg) && tvInput(adb, pkg, inputs)) continue;
                    if (adb.shell("pm disable-user --user 0 " + pkg).contains("disabled-user")) {
                        if (!keptOff.contains(pkg)) disabled.add(pkg);
                        fixed.add(pkg);
                    }
                }
                if (XGIMI) {
                    xgimiGrants(adb, true);
                    adbSecure(app, adb);
                }
                if (ROKID) rokidGrants(adb, true);
                if (!reverted && !homeHolder(adb).contains(PKG)) {
                    adb.shell("cmd package set-home-activity " + HOME_ACTIVITY);
                    fixed.add(Ui.tr("o Início", "the home screen"));
                }
                // Whole component names: ".ScreensaverAlone" (Android 11) contains ".Screensaver" (12 and newer).
                if (!reverted && !Arrays.asList(adb.shell("settings get secure screensaver_components").trim().split(",")).contains(DREAM)) {
                    adb.shell("settings put secure screensaver_components " + DREAM);
                    fixed.add(Ui.tr("a tela de descanso", "the screensaver"));
                }
                record(adb, disabled);
                JSONObject latest = state(app);
                latest.put("disabled", new JSONArray(sorted(disabled)));
                save(app, latest);
            } catch (Exception e) {
                notice(app, Ui.tr("Não consegui conferir o " + Config.APP_NAME + ": ", "Couldn't check " + Config.APP_NAME + ": ") + (Ui.ru ? Russian.text(e.getMessage() == null ? "" : e.getMessage()) : e.getMessage()));
                return;
            }
            if (!fixed.isEmpty()) {
                notice(app, (updated ? Ui.tr("A atualização da TV religou ", "The TV update turned back on ")
                        : Ui.tr("Desliguei o que faltava: ", "Turned off what was missing: ")) + String.join(", ", fixed) + ".");
            } else if (updated) {
                notice(app, Ui.tr("A TV foi atualizada; o " + Config.APP_NAME + " continua igual.", "The TV was updated; " + Config.APP_NAME + " is still on."));
            }
        }
    }

    /**
     * Turned off by an older version, protected since: back on. After a reinstall only the record
     * in the TV's settings knows about it, so both lists count. One that won't come back stays in `disabled`, and so in
     * the record, for Original and the next check.
     */
    private static void protectedBackOn(Adb adb, Set<String> disabled, Set<String> present) throws IOException {
        Set<String> ours = new HashSet<>(disabled);
        ours.addAll(recorded(adb));
        for (String pkg : sorted(ours)) {
            if (!pkg.matches("[\\w.]+")) {   // it goes into a shell command
                AppLog.w("turned-off list: skipped an unexpected name " + JSONObject.quote(pkg));
                continue;
            }
            if (!PROTECTED.contains(pkg)) continue;
            if (adb.shell("pm enable " + pkg).contains("new state: enabled")) {
                disabled.remove(pkg);
                AppLog.w("turned back on " + pkg);
            } else if (present.contains(pkg)) {
                disabled.add(pkg);
            }
        }
    }

    /**
     * What to turn off: the TV's own home screen and screensaver, Google TV's recovery home (when setupHome: the setup
     * app is one of the TV's home screens), and the lists above. Nothing on Rokid: its ROM wouldn't let us, and the
     * generic list would still reach apps the person installed there (YouTube Music).
     */
    private static List<String> targets(String home, String dream, boolean setupHome) {
        List<String> out = new ArrayList<>();
        if (ROKID) return out;
        String homePkg = packageOf(home);
        if (homePkg != null && !homePkg.equals(PKG)) out.add(homePkg);
        if (Config.DISABLE_TELEMETRY) out.addAll(Arrays.asList(PRIVACY));
        if (XGIMI) return out;
        if (TV_LAUNCHER.equals(homePkg)) out.add(TV_RECOMMENDATIONS);
        if (setupHome) out.add(GOOGLE_SETUP);
        String dreamPkg = packageOf(dream);
        if (dreamPkg != null && !dreamPkg.equals(PKG) && !dreamPkg.equals(homePkg)) out.add(dreamPkg);
        out.addAll(Arrays.asList(DEBLOAT));
        if (Config.DISABLE_ASSISTANT) out.addAll(Arrays.asList(ASSISTANT));
        return out;
    }

    /** What a switch adds besides the lists: the TV's own home screen and screensaver, and Google TV's setup app. */
    private static Set<String> switchTime(String home, String dream) {
        Set<String> out = new HashSet<>(Collections.singleton(GOOGLE_SETUP));
        if (packageOf(home) != null) out.add(packageOf(home));
        if (packageOf(dream) != null) out.add(packageOf(dream));
        return out;
    }

    private static final String TV_INPUT_HARDWARE = "android.permission.TV_INPUT_HARDWARE";

    /**
     * The input guard (note 09, C5): a list entry whose own requested permissions name TV_INPUT_HARDWARE runs a TV
     * input (HDMI, tuner) and is never turned off, whatever list it is on. Asked once per switch per package
     * (`seen` holds that switch's answers). Only that package's blocks count, not its shared UID's.
     */
    private static boolean tvInput(Adb adb, String pkg, Map<String, Boolean> seen) throws IOException {
        Boolean known = seen.get(pkg);
        if (known != null) return known;
        // Not a plain package name: never put into a shell command, and left on.
        boolean input = !pkg.matches("[\\w.]+") || requestsInput(adb.shell("dumpsys package " + pkg), pkg);
        seen.put(pkg, input);
        if (input) AppLog.w("input guard: " + pkg + " requests TV_INPUT_HARDWARE, left on");
        return input;
    }

    private static final Pattern PACKAGE_BLOCK = Pattern.compile("^Package \\[([\\w.]+)\\]");

    /** Whether a "requested permissions:" block inside pkg's own "Package [pkg]" block names TV_INPUT_HARDWARE. */
    private static boolean requestsInput(String dumpsys, String pkg) {
        String current = null;   // the package block we're in (null: another section, like SharedUser)
        int block = -1;          // the indent of the requested permissions header we're under, or -1
        for (String line : dumpsys.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            int indent = line.indexOf(t.charAt(0));
            if (block >= 0 && indent <= block) block = -1;
            Matcher m = PACKAGE_BLOCK.matcher(t);
            if (m.find()) current = m.group(1);
            else if (indent == 0 || t.startsWith("SharedUser [")) current = null;
            if (t.equals("requested permissions:")) {
                if (pkg.equals(current)) block = indent;
            } else if (block >= 0 && (t.equals(TV_INPUT_HARDWARE) || t.startsWith(TV_INPUT_HARDWARE + ":"))) {
                return true;
            }
        }
        return false;
    }

    /** The home screen in use now ("package/activity"): the HOME role holder, among the TV's home screens. */
    private static String currentHome(Adb adb) throws IOException {
        String holder = homeHolder(adb);
        if (holder.isEmpty() || holder.contains(PKG)) return null;
        String list = adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME");
        for (String line : list.split("\n")) {
            line = line.trim();
            if (line.startsWith(holder + "/")) return line;
        }
        return null;
    }

    /**
     * The package that is the home screen now, or "". Some Android 12 TVs have no `cmd role get-role-holders` (it
     * answers "Unknown command"), so then we ask which activity opens HOME.
     */
    private static String homeHolder(Adb adb) throws IOException {
        String holder = adb.shell("cmd role get-role-holders --user 0 android.app.role.HOME").trim();
        if (holder.matches("[\\w.]+(;[\\w.]+)*")) return holder;
        String[] lines = adb.shell("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME").trim().split("\n");
        String component = lines[lines.length - 1].trim();
        // With no default home yet, the system's chooser answers ("android/...ResolverActivity"): unknown.
        return component.contains("/") && !component.startsWith("android/") ? packageOf(component) : "";
    }

    /** Whether Google TV's setup app is one of the home screens (its RecoveryActivity), so it must go off too. */
    private static boolean setupIsHome(Adb adb) throws IOException {
        return adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME").contains(GOOGLE_SETUP + "/");
    }

    /** Apps that saved a Wi-Fi network ("cname" in the Wi-Fi service's list of saved networks). */
    private static Set<String> wifiOwners(Adb adb) throws IOException {
        Set<String> owners = new HashSet<>();
        Matcher m = Pattern.compile("cname=([\\w.]+)").matcher(adb.shell("dumpsys wifi | grep -o 'cname=[^ ]*'"));
        while (m.find()) owners.add(m.group(1));
        return owners;
    }

    /** The recorded home screen of the TV, as {@link #realHome}. */
    private static String originalHome(JSONObject s) {
        return realHome(s.optString("originalHome", ""));
    }

    /**
     * Some Google TVs (Android 14) leave the HOME role with Google TV's recovery home, while launcherx is the one that
     * answers the Home button. Treat that as launcherx, or it stays on and "original" restores the recovery screen.
     */
    private static String realHome(String home) {
        return home.startsWith(GOOGLE_SETUP + "/") ? GOOGLE_HOME : safeComponent(home);
    }

    private static String originalDream(JSONObject s) {
        return safeComponent(s.optString("originalDream", ""));
    }

    // Nested classes ("Outer$Inner") are valid; every shell command that takes one quotes it, so $ stays literal.
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z][\\w.]*/[\\w.$]+");

    private static String safeComponent(String component) {
        return COMPONENT.matcher(component).matches() ? component : "";
    }

    private static String safeComponents(String components) {
        for (String one : components.split(",")) {
            if (safeComponent(one).isEmpty()) return "";
        }
        return components;
    }

    private static void restoreScreensaver(Adb adb, JSONObject s) throws IOException {
        String saver = safeComponents(s.optString("originalScreensaver", ""));
        if (saver.isEmpty()) adb.shell("settings delete secure screensaver_components");
        else adb.shell("settings put secure screensaver_components '" + saver + "'");
    }

    /** The system's default screensaver ("package/service"), or "". */
    private static String defaultDream(Adb adb) throws IOException {
        String d = adb.shell("settings get secure screensaver_default_component").trim();
        return d.contains("/") && !d.startsWith(PKG + "/") ? safeComponent(d) : "";
    }

    private static String packageOf(String component) {
        if (component == null || component.isEmpty()) return null;
        int slash = component.indexOf('/');
        return slash > 0 ? component.substring(0, slash) : component;
    }

    /** Packages the shell sees (all; "-d" = only the disabled ones). The app alone can't see the ones it doesn't declare. */
    private static Set<String> packages(Adb adb, String flags) throws IOException {
        Set<String> out = new HashSet<>();
        for (String line : adb.shell("pm list packages " + flags).split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8));
        }
        return out;
    }

    private static Adb connect(Context c, JSONObject s) throws IOException {
        busy(c, Ui.tr("Conectando…", "Connecting…"));
        Adb adb = Adb.connect(c, 120_000, () -> busy(c, Ui.tr("Aceite o aviso “Permitir depuração” na TV (marque “Sempre permitir”).",
                "Accept “Allow USB debugging?” on the TV (tick “Always allow”).")));
        if (!s.optBoolean("adbAuthorized")) {
            JSONObject latest = state(c);
            try { latest.put("adbAuthorized", true); } catch (JSONException ignored) {}
            save(c, latest);
        }
        return adb;
    }

    private static void check(String output, String expected, String error) throws IOException {
        if (!output.contains(expected)) throw new IOException(error + " (" + output.trim() + ")");
    }

    // MARK: - Measurements (without opening apps: memory and the home button)

    private static void measureHomeTime(Context c, Adb adb, String mode) throws IOException, JSONException {
        List<Integer> samples = new ArrayList<>();
        Pattern total = Pattern.compile("TotalTime: (\\d+)");
        for (int i = 0; i < 3; i++) {
            adb.shell("am start -W -n " + SETTINGS_ACTIVITY);
            SystemClock.sleep(500);
            Matcher m = total.matcher(adb.shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"));
            if (m.find()) samples.add(Integer.parseInt(m.group(1)));
            SystemClock.sleep(600);
        }
        adb.shell("am start -W -n " + SETTINGS_ACTIVITY);
        if (samples.isEmpty()) return;
        Collections.sort(samples);
        putMetric(c, mode, "homeMs", samples.get(samples.size() / 2));
    }

    /** Two minutes after a switch (the home screen settled, caches trimmed). */
    static void measureMemoryNow(Context c, String mode) {
        synchronized (RUN) {
            if (!mode.equals(state(c).optString("mode"))) return;
            try (Adb adb = Adb.connect(c, 10_000, null)) {
                measureMemory(c, adb, mode);
            } catch (Exception e) {
                AppLog.w("memory measurement failed: " + e.getMessage());
            }
        }
    }

    private static void measureMemory(Context c, Adb adb, String mode) throws IOException, JSONException {
        Matcher avail = Pattern.compile("MemAvailable:\\s+(\\d+) kB").matcher(adb.shell("cat /proc/meminfo"));
        if (avail.find()) putMetric(c, mode, "memAvailableMB", Long.parseLong(avail.group(1)) / 1024);
        long pss = 0;
        String home = packageOf(originalHome(state(c)));
        String[] processes = FREE.equals(mode) ? new String[] {PKG + ":home"}
                : home == null ? new String[0] : new String[] {home, home + ":coreservices"};
        Pattern total = Pattern.compile("TOTAL PSS:\\s+(\\d+)");
        for (String p : processes) {
            Matcher m = total.matcher(adb.shell("dumpsys meminfo " + p));
            if (m.find()) pss += Long.parseLong(m.group(1));
        }
        if (pss > 0) putMetric(c, mode, "homePssMB", pss / 1024);
    }

    private static void putMetric(Context c, String mode, String key, long value) throws JSONException {
        JSONObject s = state(c);
        JSONObject metrics = s.optJSONObject("metrics");
        if (metrics == null) metrics = new JSONObject();
        JSONObject m = metrics.optJSONObject(mode);
        if (m == null) m = new JSONObject();
        m.put(key, value).put("at", System.currentTimeMillis());
        metrics.put(mode, m);
        s.put("metrics", metrics);
        save(c, s);
    }

    private static JSONObject metrics(JSONObject s, String mode) {
        JSONObject metrics = s.optJSONObject("metrics");
        return metrics == null ? null : metrics.optJSONObject(mode);
    }

    // MARK: - State (a file shared by the two processes; atomic writes)

    static JSONObject state(Context c) {
        synchronized (FILE_LOCK) {
            File f = new File(c.getFilesDir(), "home-state.json");
            if (!f.exists()) return defaults();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] data = new byte[(int) f.length()];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                return defaults();
            }
        }
    }

    private static JSONObject defaults() {
        try {
            return new JSONObject().put("mode", ORIGINAL);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void save(Context c, JSONObject s) {
        synchronized (FILE_LOCK) {
            File dir = c.getFilesDir();
            File tmp = new File(dir, "home-state.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (IOException e) {
                AppLog.w("could not save the state: " + e.getMessage());
                return;
            }
            if (!tmp.renameTo(new File(dir, "home-state.json"))) AppLog.w("could not replace the state file");
        }
    }

    private static void busy(Context c, String text) {
        JSONObject s = state(c);
        try { s.put("busy", text).put("busyAt", SystemClock.elapsedRealtime()).put("error", ""); } catch (JSONException ignored) {}
        save(c, s);
    }

    private static void done(Context c, String error) {
        JSONObject s = state(c);
        s.remove("busy");
        try { s.put("error", error == null ? "" : error); } catch (JSONException ignored) {}
        save(c, s);
        if (error != null) {
            AppLog.w(error);
            notice(c, error);
        }
    }

    /** A notice for the home screen (a bubble) and the settings screen. Keeps the last 20. */
    static void notice(Context c, String text) {
        AppLog.w("notice: " + text);
        JSONObject s = state(c);
        JSONArray old = s.optJSONArray("notices");
        JSONArray list = new JSONArray();
        try {
            list.put(new JSONObject().put("at", System.currentTimeMillis()).put("text", text));
            if (old != null) for (int i = 0; i < old.length() && list.length() < 20; i++) list.put(old.get(i));
            s.put("notices", list);
        } catch (JSONException ignored) {}
        save(c, s);
    }

    // MARK: - Helpers

    static List<String> stringList(JSONObject s, String key) {
        List<String> out = new ArrayList<>();
        JSONArray a = s.optJSONArray(key);
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /**
     * What the app turned off, kept in the TV's own settings too: an uninstall or Clear data erases our state file,
     * and a new install must still tell its own work from what the person had turned off (#142).
     */
    private static final String RECORD = "free_my_tv_disabled";

    private static Set<String> recorded(Adb adb) {
        Set<String> out = new HashSet<>();
        try {
            String v = adb.shell("settings get global " + RECORD).trim();
            if (!v.isEmpty() && !v.equals("null")) for (String pkg : v.split(",")) if (pkg.matches("[\\w.]+")) out.add(pkg);
        } catch (IOException e) {
            AppLog.w("could not read the record of what we turned off: " + e.getMessage());
        }
        return out;
    }

    private static void record(Adb adb, Set<String> disabled) {
        List<String> list = new ArrayList<>();
        for (String pkg : sorted(disabled)) if (pkg.matches("[\\w.]+")) list.add(pkg);
        try {
            adb.shell(list.isEmpty() ? "settings delete global " + RECORD
                    : "settings put global " + RECORD + " " + String.join(",", list));
        } catch (IOException e) {
            AppLog.w("could not save the record of what we turned off: " + e.getMessage());
        }
    }

    private static List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        return list;
    }
}
