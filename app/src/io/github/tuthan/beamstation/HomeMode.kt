package io.github.tuthan.beamstation

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Original ⇄ ours. "free": our home screen and screensaver, and the apps in the lists below turned off
 * (pm disable-user: still installed, back with one button). "original": everything as it came from the factory.
 *
 * It all goes through the TV's own ADB (Adb, 127.0.0.1:5555), which you allow once on the TV. The switches run as
 * background jobs (ModeJob) in the main process; the settings screen (the :home process) asks for them and follows the
 * state file. Only the TV itself asks for switches: nothing here listens to the network.
 */
internal object HomeMode {
    const val PKG = "io.github.tuthan.beamstation"
    const val HOME_ACTIVITY = PKG + "/" + PKG + ".HomeActivity"
    const val SETTINGS_ACTIVITY = PKG + "/" + PKG + ".SettingsActivity"
    /** Our screensaver: on Android 11 the copy that runs in the main process (ScreensaverAlone, #10). */
    @JvmField val DREAM = PKG + "/" + PKG + (if (Build.VERSION.SDK_INT == 30) ".ScreensaverAlone" else ".Screensaver")
    const val FREE = "free"
    const val ORIGINAL = "original"

    /** Classic Android TV's home screen; its recommendations service crashes on the screensaver while it's off (#11). */
    const val TV_LAUNCHER = "com.google.android.tvlauncher"
    const val TV_RECOMMENDATIONS = "com.google.android.tvrecommendations"
    /** Google TV's home screen: the fallback when the TV's own one wasn't recorded. */
    const val GOOGLE_HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity"
    /**
     * Google TV has a RecoveryActivity that would take over the home screen without its launcher. Older Android TV
     * (Android 9) has the same setup app without a home screen: there it is left alone.
     */
    const val GOOGLE_SETUP = "com.google.android.tungsten.setupwraith"

    /**
     * Turned off in our mode, when the TV has them: Google's extras. The TV's own home screen and screensaver are added
     * at switch time. Never on XGIMI projectors or the Rokid Station (see targets).
     */
    @JvmField val DEBLOAT = arrayOf(
        "com.google.android.play.games", "com.google.android.youtube.tvmusic",        // Play Games, YouTube Music
    )

    /**
     * XGIMI projectors: their apps all run as the shared system UID, which also owns every saved Wi-Fi network, so
     * only XGIMI Home is turned off, and PRIVACY when asked for (Android keeps networks saved by a shared UID: their
     * creator matches no package).
     * Its chooser (xgimiframeworkui) opens XGIMI Home whenever it is on, and answers the HOME query itself, so the
     * stock home screen is named here. Only in the XGIMI build (Config.DEVICE), on an XGIMI.
     */
    @JvmField val XGIMI = Config.DEVICE == "xgimi" && "XGIMI".equals(Build.BRAND, ignoreCase = true)
    private const val XGIMI_HOME = "com.xgimi.home/com.xgimi.module.cellview.home.ui.HomeActivity"

    /**
     * Rokid Station (the box for Rokid's AR glasses): its ROM refuses `pm disable-user` for every system app, so nothing
     * is turned off; the app only takes the home screen. Rokid's launcher stays on and keeps HOME away from us through
     * plugging the glasses and a reboot (tested). Its apps share the system UID. Only in the Rokid build
     * (Config.DEVICE), on a Rokid.
     */
    @JvmField val ROKID = Config.DEVICE == "rokid" && "Rokid".equals(Build.BRAND, ignoreCase = true)

    /** The voice assistant, only with disable_assistant=true in config.properties (the mic button stops working). */
    @JvmField val ASSISTANT = arrayOf("com.google.android.katniss", "com.android.hotwordenrollment.okgoogle")

    /**
     * Telemetry, only with disable_telemetry=true in config.properties (never the default). XGIMI's run as the shared
     * system UID, so the Wi-Fi-owner check can't see them: each must pass note 01's check by hand first. Not xrmservice
     * (the resource manager), iot, xgimiiotserver or msgcenter (the phone app's push channel).
     */
    @JvmField val PRIVACY = arrayOf(
        "com.xgimi.datareporter", "com.xgimi.bugreportsender",                       // XGIMI usage reports, bug reports
        "com.xgimi.adservice", "com.xgimi.minitvfactory",                            // ads, factory test
        "com.xgimi.newappmarket",                                                     // app market: posts the installed apps
    )

    /**
     * Never turned off, not even by mistake (Google services, the store, the remote, keyboard, settings, system UI).
     * Checked before every disable.
     */
    private val PROTECTED: Set<String> = hashSetOf(
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.apps.mediashell",
        "com.google.android.tv.remote.service", "com.google.android.tv.googletvremotepairer",
        "com.google.android.inputmethod.latin", "com.google.android.webview", "com.android.tv.settings",
        "com.android.systemui", "android", PKG,
        // Rokid Station (Amlogic): launcher, cursor and quick menu, system config, settings, box and glasses updates,
        // casting, setup, voice, gateway, licence page
        "com.rokid.glass.er.launcher", "com.rokid.glass.er.assistserver", "com.rokid.sysconfig", "com.rokid.erbox.settings",
        "com.rokid.glass.ota", "com.rokid.glass.firmware.upgrade", "com.rokid.erbox.projector", "com.rokid.erbox.provision",
        "com.rokid.os.voice", "com.rokid.vgateway", "com.rokid.erbox.webview", "com.droidlogic", "com.droidlogic.miracast")

    /** One switch at a time (jobs can overlap: a boot check and a button press). */
    private val RUN = Any()
    private val FILE_LOCK = Any()

    // MARK: - Requests (from any process)

    /** Asks the main process to switch (the settings screen calls this). */
    @JvmStatic
    fun request(c: Context, mode: String?) {
        ModeJob.schedule(c, ModeJob.APPLY, mode, 0)
    }

    /** Our home screen crashed 3 times in 5 minutes: give the home screen back to the TV's own (asked by :home). */
    @JvmStatic
    fun requestRevertHome(c: Context) {
        ModeJob.schedule(c, ModeJob.REVERT, null, 0)
    }

    /** After the TV boots (and after a TV update): wait for the system to settle and check our mode. */
    @JvmStatic
    fun bootCheck(c: Context) {
        ModeJob.schedule(c, ModeJob.VERIFY, null, 45_000)
    }

    /** After an update of this app: complete our mode if anything is missing. */
    @JvmStatic
    fun verify(c: Context) {
        ModeJob.schedule(c, ModeJob.VERIFY, null, 0)
    }

    // MARK: - Switches (main process, from ModeJob)

    @JvmStatic
    fun applyNow(c: Context, mode: String?) {
        synchronized(RUN) {
            if (FREE == mode) applyFree(c) else applyOriginal(c)
        }
    }

    private fun applyFree(c: Context) {
        var s = state(c)
        try {
            connect(c, s).use { adb ->
                // The TV's own home screen and screensaver, recorded once (before ours replace them) so "original"
                // restores exactly what was there, on any brand.
                var home = originalHome(s)
                if (home.isEmpty() || home.startsWith(PKG + "/")) {
                    val found = currentHome(adb)
                    home = if (found != null) realHome(found) else if (XGIMI) XGIMI_HOME else otherHome(adb) ?: ""
                }
                var dream = originalDream(s)
                if (dream.isEmpty()) dream = defaultDream(adb)
                val latest = state(c)
                latest.put("originalHome", home).put("originalDream", dream)
                val saver = adb.shell("settings get secure screensaver_components").javaTrim()
                if (!saver.contains(PKG + "/")) latest.put("originalScreensaver", safeComponents(saver))
                save(c, latest)
                // Turning off an app makes Android delete the Wi-Fi networks it saved (#14), and Original can't bring
                // them back. The TV's first setup usually saved the network: then stop here, before anything changes.
                val owners = wifiOwners(adb)
                val setupHome = setupIsHome(adb)
                if ((setupHome && owners.contains(GOOGLE_SETUP)) || owners.contains(packageOf(home))) {
                    throw IOException(Ui.tr("a configuração inicial da TV salvou o Wi-Fi, e desligá-la apagaria essas redes. " +
                            "Em Configurações → Rede e Internet, esqueça a rede e conecte de novo (as fora de alcance não aparecem " +
                            "lá: o Claude Code apaga pelo computador) e tente outra vez.",
                            "the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. " +
                            "In Settings → Network & Internet, forget the network and connect again (networks out of range don't " +
                            "show there: Claude Code can remove them from your computer), then try once more.",
                            "la configuración inicial del TV guardó el Wi-Fi, y desactivarla borraría esas redes. " +
                            "En Ajustes → Red e Internet, olvida la red y vuelve a conectarte (las fuera de alcance no aparecen " +
                            "ahí: Claude Code las borra desde la computadora) y vuelve a intentarlo."))
                }
                if (metrics(latest, ORIGINAL) == null && !home.isEmpty()) {
                    busy(c, Ui.tr("Medindo o Início original…", "Measuring the original home screen…"))
                    measureHomeTime(c, adb, ORIGINAL)
                    measureMemory(c, adb, ORIGINAL)
                }
                busy(c, Ui.tr("Trocando o Início e a tela de descanso…", "Switching the home screen and screensaver…"))
                Watchdog.clear(c)
                if (XGIMI) {
                    xgimiGrants(adb, true)
                    adbSecure(c, adb)
                }
                if (ROKID) rokidGrants(adb, true)
                check(adb.shell("cmd package set-home-activity " + HOME_ACTIVITY), "Success",
                        Ui.tr("o Android não aceitou o novo Início", "Android didn't accept the new home screen"))
                adb.shell("settings put secure screensaver_components " + DREAM)
                val started = state(c)
                started.put("mode", FREE).put("homeReverted", false).put("setupHome", setupHome)
                save(c, started)
                val targets = targets(home, dream, setupHome)
                val disabled = HashSet(stringList(state(c), "disabled"))
                val present = packages(adb, "")
                val keptOff = HashSet(stringList(state(c), "keptOff"))
                // Off now and not on our list: the person turned it off, unless an earlier install of the app did (its
                // list lives in the TV's settings too, since an uninstall erases our state file).
                val ours = recorded(adb)
                for (pkg in packages(adb, "-d")) {
                    if (targets(home, dream, true).contains(pkg) && !disabled.contains(pkg) && !ours.contains(pkg)) {
                        keptOff.add(pkg)
                    }
                }
                keptOff.removeAll(ours)
                val before = state(c)
                before.put("keptOff", JSONArray(sorted(keptOff)))
                save(c, before)
                val ownPkgs = switchTime(home, dream)
                val inputs = HashMap<String, Boolean>()
                var done = 0
                for (pkg in targets) {
                    done++
                    busy(c, Ui.tr("Desligando apps (", "Turning off apps (") + done + "/" + targets.size + ")…")
                    if (PROTECTED.contains(pkg) || !present.contains(pkg) || owners.contains(pkg)
                            || keptOff.contains(pkg)) continue
                    if (!ownPkgs.contains(pkg) && tvInput(adb, pkg, inputs)) continue
                    val out = adb.shell("pm disable-user --user 0 " + pkg)
                    if (out.contains("disabled-user")) {
                        disabled.add(pkg)
                        val now = state(c)
                        now.put("disabled", JSONArray(sorted(disabled)))
                        save(c, now)
                    } else {
                        AppLog.w("could not turn off " + pkg + ": " + out.javaTrim())
                    }
                }
                protectedBackOn(adb, disabled, present)
                record(adb, disabled)
                s = state(c)
                s.put("mode", FREE).put("since", System.currentTimeMillis()).put("homeReverted", false)
                        .put("disabled", JSONArray(sorted(disabled))).put("setupHome", setupHome)
                save(c, s)
                notice(c, Ui.tr(Config.APP_NAME + " ligado: Início e tela de descanso novos, " + disabled.size +
                        " apps desligados.",
                        Config.APP_NAME + " is on: new home screen and screensaver, " + disabled.size +
                        " apps turned off."))
                busy(c, Ui.tr("Medindo o " + Config.APP_NAME + "…", "Measuring " + Config.APP_NAME + "…"))
                measureHomeTime(c, adb, FREE)
                done(c, null)
                adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
                ModeJob.schedule(c, ModeJob.MEMORY, FREE, 120_000)
            }
        } catch (e: Exception) {
            done(c, Ui.tr("Não deu para ligar o " + Config.APP_NAME + ": ",
                    "Couldn't turn on " + Config.APP_NAME + ": ") +
                    (if (Ui.ru) Russian.text(e.message ?: "") else e.message))
        }
    }

    private fun applyOriginal(c: Context) {
        var s = state(c)
        try {
            connect(c, s).use { adb ->
                busy(c, Ui.tr("Religando os apps…", "Turning the apps back on…"))
                val home = originalHome(s)
                val toEnable = HashSet(stringList(s, "disabled"))
                for (pkg in targets(home, originalDream(s), true)) {
                    if (!DEBLOAT.contains(pkg) && !ASSISTANT.contains(pkg)
                            && !PRIVACY.contains(pkg)) toEnable.add(pkg)
                }
                toEnable.removeAll(stringList(s, "keptOff"))
                val present = packages(adb, "")
                var done = 0
                val failed = ArrayList<String>()
                for (pkg in sorted(toEnable)) {
                    done++
                    busy(c, Ui.tr("Religando apps (", "Turning apps back on (") + done + "/" + toEnable.size + ")…")
                    if (!present.contains(pkg)) continue
                    // Checked: whatever didn't come back stays on the list (and in the notice); nothing disappears
                    // silently.
                    val out = adb.shell("pm enable " + pkg)
                    if (!out.contains("new state: enabled")) failed.add(pkg)
                }
                record(adb, HashSet(failed))
                busy(c, Ui.tr("Devolvendo o Início original…", "Restoring the original home screen…"))
                val homeOk = restoreHome(adb, home)
                restoreScreensaver(adb, s)
                s = state(c)
                s.put("mode", ORIGINAL).put("since", System.currentTimeMillis()).put("homeReverted", false)
                        .put("disabled", JSONArray(failed)).remove("keptOff")
                save(c, s)
                if (failed.isEmpty() && homeOk) {
                    notice(c, Ui.tr("Modo original: Início, tela de descanso e apps de fábrica de volta.",
                            "Original mode: the factory home screen, screensaver and apps are back."))
                } else {
                    val problems = ArrayList<String>(failed)
                    if (!homeOk) problems.add(Ui.tr("o Início original", "the original home screen"))
                    notice(c, Ui.tr("Modo original, mas não consegui religar: ",
                            "Original mode, but I couldn't turn back on: ") + problems.joinToString(", ") + ".")
                }
                busy(c, Ui.tr("Medindo o Início original…", "Measuring the original home screen…"))
                measureHomeTime(c, adb, ORIGINAL)
                done(c, null)
                adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
                ModeJob.schedule(c, ModeJob.MEMORY, ORIGINAL, 120_000)
                // Last, the permissions our mode gave go back. Taking location back ends the app's processes: done in
                // the middle, it cut the switch off, and the job ran again (on the Rokid, without the home screen).
                if (XGIMI) xgimiGrants(adb, false)
                if (ROKID) rokidGrants(adb, false)
            }
        } catch (e: Exception) {
            done(c, Ui.tr("Não deu para voltar ao original: ", "Couldn't go back to the original: ") +
                    (if (Ui.ru) Russian.text(e.message ?: "") else e.message))
        }
    }

    /**
     * XGIMI: lets this app set the system language (Xgimi keeps it in the app's while XGIMI Settings turns it to
     * Chinese) and search for Bluetooth devices (Android 10+ ties that to location, never read here), or takes that
     * back. The language stays as it is; XGIMI Settings switches it again when it opens.
     */
    private fun xgimiGrants(adb: Adb, on: Boolean) {
        try {
            val pm = "pm " + (if (on) "grant " else "revoke ") + PKG + " android.permission."
            adb.shell("appops set " + PKG + " WRITE_SETTINGS " + (if (on) "allow" else "default"))
            adb.shell("appops write-settings")   // app-ops reach the disk lazily: a restart soon after would drop the set
            adb.shell(pm + "CHANGE_CONFIGURATION")
            location(adb, on)
        } catch (e: IOException) {
            AppLog.w("XGIMI permissions: " + e.message)
        }
    }

    /**
     * XGIMI: ro.adb.secure, for the Home Screen page. "0" lets any computer on the network into the projector's ADB
     * without asking (no adb_keys at all; note 01); the page says so while it is.
     */
    private fun adbSecure(c: Context, adb: Adb) {
        try {
            val v = adb.shell("getprop ro.adb.secure").javaTrim()
            val s = state(c)
            s.put("adbSecure", v)
            save(c, s)
        } catch (e: Exception) {   // only IOException and JSONException, as before
            if (e !is IOException && e !is JSONException) throw e
            AppLog.w("ro.adb.secure: " + e.message)
        }
    }

    /**
     * XGIMI: XGIMI Home turned back on behind our back (on global ROMs xrmservice does it on some remote keys; note 09)
     * goes off again. Asked when our windows get focus (Xgimi), since verify can't run from a broadcast on this ROM.
     * Not during a switch: Original turns it on itself.
     */
    @JvmStatic
    fun xgimiHomeCheck(app: Context) {
        val s = state(app)
        val home = packageOf(XGIMI_HOME)
        val busyAt = s.optLong("busyAt")
        val now = SystemClock.elapsedRealtime()
        val busy = !s.optString("busy").isEmpty() && now >= busyAt && now - busyAt < 10 * 60_000L
        if (!XGIMI || busy || FREE != s.optString("mode") || s.optBoolean("homeReverted")
                || !stringList(s, "disabled").contains(home)) return
        try {
            Adb.connect(app, 10_000, null).use { adb ->
                if (packages(adb, "-d").contains(home)) return
                val out = adb.shell("pm disable-user --user 0 " + home)
                AppLog.w(if (out.contains("disabled-user")) "xgimi: stock home came back, turned off again"
                        else "xgimi: stock home came back, could not turn it off: " + out.javaTrim())
            }
        } catch (e: IOException) {
            AppLog.w("xgimi stock home check: " + e.message)
        }
    }

    /**
     * Location for this app: granted at once, or taken back by a shell outside the app 3 s later, when the switch's job
     * is over. Taking it back ends the app's processes (and XGIMI stops a finished job's process soon after anyway).
     * adbd kills the command's process group when the command ends: setsid moves the shell out of it first.
     */
    private fun location(adb: Adb, on: Boolean) {
        val cmd = "pm " + (if (on) "grant " else "revoke ") + PKG + " android.permission.ACCESS_FINE_LOCATION"
        adb.shell(if (on) cmd else "setsid sh -c 'sleep 3; $cmd' </dev/null >/dev/null 2>&1 & sleep 0.5")
    }

    /** Rokid Station: lets this app search for Bluetooth devices (Android 10+ ties that to location, never read here), or takes that back. */
    private fun rokidGrants(adb: Adb, on: Boolean) {
        try {
            location(adb, on)
        } catch (e: IOException) {
            AppLog.w("Rokid permissions: " + e.message)
        }
    }

    /** The recorded home screen; without one, Google TV's; without that, the system picks (we leave the role). */
    private fun restoreHome(adb: Adb, recordedHome: String): Boolean {
        var home = recordedHome
        if (home.isEmpty() && adb.shell("pm path " + packageOf(GOOGLE_HOME)).contains("package:")) home = GOOGLE_HOME
        // Nothing recorded (ours came on while the TV had no default home): the TV's one other home screen. On the
        // Rokid Station the role fallback below doesn't take ours off.
        if (home.isEmpty()) home = otherHome(adb) ?: ""
        if (!home.isEmpty()) {
            adb.shell("pm enable " + packageOf(home))
            val out = adb.shell("cmd package set-home-activity '" + home + "'")
            if (out.contains("Success")) return true
            AppLog.w("home: set-home-activity " + home + ": " + out.javaTrim())
        } else {
            AppLog.w("home: none recorded or found")
        }
        adb.shell("cmd role remove-role-holder --user 0 android.app.role.HOME " + PKG)
        return !homeHolder(adb).contains(PKG)
    }

    /** Safety net: our home screen crashed 3 times in a row. The home screen and the screensaver go back; the rest stays. */
    @JvmStatic
    fun revertHomeNow(app: Context) {
        synchronized(RUN) {
            val s = state(app)
            try {
                Adb.connect(app, 10_000, null).use { adb ->
                    val ours = homeHolder(adb).contains(PKG)
                    if (!ours && (FREE != s.optString("mode") || s.optBoolean("homeReverted"))) return
                    val home = originalHome(s)
                    val keptOff = stringList(s, "keptOff")
                    if (!keptOff.contains(GOOGLE_SETUP)) adb.shell("pm enable " + GOOGLE_SETUP)
                    if (!restoreHome(adb, home)) {
                        AppLog.w("could not give the home screen back")
                        return
                    }
                    val homePkg = packageOf(home)
                    val dreamPkg = packageOf(originalDream(s))
                    if (dreamPkg != null && !keptOff.contains(dreamPkg)) adb.shell("pm enable " + dreamPkg)
                    val kept = JSONArray()
                    val stillOff = HashSet<String>()
                    for (pkg in stringList(s, "disabled")) {
                        if (pkg != GOOGLE_SETUP && pkg != homePkg && pkg != dreamPkg) {
                            kept.put(pkg)
                            stillOff.add(pkg)
                        }
                    }
                    record(adb, stillOff)
                    // The screensaver first: if that fails, the revert isn't marked done and the next try does it
                    // again.
                    restoreScreensaver(adb, s)
                    s.put("homeReverted", true).put("disabled", kept)
                    save(app, s)
                    notice(app, Ui.tr("O Início do " + Config.APP_NAME +
                            " caiu 3 vezes seguidas; voltei para o Início original.", Config.APP_NAME +
                            "'s home screen crashed 3 times in a row; I switched back to the original one."))
                    adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
                }
            } catch (e: Exception) {
                AppLog.w("could not give the home screen back: " + e.message)
            }
        }
    }

    /**
     * In our mode, checks that everything is still as we left it (the listed apps off, our home screen and
     * screensaver) and completes whatever is missing, with a notice. Runs after the TV boots (TV updates can turn
     * things back on) and after an update of this app.
     */
    @JvmStatic
    fun verifyNow(app: Context) {
        synchronized(RUN) {
            val s = state(app)
            val fingerprint = Build.FINGERPRINT
            val updated = fingerprint != s.optString("fingerprint", fingerprint)
            try {
                s.put("fingerprint", fingerprint)
                s.remove("busy")
                save(app, s)
            } catch (ignored: JSONException) {}
            if (FREE != s.optString("mode")) return
            val fixed = ArrayList<String>()
            try {
                Adb.connect(app, 10_000, null).use { adb ->
                    val present = packages(adb, "")
                    val disabledNow = packages(adb, "-d")
                    val owners = wifiOwners(adb)
                    val reverted = s.optBoolean("homeReverted")
                    val home = originalHome(s)
                    val wanted = HashSet(stringList(s, "disabled"))
                    val dream = originalDream(s)
                    for (pkg in targets(home, dream, s.optBoolean("setupHome", true))) {
                        if (!reverted || !(pkg == packageOf(home) || pkg == GOOGLE_SETUP || pkg == packageOf(dream))) {
                            wanted.add(pkg)
                        }
                    }
                    val disabled = HashSet(stringList(s, "disabled"))
                    // Off before our mode: turned off again if a TV update brings one back, but never handed to
                    // Original to turn on.
                    val keptOff = stringList(s, "keptOff")
                    val ownPkgs = switchTime(home, dream)
                    val inputs = HashMap<String, Boolean>()
                    protectedBackOn(adb, disabled, present)
                    for (pkg in sorted(wanted)) {
                        if (PROTECTED.contains(pkg) || !present.contains(pkg) || owners.contains(pkg)) continue
                        if (disabledNow.contains(pkg)) {
                            if (!keptOff.contains(pkg)) disabled.add(pkg)
                            continue
                        }
                        if (!ownPkgs.contains(pkg) && tvInput(adb, pkg, inputs)) continue
                        if (adb.shell("pm disable-user --user 0 " + pkg).contains("disabled-user")) {
                            if (!keptOff.contains(pkg)) disabled.add(pkg)
                            fixed.add(pkg)
                        }
                    }
                    if (XGIMI) {
                        xgimiGrants(adb, true)
                        adbSecure(app, adb)
                    }
                    if (ROKID) rokidGrants(adb, true)
                    if (!reverted && !homeHolder(adb).contains(PKG)) {
                        adb.shell("cmd package set-home-activity " + HOME_ACTIVITY)
                        fixed.add(Ui.tr("o Início", "the home screen"))
                    }
                    // Whole component names: ".ScreensaverAlone" (Android 11) contains ".Screensaver" (12 and newer).
                    if (!reverted && !adb.shell("settings get secure screensaver_components").javaTrim().split(",")
                            .contains(DREAM)) {
                        adb.shell("settings put secure screensaver_components " + DREAM)
                        fixed.add(Ui.tr("a tela de descanso", "the screensaver"))
                    }
                    record(adb, disabled)
                    val latest = state(app)
                    latest.put("disabled", JSONArray(sorted(disabled)))
                    save(app, latest)
                }
            } catch (e: Exception) {
                notice(app, Ui.tr("Não consegui conferir o " + Config.APP_NAME + ": ",
                        "Couldn't check " + Config.APP_NAME + ": ") +
                        (if (Ui.ru) Russian.text(e.message ?: "") else e.message))
                return
            }
            if (!fixed.isEmpty()) {
                notice(app, (if (updated) Ui.tr("A atualização da TV religou ", "The TV update turned back on ")
                        else Ui.tr("Desliguei o que faltava: ", "Turned off what was missing: ")) +
                        fixed.joinToString(", ") + ".")
            } else if (updated) {
                notice(app, Ui.tr("A TV foi atualizada; o " + Config.APP_NAME + " continua igual.",
                        "The TV was updated; " + Config.APP_NAME + " is still on."))
            }
        }
    }

    /**
     * Turned off by an older version, protected since: back on. After a reinstall only the record
     * in the TV's settings knows about it, so both lists count. One that won't come back stays in `disabled`, and so in
     * the record, for Original and the next check.
     */
    private fun protectedBackOn(adb: Adb, disabled: MutableSet<String>, present: Set<String>) {
        val ours = HashSet(disabled)
        ours.addAll(recorded(adb))
        for (pkg in sorted(ours)) {
            if (!pkg.matches(Regex("[\\w.]+"))) {   // it goes into a shell command
                AppLog.w("turned-off list: skipped an unexpected name " + JSONObject.quote(pkg))
                continue
            }
            if (!PROTECTED.contains(pkg)) continue
            if (adb.shell("pm enable " + pkg).contains("new state: enabled")) {
                disabled.remove(pkg)
                AppLog.w("turned back on " + pkg)
            } else if (present.contains(pkg)) {
                disabled.add(pkg)
            }
        }
    }

    /**
     * What to turn off: the TV's own home screen and screensaver, Google TV's recovery home (when setupHome: the setup
     * app is one of the TV's home screens), and the lists above. Nothing on Rokid: its ROM wouldn't let us, and the
     * generic list would still reach apps the person installed there (YouTube Music).
     */
    private fun targets(home: String, dream: String, setupHome: Boolean): MutableList<String> {
        val out = ArrayList<String>()
        if (ROKID) return out
        val homePkg = packageOf(home)
        if (homePkg != null && homePkg != PKG) out.add(homePkg)
        if (Config.DISABLE_TELEMETRY) out.addAll(PRIVACY)
        if (XGIMI) return out
        if (TV_LAUNCHER == homePkg) out.add(TV_RECOMMENDATIONS)
        if (setupHome) out.add(GOOGLE_SETUP)
        val dreamPkg = packageOf(dream)
        if (dreamPkg != null && dreamPkg != PKG && dreamPkg != homePkg) out.add(dreamPkg)
        out.addAll(DEBLOAT)
        if (Config.DISABLE_ASSISTANT) out.addAll(ASSISTANT)
        return out
    }

    /** What a switch adds besides the lists: the TV's own home screen and screensaver, and Google TV's setup app. */
    private fun switchTime(home: String, dream: String): Set<String> {
        val out = hashSetOf(GOOGLE_SETUP)
        val homePkg = packageOf(home)
        if (homePkg != null) out.add(homePkg)
        val dreamPkg = packageOf(dream)
        if (dreamPkg != null) out.add(dreamPkg)
        return out
    }

    private const val TV_INPUT_HARDWARE = "android.permission.TV_INPUT_HARDWARE"

    /**
     * The input guard (note 09, C5): a list entry whose own requested permissions name TV_INPUT_HARDWARE runs a TV
     * input (HDMI, tuner) and is never turned off, whatever list it is on. Asked once per switch per package
     * (`seen` holds that switch's answers). Only that package's blocks count, not its shared UID's.
     */
    private fun tvInput(adb: Adb, pkg: String, seen: MutableMap<String, Boolean>): Boolean {
        val known = seen[pkg]
        if (known != null) return known
        // Not a plain package name: never put into a shell command, and left on.
        val input = !pkg.matches(Regex("[\\w.]+")) || requestsInput(adb.shell("dumpsys package " + pkg), pkg)
        seen[pkg] = input
        if (input) AppLog.w("input guard: " + pkg + " requests TV_INPUT_HARDWARE, left on")
        return input
    }

    private val PACKAGE_BLOCK = Regex("^Package \\[([\\w.]+)\\]")

    /** Whether a "requested permissions:" block inside pkg's own "Package [pkg]" block names TV_INPUT_HARDWARE. */
    private fun requestsInput(dumpsys: String, pkg: String): Boolean {
        var current: String? = null   // the package block we're in (null: another section, like SharedUser)
        var block = -1                 // the indent of the requested permissions header we're under, or -1
        for (line in dumpsys.split("\n")) {
            val t = line.javaTrim()
            if (t.isEmpty()) continue
            val indent = line.indexOf(t[0])
            if (block >= 0 && indent <= block) block = -1
            val m = PACKAGE_BLOCK.find(t)
            if (m != null) current = m.groupValues[1]
            else if (indent == 0 || t.startsWith("SharedUser [")) current = null
            if (t == "requested permissions:") {
                if (pkg == current) block = indent
            } else if (block >= 0 && (t == TV_INPUT_HARDWARE || t.startsWith(TV_INPUT_HARDWARE + ":"))) {
                return true
            }
        }
        return false
    }

    /** The home screen in use now ("package/activity"): the HOME role holder, among the TV's home screens. */
    private fun currentHome(adb: Adb): String? {
        val holder = homeHolder(adb)
        if (holder.isEmpty() || holder.contains(PKG)) return null
        val list = adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN" +
                " -c android.intent.category.HOME")
        for (each in list.split("\n")) {
            val line = each.javaTrim()
            if (line.startsWith(holder + "/")) return line
        }
        return null
    }

    /**
     * The TV's other home screen when it has exactly one (not ours, priority 0 or more: not a fallback like Settings'
     * FallbackHome), as [realHome]; null when it has none or several. For a TV with no default home (the chooser).
     */
    private fun otherHome(adb: Adb): String? {
        var priority = 0
        var found: String? = null
        val list = adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN" +
                " -c android.intent.category.HOME")
        for (each in list.split("\n")) {
            val line = each.javaTrim()
            if (line.startsWith("priority=")) {
                priority = line.substringAfter("priority=").substringBefore(' ').toIntOrNull() ?: 0
            } else if (priority >= 0 && COMPONENT.matches(line) && !line.startsWith(PKG + "/")) {
                if (found != null) return null
                found = line
            }
        }
        return if (found != null) realHome(found) else null
    }

    /**
     * The package that is the home screen now, or "". Some Android 12 TVs have no `cmd role get-role-holders` (it
     * answers "Unknown command"), so then we ask which activity opens HOME.
     */
    private fun homeHolder(adb: Adb): String {
        val holder = adb.shell("cmd role get-role-holders --user 0 android.app.role.HOME").javaTrim()
        if (holder.matches(Regex("[\\w.]+(;[\\w.]+)*"))) return holder
        val lines = adb.shell("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN" +
                " -c android.intent.category.HOME").javaTrim().split("\n")
        val component = lines[lines.size - 1].javaTrim()
        // With no default home yet, the system's chooser answers ("android/...ResolverActivity"): unknown.
        return if (component.contains("/") && !component.startsWith("android/")) packageOf(component)!! else ""
    }

    /** Whether Google TV's setup app is one of the home screens (its RecoveryActivity), so it must go off too. */
    private fun setupIsHome(adb: Adb): Boolean {
        return adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN" +
                " -c android.intent.category.HOME").contains(GOOGLE_SETUP + "/")
    }

    /** Apps that saved a Wi-Fi network ("cname" in the Wi-Fi service's list of saved networks). */
    private fun wifiOwners(adb: Adb): Set<String> {
        val owners = HashSet<String>()
        for (m in Regex("cname=([\\w.]+)").findAll(adb.shell("dumpsys wifi | grep -o 'cname=[^ ]*'"))) {
            owners.add(m.groupValues[1])
        }
        return owners
    }

    /** The recorded home screen of the TV, as [realHome]. */
    private fun originalHome(s: JSONObject): String {
        return realHome(s.optString("originalHome", ""))
    }

    /**
     * Some Google TVs (Android 14) leave the HOME role with Google TV's recovery home, while launcherx is the one that
     * answers the Home button. Treat that as launcherx, or it stays on and "original" restores the recovery screen.
     */
    private fun realHome(home: String): String {
        return if (home.startsWith(GOOGLE_SETUP + "/")) GOOGLE_HOME else safeComponent(home)
    }

    private fun originalDream(s: JSONObject): String {
        return safeComponent(s.optString("originalDream", ""))
    }

    // Nested classes ("Outer$Inner") are valid; every shell command that takes one quotes it, so $ stays literal.
    private val COMPONENT = Regex("[A-Za-z][\\w.]*/[\\w.\$]+")

    private fun safeComponent(component: String): String {
        return if (COMPONENT.matches(component)) component else ""
    }

    private fun safeComponents(components: String): String {
        // Java's split dropped trailing empty strings ("a/b," is one component); Kotlin's keeps them.
        for (one in components.split(",").dropLastWhile { it.isEmpty() }) {
            if (safeComponent(one).isEmpty()) return ""
        }
        return components
    }

    private fun restoreScreensaver(adb: Adb, s: JSONObject) {
        val saver = safeComponents(s.optString("originalScreensaver", ""))
        if (saver.isEmpty()) adb.shell("settings delete secure screensaver_components")
        else adb.shell("settings put secure screensaver_components '" + saver + "'")
    }

    /** The system's default screensaver ("package/service"), or "". */
    private fun defaultDream(adb: Adb): String {
        val d = adb.shell("settings get secure screensaver_default_component").javaTrim()
        return if (d.contains("/") && !d.startsWith(PKG + "/")) safeComponent(d) else ""
    }

    private fun packageOf(component: String?): String? {
        if (component == null || component.isEmpty()) return null
        val slash = component.indexOf('/')
        return if (slash > 0) component.substring(0, slash) else component
    }

    /** Packages the shell sees (all; "-d" = only the disabled ones). The app alone can't see the ones it doesn't declare. */
    private fun packages(adb: Adb, flags: String): Set<String> {
        val out = HashSet<String>()
        for (each in adb.shell("pm list packages " + flags).split("\n")) {
            val line = each.javaTrim()
            if (line.startsWith("package:")) out.add(line.substring(8))
        }
        return out
    }

    private fun connect(c: Context, s: JSONObject): Adb {
        busy(c, Ui.tr("Conectando…", "Connecting…"))
        val adb = Adb.connect(c, 120_000) {
            busy(c, Ui.tr("Aceite o aviso “Permitir depuração” na TV (marque “Sempre permitir”).",
                    "Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))
        }
        if (!s.optBoolean("adbAuthorized")) {
            val latest = state(c)
            try { latest.put("adbAuthorized", true) } catch (ignored: JSONException) {}
            save(c, latest)
        }
        return adb
    }

    private fun check(output: String, expected: String, error: String) {
        if (!output.contains(expected)) throw IOException(error + " (" + output.javaTrim() + ")")
    }

    /** Java's String.trim: only chars up to ' ' (Kotlin's trim() also takes other Unicode spaces). */
    private fun String.javaTrim(): String = trim { it <= ' ' }

    // MARK: - Measurements (without opening apps: memory and the home button)

    private fun measureHomeTime(c: Context, adb: Adb, mode: String) {
        val samples = ArrayList<Int>()
        val total = Regex("TotalTime: (\\d+)")
        for (i in 0 until 3) {
            adb.shell("am start -W -n " + SETTINGS_ACTIVITY)
            SystemClock.sleep(500)
            val m = total.find(adb.shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"))
            if (m != null) samples.add(m.groupValues[1].toInt())
            SystemClock.sleep(600)
        }
        adb.shell("am start -W -n " + SETTINGS_ACTIVITY)
        if (samples.isEmpty()) return
        samples.sort()
        putMetric(c, mode, "homeMs", samples[samples.size / 2].toLong())
    }

    /** Two minutes after a switch (the home screen settled, caches trimmed). */
    @JvmStatic
    fun measureMemoryNow(c: Context, mode: String) {
        synchronized(RUN) {
            if (mode != state(c).optString("mode")) return
            try {
                Adb.connect(c, 10_000, null).use { adb ->
                    measureMemory(c, adb, mode)
                }
            } catch (e: Exception) {
                AppLog.w("memory measurement failed: " + e.message)
            }
        }
    }

    private fun measureMemory(c: Context, adb: Adb, mode: String) {
        val avail = Regex("MemAvailable:\\s+(\\d+) kB").find(adb.shell("cat /proc/meminfo"))
        if (avail != null) putMetric(c, mode, "memAvailableMB", avail.groupValues[1].toLong() / 1024)
        var pss = 0L
        val home = packageOf(originalHome(state(c)))
        val processes = if (FREE == mode) arrayOf(PKG + ":home")
                else if (home == null) arrayOf() else arrayOf(home, home + ":coreservices")
        val total = Regex("TOTAL PSS:\\s+(\\d+)")
        for (p in processes) {
            val m = total.find(adb.shell("dumpsys meminfo " + p))
            if (m != null) pss += m.groupValues[1].toLong()
        }
        if (pss > 0) putMetric(c, mode, "homePssMB", pss / 1024)
    }

    private fun putMetric(c: Context, mode: String, key: String, value: Long) {
        val s = state(c)
        var metrics = s.optJSONObject("metrics")
        if (metrics == null) metrics = JSONObject()
        var m = metrics.optJSONObject(mode)
        if (m == null) m = JSONObject()
        m.put(key, value).put("at", System.currentTimeMillis())
        metrics.put(mode, m)
        s.put("metrics", metrics)
        save(c, s)
    }

    private fun metrics(s: JSONObject, mode: String): JSONObject? {
        val metrics = s.optJSONObject("metrics")
        return metrics?.optJSONObject(mode)
    }

    // MARK: - State (a file shared by the two processes; atomic writes)

    @JvmStatic
    fun state(c: Context): JSONObject {
        synchronized(FILE_LOCK) {
            val f = File(c.filesDir, "home-state.json")
            if (!f.exists()) return defaults()
            try {
                FileInputStream(f).use { input ->
                    val data = ByteArray(f.length().toInt())
                    var read = 0
                    while (read < data.size) {
                        val n = input.read(data, read, data.size - read)
                        if (n < 0) break
                        read += n
                    }
                    return JSONObject(String(data, 0, read, StandardCharsets.UTF_8))
                }
            } catch (e: IOException) {
                return defaults()
            } catch (e: JSONException) {
                return defaults()
            }
        }
    }

    private fun defaults(): JSONObject {
        try {
            return JSONObject().put("mode", ORIGINAL)
        } catch (e: JSONException) {
            return JSONObject()
        }
    }

    @JvmStatic
    fun save(c: Context, s: JSONObject) {
        synchronized(FILE_LOCK) {
            val dir = c.filesDir
            val tmp = File(dir, "home-state.json.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(s.toString().toByteArray(StandardCharsets.UTF_8))
                    out.fd.sync()
                }
            } catch (e: IOException) {
                AppLog.w("could not save the state: " + e.message)
                return
            }
            if (!tmp.renameTo(File(dir, "home-state.json"))) AppLog.w("could not replace the state file")
        }
    }

    private fun busy(c: Context, text: String) {
        val s = state(c)
        try {
            s.put("busy", text).put("busyAt", SystemClock.elapsedRealtime()).put("error", "")
        } catch (ignored: JSONException) {}
        save(c, s)
    }

    private fun done(c: Context, error: String?) {
        val s = state(c)
        s.remove("busy")
        try { s.put("error", error ?: "") } catch (ignored: JSONException) {}
        save(c, s)
        if (error != null) {
            AppLog.w(error)
            notice(c, error)
        }
    }

    /** A notice for the home screen (a bubble) and the settings screen. Keeps the last 20. */
    @JvmStatic
    fun notice(c: Context, text: String?) {
        AppLog.w("notice: " + text)
        val s = state(c)
        val old = s.optJSONArray("notices")
        val list = JSONArray()
        try {
            list.put(JSONObject().put("at", System.currentTimeMillis()).put("text", text))
            if (old != null) {
                var i = 0
                while (i < old.length() && list.length() < 20) {
                    list.put(old.get(i))
                    i++
                }
            }
            s.put("notices", list)
        } catch (ignored: JSONException) {}
        save(c, s)
    }

    // MARK: - Helpers

    @JvmStatic
    fun stringList(s: JSONObject, key: String): MutableList<String> {
        val out = ArrayList<String>()
        val a = s.optJSONArray(key)
        if (a != null) for (i in 0 until a.length()) out.add(a.optString(i))
        return out
    }

    /**
     * What the app turned off, kept in the TV's own settings too: an uninstall or Clear data erases our state file,
     * and a new install must still tell its own work from what the person had turned off (#142).
     */
    private const val RECORD = "beam_station_disabled"

    private fun recorded(adb: Adb): Set<String> {
        val out = HashSet<String>()
        try {
            val v = adb.shell("settings get global " + RECORD).javaTrim()
            if (!v.isEmpty() && v != "null") for (pkg in v.split(",")) if (pkg.matches(Regex("[\\w.]+"))) out.add(pkg)
        } catch (e: IOException) {
            AppLog.w("could not read the record of what we turned off: " + e.message)
        }
        return out
    }

    private fun record(adb: Adb, disabled: Set<String>) {
        val list = ArrayList<String>()
        for (pkg in sorted(disabled)) if (pkg.matches(Regex("[\\w.]+"))) list.add(pkg)
        try {
            adb.shell(if (list.isEmpty()) "settings delete global " + RECORD
                    else "settings put global " + RECORD + " " + list.joinToString(","))
        } catch (e: IOException) {
            AppLog.w("could not save the record of what we turned off: " + e.message)
        }
    }

    private fun sorted(set: Set<String>): MutableList<String> {
        val list = ArrayList(set)
        list.sort()
        return list
    }
}
