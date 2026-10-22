package io.github.tuthan.beamstation

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.io.FilenameFilter
import java.io.IOException
import java.util.Locale
import java.util.regex.Pattern

/**
 * What shows up on the home screen and in the "All Apps" sheet, and how to open each thing.
 *
 * On the home shelf, each app is its colored logo on a dark card: the official logo in assets/logos/<key>.png
 * (downloaded from Wikimedia Commons by tools/logos.py) or, without one, the logo lifted from the app's TV art (the
 * Android TV "banner"), saved to disk after the first time. In "All Apps", 16:9 cards with the whole TV art (or the
 * icon on a dark card). Shortcuts (live TV, inputs, All Apps, settings) are icon buttons.
 */
internal object HomeApps {
    const val APP = 0
    const val LIVE_TV = 1
    const val INPUT = 2
    const val ALL_APPS = 3
    const val SETTINGS = 4
    const val ANDROID_SETTINGS = 5
    /** The "HDMI Inputs" button: opens the bottom drawer on the inputs (the home screen handles it). */
    const val INPUTS = 7
    /** XGIMI projectors' buttons (Xgimi): auto focus, auto keystone, speaker mode, eco mode. */
    const val FOCUS = 8
    const val KEYSTONE = 9
    const val SPEAKER = 10
    const val ECO = 11
    /** Rokid glasses' 2D/3D button (Rokid); its key says what pressing it does: "glasses-3d" or "glasses-2d". */
    const val GLASSES_3D = 12
    /** The Rokid Station's sleep timer button (Power): each press steps it on (off, 30, 60, 90, 120 min). */
    const val SLEEP_TIMER = 13
    /** Image size of each card (16:9; on screen it shows up to ~330 px wide). */
    const val TILE_W = 384
    const val TILE_H = 216

    class Item {
        @JvmField var kind = 0
        @JvmField var key: String? = null
        @JvmField var label: String? = null
        @JvmField var pkg: String? = null
        @JvmField var inputId: String? = null
        @JvmField var number: String? = null
        // opens this address in the app instead of the app's home (a title's page)
        @JvmField var uri: String? = null
        @JvmField var symbol: String? = null      // icon for the shortcuts and special cards
        @JvmField var tile: Bitmap? = null        // 16:9 card (apps and specials)
        @JvmField var logo: Bitmap? = null        // colored logo with no background (shelf); null = use the card
        @JvmField var logoDark: Bitmap? = null    // version for a light background (YouTube's white icon), if any
        // fraction of the logo with ink (thin logos are drawn larger, with the same "weight")
        @JvmField var logoInk = 0f
        @JvmField var logoScale = 1f              // size tweak by eye (compact logos need more)
        @JvmField var connected = true
    }

    /**
     * Apps on the home shelf, in this order, when installed (up to MAX_SHELF): key, name, package, brand color (for
     * its logo on glass) and how to lift the logo from the app's TV art: "tint" = a white logo takes the art's
     * background color (Prime Video, Globoplay); "play" = the white triangle inside the red button stays white
     * (YouTube); empty = the art's colors as they are. Last, a visual size tweak for the logo. Edit freely.
     */
    private val SHELF = arrayOf(
        arrayOf("netflix", "Netflix", "com.netflix.ninja", "E50914", "", "1"),
        arrayOf("youtube", "YouTube", "com.google.android.youtube.tv", "FF0033", "play", "1"),
        arrayOf("prime", "Prime Video", "com.amazon.amazonvideo.livingroom", "1A98FF", "tint", "0.96"),
        arrayOf("disney", "Disney+", "com.disney.disneyplus", "1838C8", "", "1"),
        arrayOf("max", "Max", "com.wbd.stream", "6C3BE0", "", "1.32"),
        arrayOf("max", "HBO Max", "com.hbo.hbonow", "6C3BE0", "", "1.32"),
        arrayOf("appletv", "Apple TV", "com.apple.atve.androidtv.appletv", "B8BCC6", "", "1.1"),
        arrayOf("hulu", "Hulu", "com.hulu.livingroomplus", "1CE783", "", "1"),
        arrayOf("paramount", "Paramount+", "com.cbs.ott", "0064FF", "", "1"),
        arrayOf("peacock", "Peacock", "com.peacocktv.peacockandroid", "6E55DC", "", "1"),
        arrayOf("globoplay", "Globoplay", "com.globo.globotv", "FF5A1F", "tint", "1"),
        arrayOf("spotify", "Spotify", "com.spotify.tv.android", "1DB954", "", "1"),
    )
    const val MAX_SHELF = 8
    /** Live TV apps (the antenna's channels open through the standard Android request first): Live Channels. */
    @JvmField val LIVE_TV_APPS = arrayOf("com.google.android.tv")
    /** Kept out of "All Apps" (only hidden, still installed). Add the ones your TV shows that you never use. */
    private val HIDDEN: Set<String> = hashSetOf(
            "com.google.android.play.games",        // Google Play Games
            "com.google.android.videos",            // Google Play Movies (discontinued)
            "com.disney.starplus")                  // Star+ (merged into Disney+)

    private val TILES = HashMap<String, Bitmap>()    // key → ready card
    private val LOGOS = HashMap<String, Bitmap?>()   // key → colored logo with no background

    /**
     * Home screen apps. `extract`: also lifts the logos that are not on disk yet (slow: background only); without it,
     * those apps keep their TV art until the next load.
     */
    @JvmStatic
    fun shelf(c: Context, extract: Boolean): MutableList<Item> {
        val items = ArrayList<Item>()
        val pm = c.packageManager
        for (pkg in shelfPackages(c)) {
            if (items.size >= MAX_SHELF) break
            val launch = launchIntent(pm, pkg) ?: continue
            val ri = pm.resolveActivity(launch, 0)
            val d = known(pkg)
            val item = Item()
            item.kind = APP
            item.pkg = pkg
            if (d == null) {
                // An app picked in Settings > Shelf that isn't in SHELF: its own name and TV art.
                item.key = pkg
                item.label = if (ri != null) valueOf(ri.loadLabel(pm)) else pkg
                item.tile = appTile(c, null, pkg, 0)
            } else {
                item.key = d[0]
                item.label = d[1]
                val logo = shelfLogo(c, d[0], d[2], d[4], extract)
                item.logo = logo
                if (logo == null) item.tile = appTile(c, d[0], d[2], 0xFF000000.toInt() or d[3].toInt(16))
                if (logo != null) item.logoInk = ink(logo)
                item.logoDark = ownLogoCached(c, d[0] + "-dark")
                item.logoScale = d[5].toFloat()
            }
            items.add(item)
        }
        return items
    }

    private fun known(pkg: String): Array<String>? {
        for (d in SHELF) if (d[2] == pkg) return d
        return null
    }

    /** The shelf's packages in order: the user's list (Settings > Shelf), or the built-in one. */
    @JvmStatic
    fun shelfPackages(c: Context): MutableList<String> {
        val custom = Prefs.shelf()
        if (custom != null) return custom
        val out = ArrayList<String>()
        val pm = c.packageManager
        for (d in SHELF) if (out.size < MAX_SHELF && launchIntent(pm, d[2]) != null) out.add(d[2])
        return out
    }

    /** Every app the shelf could hold, as {package, name}: the ones from SHELF first, then the other TV apps by name. */
    @JvmStatic
    fun shelfCandidates(c: Context): MutableList<Array<String>> {
        val pm = c.packageManager
        val out = ArrayList<Array<String>>()
        val seen = HashSet<String>()
        for (d in SHELF) if (launchIntent(pm, d[2]) != null && seen.add(d[2])) out.add(arrayOf(d[2], d[1]))
        val more = ArrayList<Array<String>>()
        for (app in launchable(c)) {
            if (app[0] == c.packageName || HIDDEN.contains(app[0]) || !seen.add(app[0])) continue
            more.add(app)
        }
        more.sortWith(Comparator { a, b -> a[1].lowercase(Locale.ROOT).compareTo(b[1].lowercase(Locale.ROOT)) })
        out.addAll(more)
        return out
    }

    /** Live TV, the HDMI inputs with a device sending a signal, and All Apps. */
    @JvmStatic
    fun shortcuts(c: Context): MutableList<Item> {
        val items = ArrayList<Item>()
        if (liveTv(c) != null) {
            items.add(shortcut(LIVE_TV, "live", Ui.tr("TV ao Vivo", "Live TV"), "tv"))
        }
        items.addAll(inputs(c))
        items.add(shortcut(ALL_APPS, "all", Ui.tr("Todos os Apps", "All Apps"), "apps"))
        return items
    }

    private fun shortcut(kind: Int, key: String?, label: String?, symbol: String?): Item {
        val item = Item()
        item.kind = kind
        item.key = key
        item.label = label
        item.symbol = symbol
        return item
    }

    /** Icon button (the ones in the corner of the home screen). */
    @JvmStatic
    fun action(kind: Int, key: String?, label: String?, symbol: String?): Item = shortcut(kind, key, label, symbol)

    /** The app's settings, in the corner of the home screen. */
    @JvmStatic
    fun settings(): Item = shortcut(SETTINGS, "settings", Ui.tr("Ajustes", "Settings"), "settings")

    /**
     * Shortcuts in the bottom drawer (on XGIMI projectors, also focus, keystone, speaker mode and eco mode; on the
     * Rokid Station, the sleep timer, and 2D/3D with glasses plugged in). `glasses`: also asks the glasses for their mode
     * (slow: background only); without it, the 2D/3D button waits for the next load.
     */
    @JvmStatic
    fun drawerShortcuts(c: Context, glasses: Boolean): MutableList<Item> {
        val items = ArrayList<Item>()
        items.add(shortcut(ALL_APPS, "all", Ui.tr("Todos os Apps", "All Apps"), "apps"))
        items.add(shortcut(SETTINGS, "settings", Ui.tr("Ajustes do Início", "Home Settings"), "settings"))
        items.add(shortcut(ANDROID_SETTINGS, "tv-settings", Ui.tr("Ajustes da TV", "TV Settings"), "tv_settings"))
        if (Xgimi.hasFocusKeystone(c)) {
            items.add(shortcut(FOCUS, "focus", Ui.tr("Foco Automático", "Auto Focus"), "focus"))
            items.add(shortcut(KEYSTONE, "keystone", Ui.tr("Ajuste Trapezoidal", "Auto Keystone"), "keystone"))
        }
        if (Xgimi.hasSpeaker(c)) {
            items.add(shortcut(SPEAKER, "speaker", Ui.tr("Modo Caixa de Som", "Speaker Mode"), "speaker"))
        }
        if (Xgimi.hasEco()) items.add(shortcut(ECO, "eco", Ui.tr("Modo Econômico", "Eco Mode"), "eco"))
        if (Power.available()) {
            items.add(shortcut(SLEEP_TIMER, "sleep-timer", Ui.tr("Timer para Dormir", "Sleep Timer"), "bedtime"))
        }
        val mode = if (glasses) Rokid.mode(c) else -1
        if (mode == Rokid.MODE_3D) {
            items.add(shortcut(GLASSES_3D, "glasses-2d", Ui.tr("Mudar para 2D", "Switch to 2D"), "3d"))
        } else if (mode >= 0) {
            items.add(shortcut(GLASSES_3D, "glasses-3d", Ui.tr("Mudar para 3D", "Switch to 3D"), "3d"))
        }
        return items
    }

    /** A streaming service's app (the "Watch" button of the featured pick). */
    @JvmStatic
    fun app(pkg: String?, label: String?, uri: String?): Item {
        val item = app(pkg, label)
        item.uri = uri
        return item
    }

    @JvmStatic
    fun app(pkg: String?, label: String?): Item {
        val item = Item()
        item.kind = APP
        item.key = pkg
        item.pkg = pkg
        item.label = label
        return item
    }

    /** For the bottom drawer: live TV and every HDMI input (the ones with no device are shown dimmed). */
    @JvmStatic
    fun allInputs(c: Context): MutableList<Item> {
        val items = ArrayList<Item>()
        if (liveTv(c) != null) items.add(shortcut(LIVE_TV, "live", Ui.tr("TV ao Vivo", "Live TV"), "tv"))
        items.addAll(inputs(c, true))
        return items
    }

    private fun inputs(c: Context): MutableList<Item> = inputs(c, false)

    /**
     * HDMI inputs: only the ones with a device sending a signal (or all of them, with `all`). A name you gave the input
     * in the TV's settings is used as the label; without one, the name the device reports over HDMI-CEC ("PlayStation 5").
     * The icon follows what that name says the device is (see deviceSymbol).
     */
    private fun inputs(c: Context, all: Boolean): MutableList<Item> {
        val out = ArrayList<Item>()
        val tim = c.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager? ?: return out
        val list = ArrayList<TvInputInfo>()
        // A device that talks HDMI-CEC gets its own input, a child of the port's, named after the device: port input id
        // -> that name.
        val devices = HashMap<String, String>()
        try {
            for (info in tim.tvInputList) {
                if (info.type != TvInputInfo.TYPE_HDMI) continue
                if (info.parentId == null) {
                    list.add(info)
                } else {
                    devices[info.parentId] = valueOf(info.loadLabel(c)).trim { it <= ' ' }   // Java's trim
                }
            }
        } catch (e: RuntimeException) {
            return out
        }
        list.sortWith(Comparator { a, b -> a.id.compareTo(b.id) })
        val digit = Regex("(\\d)")
        for (i in list.indices) {
            val info = list[i]
            var number = i + 1
            val m = digit.find(valueOf(info.loadLabel(c)))
            if (m != null) number = m.groupValues[1].toInt()
            // Some TVs report "connected standby" for every port, with or without a device; only truly "connected"
            // (a device that is on and sending a signal) makes the input show up.
            var state = TvInputManager.INPUT_STATE_DISCONNECTED
            try {
                state = tim.getInputState(info.id)
            } catch (ignored: RuntimeException) {
            }
            if (!all && state != TvInputManager.INPUT_STATE_CONNECTED) continue
            var custom: CharSequence? = null
            try {
                custom = info.loadCustomLabel(c)
            } catch (ignored: RuntimeException) {
            }
            val reported = if (devices.containsKey(info.id)) devices[info.id]!! else ""
            val label = if (custom != null && custom.length > 0) custom.toString()
                else if (reported.isNotEmpty() && state == TvInputManager.INPUT_STATE_CONNECTED) reported
                else "HDMI " + number
            val symbol = if (state == TvInputManager.INPUT_STATE_CONNECTED || custom != null)
                deviceSymbol(label) else "hdmi"
            val item = shortcut(INPUT, "hdmi" + number, label, symbol)
            item.inputId = info.id
            item.number = number.toString()
            item.connected = state == TvInputManager.INPUT_STATE_CONNECTED
            out.add(item)
        }
        out.sortWith(Comparator { a, b -> a.number!!.compareTo(b.number!!) })
        return out
    }

    /** Words in a device's name, by the icon they pick: a game console, a streaming box, a sound system, a TV box. */
    private val DEVICE_WORDS = arrayOf(
        arrayOf("gamepad", "playstation", "ps5", "ps4", "ps3", "xbox", "nintendo", "switch", "steam deck", "console",
                "game"),
        arrayOf("play", "apple tv", "chromecast", "fire tv", "firetv", "roku", "shield", "mi box", "tv box", "tvbox",
                "stick", "streaming", "google tv", "android tv"),
        arrayOf("speaker", "soundbar", "sound bar", "receiver", "denon", "yamaha", "marantz", "onkyo", "bose", "sonos",
                "jbl", "audio", "home theater"),
        arrayOf("tv", "cable", "set-top", "decoder", "decodificador", "tuner", "sky", "claro", "vivo"),
    )

    /** The icon for an HDMI device, from the words in its name; a name that says nothing (or a plain port) is the HDMI plug. */
    @JvmStatic
    fun deviceSymbol(name: String?): String {
        val n = if (name == null) "" else name.lowercase(Locale.ROOT)
        for (group in DEVICE_WORDS) {
            for (i in 1 until group.size) if (n.contains(group[i])) return group[0]
        }
        return "hdmi"
    }

    /** Installed, enabled TV apps that are not on the home screen (the TV's Settings is among them), plus our own. */
    @JvmStatic
    fun others(c: Context): MutableList<Item> {
        val skip = HashSet<String>()
        // A built-in app is skipped only while it is on the shelf: one the person took off must still be listed here.
        val onShelf = shelfPackages(c)
        for (d in SHELF) if (onShelf.contains(d[2])) skip.add(d[2])
        skip.addAll(LIVE_TV_APPS)
        skip.add(c.packageName)
        skip.addAll(HIDDEN)
        val items = ArrayList<Item>()
        val seen = HashSet<String>()
        for (app in launchable(c)) {
            val pkg = app[0]
            if (skip.contains(pkg) || !seen.add(pkg)) continue
            val item = Item()
            item.kind = APP
            item.pkg = pkg
            item.key = pkg
            item.label = app[1]
            item.tile = appTile(c, null, pkg, 0)
            items.add(item)
        }
        items.sortWith(Comparator { a, b ->
            a.label!!.lowercase(Locale.ROOT).compareTo(b.label!!.lowercase(Locale.ROOT))
        })
        synchronized(HomeApps::class.java) {
            TILES.keys.removeIf { k -> k.contains(".") && !seen.contains(k) }
        }
        items.add(special(c, SETTINGS, "beamstation-settings", Config.APP_NAME, "mark"))
        return items
    }

    private fun special(c: Context, kind: Int, key: String, label: String?, symbol: String?): Item {
        val item = shortcut(kind, key, label, symbol)
        item.tile = symbolTile(c, key, symbol)
        return item
    }

    /** Rokid's own Settings has the glasses' pages; Android TV's, which ACTION_SETTINGS opens there, has none. */
    private val ROKID_SETTINGS = ComponentName("com.rokid.erbox.settings",
            "com.rokid.erbox.settings.MainActivity")

    /**
     * The TV's own settings. XGIMI has no settings activity: its Settings is a service that the remote's key starts. On
     * the Rokid Station, Rokid's.
     */
    @JvmStatic
    fun openTvSettings(c: Context): Boolean {
        if (HomeMode.XGIMI) return Xgimi.openSettings(c)
        if (HomeMode.ROKID) {
            try {
                c.startActivity(Intent(Intent.ACTION_MAIN).setComponent(ROKID_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: RuntimeException) {   // only ActivityNotFoundException and SecurityException, as before
                if (!cannotStart(e)) throw e
                AppLog.w("couldn't open Rokid's settings: " + e.message)
            }
        }
        try {
            c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (e: RuntimeException) {   // only ActivityNotFoundException and SecurityException, as before
            if (!cannotStart(e)) throw e
            AppLog.w("couldn't open the TV's settings: " + e.message)
            return false
        }
    }

    /** What startActivity throws when the activity can't be opened (the Java caught exactly these two). */
    private fun cannotStart(e: RuntimeException) = e is ActivityNotFoundException || e is SecurityException

    /** Opens the item with the system zoom transition growing out of the card (the app starts opening right away). */
    @JvmStatic
    fun open(a: Activity, item: Item, from: View, x: Int, y: Int, w: Int, h: Int): Boolean {
        var intent: Intent?
        var plain: Intent? = null   // plain: the app's own home, when it won't take the title's address
        when (item.kind) {
            APP -> {
                // The SDK takes no null package here (Java passed it on; no APP item is without one): none opens nothing.
                val pkg = item.pkg
                intent = if (pkg != null) launchIntent(a.packageManager, pkg) else null
                plain = intent
                if (plain != null) plain.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val uri = item.uri
                if (intent != null && uri != null) {
                    intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
                            .setPackage(item.pkg)
                }
            }
            LIVE_TV -> intent = liveTv(a)
            INPUT -> intent = if (HomeMode.XGIMI) Xgimi.hdmi(item.inputId)
                    else Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(item.inputId))
            SETTINGS -> intent = Intent().setComponent(ComponentName(a, SettingsActivity::class.java))
            ANDROID_SETTINGS -> {
                if (HomeMode.XGIMI || HomeMode.ROKID) return openTvSettings(a)
                intent = Intent(Settings.ACTION_SETTINGS)
            }
            FOCUS -> return Xgimi.autoFocus(a)
            KEYSTONE -> return Xgimi.autoKeystone(a)
            else -> return false
        }
        if (intent == null) return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            a.startActivity(intent,
                    ActivityOptions.makeScaleUpAnimation(from, x, y, Math.max(w, 1), Math.max(h, 1)).toBundle())
            return true
        } catch (e: RuntimeException) {   // only ActivityNotFoundException and SecurityException, as before
            if (!cannotStart(e)) throw e
            AppLog.w("couldn't open " + item.label + ": " + e.message)
            if (plain == null || plain === intent) return false
            plain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                a.startActivity(plain)
                return true
            } catch (e2: RuntimeException) {
                if (!cannotStart(e2)) throw e2
                return false
            }
        }
    }

    /** Live TV: the antenna input's channel list (the standard Android request), else a live TV app. */
    @JvmStatic
    fun liveTv(c: Context): Intent? {
        // Projectors have no tuner (an app's preview channels, VLC's, would count as one).
        if (HomeMode.XGIMI) return null
        val pm = c.packageManager
        val tuner = tunerInput(c)
        if (tuner != null) {
            val channels = Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner))
            if (channels.resolveActivity(pm) != null) return channels
        }
        for (pkg in LIVE_TV_APPS) {
            val app = launchIntent(pm, pkg)
            if (app != null) return app
        }
        return null
    }

    /**
     * The TV's antenna (tuner) input, or null. The TV maker's hardware tuner comes first: system apps also declare
     * tuner inputs (AirPlay, on some TVs), and Android names the hardware ones "…/HW<id>".
     */
    @JvmStatic
    fun tunerInput(c: Context): String? {
        val tim = c.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager? ?: return null
        var system: String? = null
        var fallback: String? = null
        try {
            for (info in tim.tvInputList) {
                if (info.type != TvInputInfo.TYPE_TUNER || info.isPassthroughInput) continue
                if (info.serviceInfo != null && info.serviceInfo.applicationInfo != null
                        && (info.serviceInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                    if (info.id.contains("/HW")) return info.id
                    if (system == null) system = info.id
                }
                if (fallback == null) fallback = info.id
            }
        } catch (ignored: RuntimeException) {
        }
        return system ?: fallback
    }

    @JvmStatic
    fun launchable(c: Context): MutableList<Array<String>> {
        val pm = c.packageManager
        val out = ArrayList<Array<String>>()
        val seen = HashSet<String>()
        val tv = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        for (ri in pm.queryIntentActivities(tv, 0)) {
            val pkg = ri.activityInfo.packageName
            if (seen.add(pkg)) out.add(arrayOf(pkg, valueOf(ri.loadLabel(pm))))
        }
        val phone = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(phone, 0)) {
            val pkg = ri.activityInfo.packageName
            if ((ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || !seen.add(pkg)) continue
            out.add(arrayOf(pkg, valueOf(ri.loadLabel(pm))))
        }
        return out
    }

    @JvmStatic
    fun launchIntent(pm: PackageManager, pkg: String): Intent? {
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
        return intent ?: pm.getLaunchIntentForPackage(pkg)
    }

    /** String.valueOf for a label: "null" for a null one, as the Java had it. */
    private fun valueOf(s: CharSequence?): String = s.toString()

    // MARK: - Colored logos (shelf)

    /** Bump when the way logos are lifted changes (the ones saved on disk are redone). */
    private const val LOGO_VERSION = 2

    private fun shelfLogo(c: Context, key: String, pkg: String, mode: String, extract: Boolean): Bitmap? {
        synchronized(LOGOS) {
            if (LOGOS.containsKey(key)) return LOGOS[key]
        }
        var logo = ownLogo(c, key)
        val file = logoFile(c, key, pkg)
        val none = File(file.path + ".none")
        if (logo == null && file.exists()) logo = BitmapFactory.decodeFile(file.path)
        if (logo == null) {
            if (none.exists()) {
                logo = null
            } else if (!extract) {
                return null
            } else {
                logo = bannerLogo(c, pkg, mode == "tint", mode == "play")
                saveLogo(logo, file, none)
            }
        }
        if (logo != null) logo.setHasMipMap(true)
        synchronized(LOGOS) {
            LOGOS[key] = logo
        }
        return logo
    }

    /** File for the logo lifted from the TV art: it changes when the app is updated (the art may have changed). */
    private fun logoFile(c: Context, key: String, pkg: String): File {
        var updated = 0L
        try {
            updated = c.packageManager.getPackageInfo(pkg, 0).lastUpdateTime
        } catch (ignored: PackageManager.NameNotFoundException) {
        }
        return File(File(c.cacheDir, "logos"), key + "-" + updated + "-v" + LOGO_VERSION + ".png")
    }

    private fun saveLogo(logo: Bitmap?, file: File, none: File) {
        val dir = file.parentFile
        dir.mkdirs()
        val key = file.name.substring(0, file.name.lastIndexOf('-', file.name.lastIndexOf("-v") - 1))
        val old = dir.listFiles(FilenameFilter { _, name ->
            name.matches(Regex(Pattern.quote(key) + "-\\d+-v\\d+\\.png(\\.none)?"))
        })
        if (old != null) {
            for (f in old) {
                f.delete()
            }
        }
        try {
            FileOutputStream(if (logo != null) file else none).use { out ->
                if (logo != null) logo.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: IOException) {
            AppLog.w("logo not saved: " + e.message)
        }
    }

    /** Fraction (0 to 1) of the logo's pixels that have ink, by sampling. */
    private fun ink(logo: Bitmap): Float {
        val w = logo.width
        val h = logo.height
        var hits = 0
        var total = 0
        for (y in 0 until h step Math.max(1, h / 48)) {
            for (x in 0 until w step Math.max(1, w / 96)) {
                if ((logo.getPixel(x, y) ushr 24) > 128) hits++
                total++
            }
        }
        return if (total == 0) 0.4f else Math.max(0.08f, hits.toFloat() / total)
    }

    /**
     * Logo lifted from the app's TV art: the background is removed (estimated from the edges, gradients are fine), the
     * brand colors stay and black turns white (the card is dark). With `tint`, a white logo on a colored background
     * takes the background's color; with `holes`, holes inside colored shapes (the play triangle) turn white. The
     * edges are "decontaminated" (no halo from the background).
     */
    private fun bannerLogo(c: Context, pkg: String, tint: Boolean, holes: Boolean): Bitmap? {
        val d = bannerDrawable(c, pkg) ?: return null
        val w = 768
        val h = 432
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(src)
        canvas.drawColor(0xFF15161C.toInt())
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        // Edge profiles (a 3% band), smoothed: the background at any point is the "patch" spanning the four sides.
        val band = Math.round(h * 0.03f)
        val left = Array(h) { FloatArray(3) }
        val right = Array(h) { FloatArray(3) }
        val top = Array(w) { FloatArray(3) }
        val bottom = Array(w) { FloatArray(3) }
        for (y in 0 until h) {
            for (x in 0 until band) {
                add(left[y], px[y * w + x], 1f / band)
                add(right[y], px[y * w + w - 1 - x], 1f / band)
            }
        }
        for (x in 0 until w) {
            for (y in 0 until band) {
                add(top[x], px[y * w + x], 1f / band)
                add(bottom[x], px[(h - 1 - y) * w + x], 1f / band)
            }
        }
        smooth(left, 25)
        smooth(right, 25)
        smooth(top, 41)
        smooth(bottom, 41)
        val p00 = top[0]
        val p10 = top[w - 1]
        val p01 = bottom[0]
        val p11 = bottom[w - 1]
        val mean = FloatArray(3)
        for (i in 0 until 3) {
            mean[i] = (p00[i] + p10[i] + p01[i] + p11[i] + top[w / 2][i] + bottom[w / 2][i] + left[h / 2][i] +
                    right[h / 2][i]) / 8
        }
        val bgLum = lum(mean)
        val bgSat = sat(mean)
        val out = IntArray(w * h)
        val bg = FloatArray(3)
        val col = FloatArray(3)
        var opaque = 0
        for (y in 0 until h) {
            val v = y.toFloat() / (h - 1)
            for (x in 0 until w) {
                val u = x.toFloat() / (w - 1)
                for (i in 0 until 3) {
                    bg[i] = (1 - v) * top[x][i] + v * bottom[x][i] + (1 - u) * left[y][i] + u * right[y][i] -
                            ((1 - u) * (1 - v) * p00[i] + u * (1 - v) * p10[i] + (1 - u) * v * p01[i] + u * v * p11[i])
                }
                val p = px[y * w + x]
                col[0] = ((p shr 16) and 255) / 255f
                col[1] = ((p shr 8) and 255) / 255f
                col[2] = (p and 255) / 255f
                val dist = Math.sqrt((((col[0] - bg[0]) * (col[0] - bg[0]) + (col[1] - bg[1]) * (col[1] - bg[1]) +
                        (col[2] - bg[2]) * (col[2] - bg[2])) / 3).toDouble()).toFloat()
                val a = smoothstep(0.07f, 0.22f, dist)
                if (a <= 0.004f) continue
                for (i in 0 until 3) col[i] = clamp((col[i] - (1 - a) * bg[i]) / a)   // no background halo
                val l = lum(col)
                val s = sat(col)
                if (bgLum > 0.6f && l < 0.42f && s < 0.3f) {
                    col.fill(1f)                                    // black text on a light background → white
                } else if (tint && bgLum <= 0.6f && bgLum > 0.1f && bgSat > 0.35f && l > 0.7f && s < 0.25f) {
                    vivid(bg, col)                                  // white logo on brand color → that color, vivid
                }
                out[y * w + x] = (Math.round(a * 255) shl 24) or (Math.round(col[0] * 255) shl 16) or
                        (Math.round(col[1] * 255) shl 8) or Math.round(col[2] * 255)
                if (a > 0.5f) opaque++
            }
        }
        val area = opaque.toFloat() / (w * h)
        if (area < 0.01f || area > 0.6f) return null    // couldn't split the logo from the background: keep the card
        if (holes) fillHoles(out, w, h)
        val logo = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return trimLogo(logo)
    }

    private fun bannerDrawable(c: Context, pkg: String): Drawable? {
        val pm = c.packageManager
        try {
            val launch = launchIntent(pm, pkg)
            val component = launch?.component
            if (launch != null && component != null) {
                val ai = pm.getActivityInfo(component, 0)
                val res = ai.bannerResource
                if (res != 0) {
                    val r = pm.getResourcesForApplication(ai.applicationInfo)
                    val value = TypedValue()
                    r.getValue(res, value, true)
                    val d = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null)
                    if (d != null) {
                        AppLog.w("TV banner for " + pkg + ": " + value.string + " (" + d.intrinsicWidth + "×" +
                                d.intrinsicHeight + ")")
                    }
                    return d
                }
            }
            return pm.getApplicationBanner(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
    }

    /** Closed holes inside colored shapes (the play triangle) turn white; the ones in letters don't. */
    private fun fillHoles(px: IntArray, w: Int, h: Int) {
        val mark = ByteArray(w * h)   // 1 = outside (reached from the border), 2 = hole already visited
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        for (x in 0 until w) {
            for (y in intArrayOf(0, h - 1)) {
                val i = y * w + x
                if (mark[i].toInt() == 0 && (px[i] ushr 24) < 128) { mark[i] = 1; queue[tail++] = i }
            }
        }
        for (y in 0 until h) {
            for (x in intArrayOf(0, w - 1)) {
                val i = y * w + x
                if (mark[i].toInt() == 0 && (px[i] ushr 24) < 128) { mark[i] = 1; queue[tail++] = i }
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            val next = intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1,
                    if (y < h - 1) i + w else -1)
            for (n in next) {
                if (n >= 0 && mark[n].toInt() == 0 && (px[n] ushr 24) < 128) { mark[n] = 1; queue[tail++] = n }
            }
        }
        for (start in 0 until w * h) {
            if (mark[start].toInt() != 0 || (px[start] ushr 24) >= 128) continue
            head = 0
            tail = 0
            queue[tail++] = start
            mark[start] = 2
            var satSum = 0f
            var border = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                val next = intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1,
                        if (y < h - 1) i + w else -1)
                for (n in next) {
                    if (n < 0) continue
                    if ((px[n] ushr 24) >= 128) {
                        val c = floatArrayOf(((px[n] shr 16) and 255) / 255f, ((px[n] shr 8) and 255) / 255f,
                                (px[n] and 255) / 255f)
                        satSum += sat(c)
                        border++
                    } else if (mark[n].toInt() == 0) {
                        mark[n] = 2
                        queue[tail++] = n
                    }
                }
            }
            if (border > 0 && satSum / border > 0.45f && tail < w * h / 20) {
                for (k in 0 until tail) px[queue[k]] = 0xFFFFFFFF.toInt()
            }
        }
    }

    private fun trimLogo(b: Bitmap): Bitmap {
        val t = trim(b)
        val pad = Math.max(2, Math.round(Math.max(t.width, t.height) * 0.02f))
        val out = Bitmap.createBitmap(t.width + 2 * pad, t.height + 2 * pad, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(t, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    private fun add(acc: FloatArray, p: Int, k: Float) {
        acc[0] += ((p shr 16) and 255) / 255f * k
        acc[1] += ((p shr 8) and 255) / 255f * k
        acc[2] += (p and 255) / 255f * k
    }

    /** Moving average (odd window) on each channel of the profile. */
    private fun smooth(profile: Array<FloatArray>, window: Int) {
        val n = profile.size
        val r = window / 2
        val copy = Array(n) { profile[it].clone() }
        for (i in 0 until n) {
            val sum = FloatArray(3)
            var count = 0
            for (j in Math.max(0, i - r)..Math.min(n - 1, i + r)) {
                for (k in 0 until 3) sum[k] += copy[j][k]
                count++
            }
            for (k in 0 until 3) profile[i][k] = sum[k] / count
        }
    }

    private fun lum(c: FloatArray): Float = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    private fun sat(c: FloatArray): Float {
        val max = Math.max(c[0], Math.max(c[1], c[2]))
        val min = Math.min(c[0], Math.min(c[1], c[2]))
        return if (max <= 0f) 0f else (max - min) / max
    }

    /** The local background color (gradients included), vivid and bright: the logo stands out on the dark card. */
    private fun vivid(bg: FloatArray, out: FloatArray) {
        val hsv = FloatArray(3)
        Color.RGBToHSV(Math.round(bg[0] * 255), Math.round(bg[1] * 255), Math.round(bg[2] * 255), hsv)
        hsv[1] = Math.max(hsv[1], 0.55f)
        hsv[2] = 1f
        val c = Color.HSVToColor(hsv)
        out[0] = ((c shr 16) and 255) / 255f
        out[1] = ((c shr 8) and 255) / 255f
        out[2] = (c and 255) / 255f
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp((x - e0) / (e1 - e0))
        return t * t * (3 - 2 * t)
    }

    private fun clamp(v: Float): Float = Math.max(0f, Math.min(1f, v))

    // MARK: - Cards

    @JvmStatic
    @Synchronized
    private fun appTile(c: Context, key: String?, pkg: String, brand: Int): Bitmap? {
        val cacheKey = key ?: pkg
        if (TILES.containsKey(cacheKey)) return TILES[cacheKey]
        val logo = if (key == null) null else ownLogo(c, key)
        var tile = if (logo != null) logoTile(logo, brand) else bannerTile(c, pkg)
        if (tile == null) tile = iconTile(c, pkg)
        tile.setHasMipMap(true)
        TILES[cacheKey] = tile
        return tile
    }

    /** The app's official TV art (the Android TV banner), at the highest density the app has. */
    private fun bannerTile(c: Context, pkg: String): Bitmap? {
        val pm = c.packageManager
        var d: Drawable? = null
        try {
            val launch = launchIntent(pm, pkg)
            val component = launch?.component
            if (launch != null && component != null) {
                val ai = pm.getActivityInfo(component, 0)
                val res = ai.bannerResource
                if (res != 0) {
                    d = pm.getResourcesForApplication(ai.applicationInfo)
                            .getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null)
                }
            }
            if (d == null) d = pm.getApplicationBanner(pkg)
        } catch (ignored: PackageManager.NameNotFoundException) {
        } catch (ignored: RuntimeException) {
        }
        if (d == null) return null
        val b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        canvas.drawColor(0xFF15161C.toInt())   // banners with transparency sit on a dark background
        d.setBounds(0, 0, TILE_W, TILE_H)
        d.draw(canvas)
        return b
    }

    /** No banner: the app's icon (in its proper shape, with some margin) on a dark card. */
    private fun iconTile(c: Context, pkg: String): Bitmap {
        val b = darkTile()
        val canvas = Canvas(b)
        var icon: Drawable? = null
        try {
            val pm = c.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            if (ai.icon != 0) {
                try {
                    icon = pm.getResourcesForApplication(ai)
                            .getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null)
                } catch (ignored: PackageManager.NameNotFoundException) {
                } catch (ignored: RuntimeException) {
                }
            }
            if (icon == null) icon = pm.getApplicationIcon(ai)
        } catch (ignored: PackageManager.NameNotFoundException) {
        }
        if (icon == null) return b
        val size = Math.round(TILE_H * 0.56f)
        val left = (TILE_W - size) / 2f
        val top = (TILE_H - size) / 2f
        if (icon is AdaptiveIconDrawable) {
            // Adaptive icon: the layers clipped to a squircle, like an Apple icon.
            val clip = Path()
            clip.addRoundRect(RectF(left, top, left + size, top + size), size * 0.2237f, size * 0.2237f,
                    Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val pad = size / 4
            for (layer in arrayOf<Drawable?>(icon.background, icon.foreground)) {
                if (layer == null) continue
                layer.setBounds(Math.round(left) - pad, Math.round(top) - pad, Math.round(left) + size + pad,
                        Math.round(top) + size + pad)
                layer.draw(canvas)
            }
            canvas.restore()
        } else {
            // Legacy icon (a free-form drawing with transparency): smaller, no frame.
            val s = Math.round(TILE_H * 0.46f)
            icon.setBounds((TILE_W - s) / 2, (TILE_H - s) / 2, (TILE_W + s) / 2, (TILE_H + s) / 2)
            icon.draw(canvas)
        }
        return b
    }

    /** Settings and the like: the icon in white on a dark card. */
    @JvmStatic
    @Synchronized
    private fun symbolTile(c: Context, key: String, symbol: String?): Bitmap? {
        if (TILES.containsKey(key)) return TILES[key]
        val b = darkTile()
        Symbols.draw(c, Canvas(b), symbol, TILE_W / 2f, TILE_H / 2f, TILE_H * 0.36f, 0xF2FFFFFF.toInt())
        b.setHasMipMap(true)
        TILES[key] = b
        return b
    }

    /** The official logo (no background) on dark glass with a faint brand tint coming from below. */
    private fun logoTile(logo: Bitmap, brand: Int): Bitmap {
        val b = darkTile()
        val canvas = Canvas(b)
        val glow = Paint()
        glow.setShader(RadialGradient(TILE_W / 2f, TILE_H * 1.15f, TILE_W * 0.75f, Ui.alpha(brand, 0.38f), 0x00000000,
                Shader.TileMode.CLAMP))
        canvas.drawRect(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(), glow)
        val wide = logo.width > logo.height * 1.35f
        val maxW = TILE_W * (if (wide) 0.62f else 0.4f)
        val maxH = TILE_H * (if (wide) 0.42f else 0.56f)
        val scale = Math.min(maxW / logo.width, maxH / logo.height)
        val w = logo.width * scale
        val h = logo.height * scale
        canvas.drawBitmap(logo, null, RectF((TILE_W - w) / 2, (TILE_H - h) / 2, (TILE_W + w) / 2, (TILE_H + h) / 2),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return b
    }

    private fun darkTile(): Bitmap {
        val b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val bg = Paint()
        bg.setShader(LinearGradient(0f, 0f, 0f, TILE_H.toFloat(), 0xFF2B2E3A.toInt(), 0xFF17191F.toInt(),
                Shader.TileMode.CLAMP))
        Canvas(b).drawRect(0f, 0f, TILE_W.toFloat(), TILE_H.toFloat(), bg)
        return b
    }

    /** Official logo (tools/logos.py): trims the transparent margins and scales it down to at most 512 px. */
    /** The bundled logo, read once per process: reading it again on every load made a new image and filled caches. */
    private fun ownLogoCached(c: Context, key: String): Bitmap? {
        synchronized(LOGOS) {
            if (LOGOS.containsKey("own:" + key)) return LOGOS["own:" + key]
        }
        val b = ownLogo(c, key)
        synchronized(LOGOS) {
            LOGOS["own:" + key] = b
        }
        return b
    }

    private fun ownLogo(c: Context, key: String): Bitmap? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            c.assets.open("logos/" + key + ".png").use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            val opts = BitmapFactory.Options()
            opts.inSampleSize = 1
            val largest = Math.max(bounds.outWidth, bounds.outHeight)
            while (largest / (opts.inSampleSize * 2) >= 640) opts.inSampleSize *= 2
            var b: Bitmap? = c.assets.open("logos/" + key + ".png").use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
            if (b == null) return null
            b = trim(b)
            val scale = Math.min(1f, 512f / Math.max(b.width, b.height))
            if (scale < 1f) {
                b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.width * scale)),
                        Math.max(1, Math.round(b.height * scale)), true)
            }
            return b
        } catch (e: IOException) {
            return null
        }
    }

    private fun trim(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        val row = IntArray(w)
        var top = h
        var bottom = -1
        var left = w
        var right = -1
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((row[x] ushr 24) > 10) {
                    if (y < top) top = y
                    bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (bottom < 0) return b
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1)
    }
}
