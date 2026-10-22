package io.github.tuthan.beamstation

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilenameFilter
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
internal class Recs private constructor(c: Context) {
    class Pick {
        @JvmField var id = 0
        @JvmField var kind: String? = null            // "movie" or "tv"
        @JvmField var title: String? = null
        @JvmField var backdrop: String? = null
        @JvmField var meta: String? = ""
        @JvmField var app: String? = null
        @JvmField var pkg: String? = null
        // opens the title in its app (looked up with Watchmode), or null: the app's home
        @JvmField var link: String? = null
        @JvmField var detailed = false
    }

    interface Listener {
        fun onRecs()
    }

    private val context: Context
    private val cache: SharedPreferences
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners: MutableList<Listener> = ArrayList()
    private val images: MutableMap<String?, Backdrop> = LinkedHashMap()   // this hour's, built and ready to draw
    private val pending = HashSet<String>()
    @Volatile private var picks: List<Pick> = ArrayList()
    @Volatile private var period = -1
    @Volatile private var listAt = 0L
    @Volatile private var fetching = false
    @Volatile private var waitingLogged = false
    @Volatile private var error = ""
    @Volatile private var light = false     // light design: builds only the cards (all three tabs' cards fit in memory)
    @Volatile private var failedAt = 0L
    @Volatile private var keyStamp = 0L
    @Volatile private var linkRetryAt = 0L
    @Volatile private var listLanguage: String? = ""   // language used for the saved titles, genres and texts

    init {
        context = c
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE)
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply()
        try {
            val saved = cache.getString("picks", null)
            if (saved != null) picks = parse(JSONArray(saved))
            period = cache.getInt("period", -1)
            listAt = cache.getLong("listAt", 0)
            listLanguage = cache.getString("language", if (cache.getBoolean("english", false)) "en" else "pt")
            keyStamp = cache.getLong("keyStamp", 0)
        } catch (ignored: Exception) {
        }
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun writeStatus() {
        try {
            FileOutputStream(File(context.filesDir, "recs-status.json")).use { out ->
                out.write(JSONObject().put("count", picks.size).put("error", error)
                    .put("at", System.currentTimeMillis()).toString().toByteArray(StandardCharsets.UTF_8))
            }
        } catch (ignored: Exception) {
        }
    }

    fun error(): String = error

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    fun now(): List<Pick> = now(null)

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    fun now(kind: String?): MutableList<Pick> {
        val list: MutableList<Pick> = ArrayList()
        if (!hasKey(context)) return list    // no TMDB key: no recommendations
        val have = HashMap<String, Boolean>()   // one look per app, not per title (this runs every 15 s)
        for (p in picks) {
            val pkg = p.pkg
            if ((kind == null || kind == p.kind) &&
                (!p.detailed || (pkg != null && have.computeIfAbsent(pkg) { installed(it) }))
            ) list.add(p)   // app missing: skip
        }
        if (list.isEmpty()) return list
        val hour = System.currentTimeMillis() / 3600_000L
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        val out: MutableList<Pick> = ArrayList()
        val seen = HashSet<String>()
        var i = 0
        while (i < list.size && out.size < 3) {
            val p = list[((hour * 3 + i) % list.size).toInt()]
            if (seen.add(franchise(p.title))) out.add(p)
            i++
        }
        return out
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    fun backdrop(p: Pick?): Backdrop? {
        val key = p?.backdrop ?: return null    // the details may swap the image midway
        synchronized(images) {
            val b = images[key]
            if (b != null) return b
            if (!pending.add(key)) return null
        }
        io.execute {
            try {
                loadImage(p)
            } finally {
                synchronized(images) { pending.remove(key) }
            }
        }
        return null
    }

    private fun hasImage(p: Pick): Boolean = p.backdrop != null && imageFile(p).exists()

    private fun imageFile(p: Pick): File = File(File(context.cacheDir, "recs"), p.backdrop!!.replace("/", ""))

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    fun refreshIfStale() {
        if (!hasKey(context) || fetching) return
        if (!HomeData.networkUp(context)) {
            // No back-off and no stamps touched: the next tick fetches as soon as the network is up.
            if (!waitingLogged) AppLog.w("recommendations: waiting for the network")
            waitingLogged = true
            return
        }
        waitingLogged = false
        val now = System.currentTimeMillis()
        val stamp = sourceStamp()   // a new key, country, animation choice or installed streaming-app set refetches
        val newKey = stamp != keyStamp
        val sinceFail = now - failedAt    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0L && sinceFail >= 0 && sinceFail < 10 * 60_000L) return
        val p = period(now)
        val stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty() ||
            listLanguage != Ui.language
        var missing = false
        for (pick in wanted()) missing = missing or (!pick.detailed || !hasImage(pick) || needsLink(pick))
        if (!stale && !missing) return
        keyStamp = stamp
        fetching = true
        io.execute {
            try {
                if (stale) fetchList(p)
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                var failures = 0
                var tried = 0
                var last = ""
                for (pick in wanted()) {
                    tried++
                    try {
                        if (!pick.detailed) fetchDetails(pick)
                        if (pick.pkg != null) download(pick)
                    } catch (e: Exception) {
                        failures++
                        last = e.message ?: e.toString()
                    }
                    findLink(pick)
                }
                save()
                if (tried > 0 && failures == tried) throw IOException(last)
                error = ""
                failedAt = 0
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                failedAt = System.currentTimeMillis()
                AppLog.w("recommendations: " + error)
            } finally {
                fetching = false
                writeStatus()
                notifyListeners()
            }
        }
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    fun setLight(value: Boolean) {
        if (value == light) return
        light = value
        synchronized(images) {
            images.clear()
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    fun showcase(): MutableList<Pick> {
        val out: MutableList<Pick> = ArrayList()
        if (!hasKey(context)) return out
        val seen = HashSet<String>()
        for (p in wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p)
        val have = HashMap<String, Boolean>()
        for (p in picks) {
            val pkg = p.pkg
            if (p.detailed && pkg != null && have.computeIfAbsent(pkg) { installed(it) } && hasImage(p) &&
                seen.add(franchise(p.title))
            ) out.add(p)
        }
        return out
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    fun readyImage(p: Pick): File? = if (hasImage(p)) imageFile(p) else null

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    fun prefetchAll() {
        if (!hasKey(context) || fetching) return
        val sinceFail = System.currentTimeMillis() - failedAt
        if (failedAt != 0L && sinceFail >= 0 && sinceFail < 10 * 60_000L) return
        val todo: MutableList<Pick> = ArrayList()
        for (p in picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p)
        if (todo.isEmpty()) return
        fetching = true
        io.execute {
            var failures = 0
            var last = ""
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (p in todo) {
                    try {
                        if (!p.detailed) fetchDetails(p)
                        if (p.pkg != null) download(p)
                    } catch (e: Exception) {
                        failures++
                        last = e.message ?: e.toString()
                    }
                }
                save()
                if (failures == todo.size) {
                    error = last
                    failedAt = System.currentTimeMillis()
                    AppLog.w("recommendations (screensaver): " + last)
                }
            } finally {
                fetching = false
                notifyListeners()
            }
        }
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    fun wanted(): MutableList<Pick> {
        val out: MutableList<Pick> = ArrayList()
        val seen = HashSet<String>()
        for (kind in arrayOf(null, "movie", "tv")) {
            for (p in now(kind)) if (seen.add(key(p))) out.add(p)
        }
        return out
    }

    private fun notifyListeners() {
        main.post { for (l in ArrayList(listeners)) l.onRecs() }
    }

    // MARK: - TMDB

    private fun fetchList(p: Int) {
        // The services the TV has, by their TMDB ids in your region.
        val services = HashMap<Int, Array<String>>()
        val providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION)
            .getJSONArray("results")
        for (i in 0 until providers.length()) {
            val o = providers.getJSONObject(i)
            val s = service(o.optString("provider_name"))
            if (s != null && installed(s[2])) services[o.getInt("provider_id")] = s
        }
        if (services.isEmpty()) throw IOException("none of the TV's streaming services are on TMDB")
        val ids = StringBuilder()
        for (id in services.keys) ids.append(if (ids.length == 0) "" else "|").append(id)
        val common = "&language=" + lang() + "&watch_region=" + REGION +
            "&with_watch_monetization_types=flatrate&include_adult=false" +
            "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8") +
            (if (Config.HIDE_ANIMATION) "&without_genres=" + ANIMATION else "")
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        val hotMovies: MutableList<Pick> = ArrayList()
        val hotShows: MutableList<Pick> = ArrayList()
        for (kind in arrayOf("movie", "tv")) {
            val hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results")
            val candidates: MutableList<Pick> = ArrayList()
            var i = 0
            while (i < hot.length() && i < 20) {
                add(candidates, hot.getJSONObject(i), kind)
                i++
            }
            for (pick in candidates) {
                try {
                    fetchDetails(pick)
                } catch (e: Exception) {
                    continue
                }
                if (pick.pkg != null) (if (kind == "movie") hotMovies else hotShows).add(pick)
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        val movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365) +
            "&primary_release_date.lte=" + day(0) + common).getJSONArray("results")
        val shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0) +
            common).getJSONArray("results")
        val newMovies: MutableList<Pick> = ArrayList()
        val newShows: MutableList<Pick> = ArrayList()
        for (i in 0 until movies.length()) add(newMovies, movies.getJSONObject(i), "movie")
        for (i in 0 until shows.length()) add(newShows, shows.getJSONObject(i), "tv")
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        val list: MutableList<Pick> = ArrayList()
        val seen = HashSet<String>()
        alternate(list, seen, hotMovies, hotShows, 24)
        alternate(list, seen, newMovies, newShows, 24)
        if (list.isEmpty()) throw IOException("TMDB returned no recommendations")
        picks = list
        period = p
        listAt = System.currentTimeMillis()
        listLanguage = Ui.language
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private fun fetchDetails(p: Pick) {
        val o = get("/" + p.kind + "/" + p.id + "?language=" + lang() +
            "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language + ",en")
        p.title = title(p, o)
        val best = bestBackdrop(o.optJSONObject("images"))
        if (best != null) p.backdrop = best
        val parts: MutableList<String?> = ArrayList()
        val genres: JSONArray? = o.optJSONArray("genres")
        if (p.kind == "tv") parts.add(Ui.tr("Série", "Series"))
        if (genres != null && genres.length() > 0) {
            val name = genres.getJSONObject(0).optString("name")
            parts.add(if (Ui.en) name else genre(name))
        }
        if (p.kind == "movie") {
            val minutes = o.optInt("runtime")
            if (minutes > 0 && Ui.ru) {
                parts.add(if (minutes >= 60) "${minutes / 60} ч ${minutes % 60} мин" else "$minutes мин")
            } else if (minutes > 0 && Ui.en) {
                parts.add(if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m")
            } else if (minutes > 0) {
                parts.add(if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min")
            }
        } else {
            val seasons = o.optInt("number_of_seasons")
            if (seasons > 0 && Ui.ru) parts.add(Russian.count(seasons.toLong(), "сезон", "сезона", "сезонов"))
            else if (seasons > 0 && Ui.en) parts.add(if (seasons == 1) "1 season" else "$seasons seasons")
            else if (seasons > 0 && Ui.de) parts.add(if (seasons == 1) "1 Staffel" else "$seasons Staffeln")
            else if (seasons > 0) parts.add(if (seasons == 1) "1 temporada" else "$seasons temporadas")
        }
        val wp: JSONObject? = o.optJSONObject("watch/providers")
        val br: JSONObject? = wp?.optJSONObject("results")?.optJSONObject(REGION)
        val flat: JSONArray? = br?.optJSONArray("flatrate")
        var i = 0
        while (flat != null && i < flat.length() && p.app == null) {
            val s = service(flat.getJSONObject(i).optString("provider_name"))
            if (s != null && installed(s[2])) {
                p.app = s[1]
                p.pkg = s[2]
            }
            i++
        }
        val app = p.app
        if (app != null) parts.add(SERVICE_NAME[app])
        p.meta = parts.joinToString(" · ")
        p.detailed = true
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private fun title(p: Pick, o: JSONObject): String? {
        val field = if (p.kind == "movie") "title" else "name"
        val own = o.optString(field, p.title)
        val original = o.optString("original_language")
        if (Ui.en || own.isEmpty() || own != o.optString("original_" + field) ||
            Ui.language == original || "en" == original
        ) return own
        return try {
            val english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field)
            if (english.isEmpty()) own else english
        } catch (e: Exception) {
            own
        }
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private fun service(name: String): Array<String>? {
        val n = name.lowercase(Locale.ROOT)
        var first: Array<String>? = null
        for (s in SERVICES) {
            if (!(if (s[0] == "max") n.matches(Regex(".*\\bmax\\b.*")) else n.contains(s[0]))) continue
            if (installed(s[2])) return s
            if (first == null) first = s
        }
        return first
    }

    private fun installed(pkg: String): Boolean = HomeApps.launchIntent(context.packageManager, pkg) != null

    private fun sourceStamp(): Long {
        var stamp = (Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L + (if (Config.HIDE_ANIMATION) 1 else 0)
        for (service in SERVICES) stamp = stamp * 31L + (if (installed(service[2])) service[2].hashCode() else 0)
        return stamp
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private fun needsLink(p: Pick): Boolean = linksOn() && p.pkg != null && p.link == null &&
        cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private fun linksOn(): Boolean {
        val key = Config.WATCHMODE_KEY.trim { it <= ' ' }
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode()
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private fun findLink(p: Pick) {
        if (!linksOn() || p.pkg == null || p.link != null) return
        val saved = cache.getString(linkKey(p), null)
        if (saved != null) {
            p.link = if (saved.isEmpty()) null else saved
            return
        }
        if (System.currentTimeMillis() < linkRetryAt) return
        try {
            var link = ""
            val found: JSONArray? =
                JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results")
            if (found != null && found.length() > 0) {
                val sources = JSONArray(
                    watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION))
                var service: Array<String>? = null
                for (s in SERVICES) if (s[1] == p.app) service = s
                var i = 0
                while (service != null && i < sources.length() && link.isEmpty()) {
                    val s = sources.getJSONObject(i)
                    val name = s.optString("name").lowercase(Locale.ROOT).replace(" ", "")
                    val want = service[0].replace(" ", "")
                    val same = if (want == "max") name == "max" else name.contains(want)
                    if (same && s.optString("type") == "sub" && s.optString("web_url").startsWith("https://")) {
                        link = s.optString("web_url")
                    }
                    i++
                }
            }
            cache.edit().putString(linkKey(p), link).apply()
            p.link = if (link.isEmpty()) null else link
        } catch (e: Exception) {
            if ("Watchmode returned HTTP 401" == e.message) {
                cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim { it <= ' ' }.hashCode()).apply()
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L   // quota or network: not again right away
            AppLog.w("watchmode: " + e.message)
        }
    }

    private fun get(path: String): JSONObject {
        val key = readKey() ?: throw IOException("no TMDB key")
        val bearer = key.startsWith("eyJ")   // read access token (v4) or API key (v3)
        val url = API + path +
            (if (bearer) "" else (if (path.contains("?")) "&" else "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"))
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key)
        try {
            val code = c.responseCode
            if (code == 401) throw IOException("TMDB rejected the key")
            if (code != 200) throw IOException("TMDB returned HTTP " + code)
            return JSONObject(read(c.inputStream))
        } finally {
            c.disconnect()
        }
    }

    private fun readKey(): String? {
        val key = Config.TMDB_KEY.trim { it <= ' ' }
        return if (key.isEmpty()) null else key
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private fun download(p: Pick) {
        val f = imageFile(p)
        if (f.exists()) return
        f.parentFile.mkdirs()
        val part = File(f.path + ".part")
        val c = URL(IMAGES + p.backdrop).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 20000
        try {
            c.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(16384)
                    var n = input.read(buffer)
                    while (n > 0) {
                        out.write(buffer, 0, n)
                        n = input.read(buffer)
                    }
                }
            }
        } catch (e: IOException) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
        if (!part.renameTo(f)) throw IOException("couldn't save the image")
        prune(f.parentFile)
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private fun loadImage(p: Pick) {
        synchronized(images) {
            if (images.containsKey(p.backdrop)) return
        }
        val f = imageFile(p)
        try {
            download(p)
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, bounds)
            val opts = BitmapFactory.Options()
            opts.inSampleSize = 1
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2
            val b = BitmapFactory.decodeFile(f.path, opts) ?: throw IOException("unreadable image")
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " +
                Backdrop.W + "×" + Backdrop.H)
            val forLight = light
            val backdrop = Backdrop.make(b, forLight)
            if (forLight != light) return   // the design changed midway: this one is stale
            synchronized(images) {
                images[p.backdrop] = backdrop
                while (images.size > (if (forLight) 10 else 4)) images.remove(images.keys.iterator().next())
            }
            notifyListeners()
        } catch (e: Exception) {   // the Java caught IOException | RuntimeException: nothing else is thrown here
            f.delete()
            AppLog.w("recommendation image: " + e.message)
        } catch (e: OutOfMemoryError) {
            f.delete()
            AppLog.w("recommendation image: " + e.message)
        }
    }

    fun trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        val keep = HashSet<String>()
        for (p in now(null)) {
            val b = p.backdrop
            if (b != null) keep.add(b)
        }
        synchronized(images) {
            images.keys.retainAll(keep)
        }
    }

    // MARK: - List cache

    private fun save() {
        try {
            val a = JSONArray()
            for (p in picks) {
                a.put(JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                    .put("link", if (p.link == null) "" else p.link)
                    .put("meta", p.meta).put("app", if (p.app == null) "" else p.app)
                    .put("pkg", if (p.pkg == null) "" else p.pkg)
                    .put("detailed", p.detailed))
            }
            val e = cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                .putString("language", listLanguage)
                .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION)
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            val keep = HashSet<String>()
            for (p in picks) keep.add(linkKey(p))
            for (k in cache.all.keys) if (k.startsWith("wm:") && k != "wm:refused" && !keep.contains(k)) e.remove(k)
            e.apply()
        } catch (ignored: Exception) {
        }
    }

    companion object {
        private const val API = "https://api.themoviedb.org/3"
        /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
        private val IMAGES = "https://image.tmdb.org/t/p/" + (if (Backdrop.W <= 1280) "w1280" else "original")
        /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
        private const val ANIMATION = 16
        private const val LIST_EVERY = 6 * 3600_000L
        /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
        private const val CACHE_VERSION = 3
        private const val MANHA = 0
        private const val TARDE = 1
        private const val NOITE = 2
        @JvmField val PERIOD_LABEL = arrayOf("PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE")
        @JvmField val PERIOD_LABEL_RU = arrayOf("ЭТИМ УТРОМ", "СЕГОДНЯ ДНЁМ", "СЕГОДНЯ ВЕЧЕРОМ")
        @JvmField val PERIOD_LABEL_EN = arrayOf("THIS MORNING", "THIS AFTERNOON", "TONIGHT")
        @JvmField val PERIOD_LABEL_ES = arrayOf("PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE")
        @JvmField val PERIOD_LABEL_DE = arrayOf("HEUTE MORGEN", "HEUTE NACHMITTAG", "HEUTE ABEND")
        /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
        private val SERVICES = arrayOf(
            arrayOf("netflix", "netflix", "com.netflix.ninja"),
            arrayOf("prime video", "prime", "com.amazon.amazonvideo.livingroom"),
            arrayOf("disney", "disney", "com.disney.disneyplus"),
            arrayOf("hulu", "hulu", "com.hulu.livingroomplus"),
            arrayOf("paramount", "paramount", "com.cbs.ott"),
            arrayOf("peacock", "peacock", "com.peacocktv.peacockandroid"),
            arrayOf("globoplay", "globoplay", "com.globo.globotv"),
            arrayOf("max", "max", "com.wbd.stream"),
            arrayOf("max", "max", "com.hbo.hbonow"),
            arrayOf("apple tv", "appletv", "com.apple.atve.androidtv.appletv"),
            arrayOf("hotstar", "hotstar", "in.startv.hotstar"),   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
            arrayOf("okko", "okko", "ru.more.play"),
            arrayOf("crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"),
            arrayOf("oneplay", "oneplay", "net.cme.voyo.cz.tvapp"),  // Czechia: Voyo and O2 TV became Oneplay, same app
        )
        private val SERVICE_NAME: MutableMap<String, String> = HashMap()

        init {
            SERVICE_NAME["netflix"] = "Netflix"
            SERVICE_NAME["prime"] = "Prime Video"
            SERVICE_NAME["disney"] = "Disney+"
            SERVICE_NAME["hulu"] = "Hulu"
            SERVICE_NAME["paramount"] = "Paramount+"
            SERVICE_NAME["peacock"] = "Peacock"
            SERVICE_NAME["globoplay"] = "Globoplay"
            SERVICE_NAME["max"] = "Max"
            SERVICE_NAME["appletv"] = "Apple TV"
            SERVICE_NAME["hotstar"] = "JioHotstar"
            SERVICE_NAME["okko"] = "Okko"
            SERVICE_NAME["crunchyroll"] = "Crunchyroll"
            SERVICE_NAME["oneplay"] = "Oneplay"
        }

        /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
        private val REGION = Config.TMDB_REGION.trim { it <= ' ' }.uppercase(Locale.ROOT)

        private var instance: Recs? = null

        @JvmStatic
        @Synchronized
        fun get(c: Context): Recs {
            if (instance == null) instance = Recs(c.applicationContext)
            return instance!!
        }

        @JvmStatic
        fun hasKey(c: Context?): Boolean = !Config.TMDB_KEY.trim { it <= ' ' }.isEmpty()

        /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
        @JvmStatic
        fun status(c: Context): JSONObject {
            val f = File(c.filesDir, "recs-status.json")
            if (!f.exists() || !hasKey(c)) return JSONObject()
            return try {
                FileInputStream(f).use { JSONObject(read(it)) }
            } catch (e: Exception) {
                JSONObject()
            }
        }

        @JvmStatic
        fun periodLabel(): String =
            (if (Ui.ru) PERIOD_LABEL_RU else if (Ui.es) PERIOD_LABEL_ES else if (Ui.de) PERIOD_LABEL_DE
            else if (Ui.en) PERIOD_LABEL_EN else PERIOD_LABEL)[period(System.currentTimeMillis())]

        private fun period(now: Long): Int {
            val cal = Calendar.getInstance()
            cal.timeInMillis = now
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            return if (hour >= 5 && hour < 12) MANHA else if (hour >= 12 && hour < 18) TARDE else NOITE
        }

        /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
        private fun franchise(title: String?): String {
            val t = title?.lowercase(Locale.ROOT) ?: ""
            var cut = t.indexOf(':')
            if (cut < 0) cut = t.indexOf(" - ")
            return (if (cut > 0) t.substring(0, cut) else t).trim { it <= ' ' }
        }

        /** Name of the service the pick is on ("Netflix"), or empty. */
        @JvmStatic
        fun serviceName(p: Pick?): String? {
            val app = p?.app
            return if (app == null) "" else SERVICE_NAME[app]
        }

        /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
        @JvmStatic
        fun key(p: Pick): String = p.kind + ":" + p.id

        // MARK: - TMDB

        /** Language of titles and genres; the services come from the region. */
        private fun lang(): String {
            if (Ui.ru) return "ru-RU"
            if (Ui.es) return if ("ES" == Locale.getDefault().country) "es-ES" else "es-MX"
            if (Ui.de) return "de-DE"   // TMDB's de-AT and de-CH have few translations of their own
            return if (Ui.en) "en-US" else "pt-BR"
        }

        private fun alternate(
            out: MutableList<Pick>, seen: MutableSet<String>, a: List<Pick>, b: List<Pick>, max: Int,
        ) {
            var i = 0
            while (i < Math.max(a.size, b.size) && out.size < max) {
                if (i < a.size && seen.add(key(a[i]))) out.add(a[i])
                if (i < b.size && out.size < max && seen.add(key(b[i]))) out.add(b[i])
                i++
            }
        }

        /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
        private fun day(offset: Int): String {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, offset)
            return java.lang.String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        }

        private fun add(list: MutableList<Pick>, o: JSONObject, kind: String) {
            val backdrop = o.optString("backdrop_path", "")
            if (backdrop.isEmpty() || "null" == backdrop) return
            if (Config.HIDE_ANIMATION && animated(o)) return
            val p = Pick()
            p.id = o.optInt("id")
            p.kind = kind
            p.title = o.optString(if (kind == "movie") "title" else "name")
            p.backdrop = backdrop
            list.add(p)
        }

        private fun animated(o: JSONObject): Boolean {
            val genres: JSONArray? = o.optJSONArray("genre_ids")
            if (genres != null) for (i in 0 until genres.length()) if (genres.optInt(i) == ANIMATION) return true
            return false
        }

        /** TV genres that TMDB returns in English even in pt-BR. */
        private val GENRES_PT = arrayOf(
            arrayOf("Action & Adventure", "Ação e Aventura"),
            arrayOf("Sci-Fi & Fantasy", "Ficção Científica e Fantasia"),
            arrayOf("War & Politics", "Guerra e Política"), arrayOf("Kids", "Infantil"), arrayOf("News", "Notícias"),
            arrayOf("Soap", "Novela"), arrayOf("Talk", "Entrevistas"),
        )
        private val GENRES_ES = arrayOf(
            arrayOf("Action & Adventure", "Acción y Aventura"),
            arrayOf("Sci-Fi & Fantasy", "Ciencia ficción y Fantasía"),
            arrayOf("War & Politics", "Guerra y Política"), arrayOf("Kids", "Infantil"), arrayOf("News", "Noticias"),
            arrayOf("Soap", "Telenovela"), arrayOf("Talk", "Entrevistas"),
        )
        private val GENRES_DE = arrayOf(
            arrayOf("Action & Adventure", "Action & Abenteuer"), arrayOf("War & Politics", "Krieg & Politik"),
            arrayOf("Kids", "Kinder"), arrayOf("News", "Nachrichten"), arrayOf("Soap", "Seifenoper"),
            arrayOf("Talk", "Talkshow"),
        )

        private fun genre(name: String): String {
            if (Ui.ru) return Russian.text(name)
            for (g in if (Ui.es) GENRES_ES else if (Ui.de) GENRES_DE else GENRES_PT) {
                if (g[0].equals(name, ignoreCase = true)) return g[1]
            }
            return name
        }

        /**
         * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
         * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
         */
        private fun bestBackdrop(images: JSONObject?): String? {
            val list: JSONArray? = images?.optJSONArray("backdrops")
            var best: String? = null
            var bestScore = -1.0
            if (list != null) for (i in 0 until list.length()) {
                val b: JSONObject? = list.optJSONObject(i)
                if (b == null || b.optString("file_path", "").isEmpty()) continue
                val textless = b.isNull("iso_639_1")
                val width = b.optInt("width")
                val score = (if (textless) 1e7 else 0.0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) +
                    b.optDouble("vote_average", 0.0)
                if (score > bestScore) {
                    bestScore = score
                    best = b.optString("file_path")
                }
            }
            return best
        }

        // MARK: - Watchmode

        private fun linkKey(p: Pick): String = "wm:" + p.kind + p.id + p.app

        private fun watchmode(path: String): String {
            val c = URL("https://api.watchmode.com/v1" + path + (if (path.contains("?")) "&" else "?") +
                "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim { it <= ' ' }, "UTF-8"))
                .openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            try {
                val code = c.responseCode
                if (code != 200) throw IOException("Watchmode returned HTTP " + code)
                return read(c.inputStream)
            } finally {
                c.disconnect()
            }
        }

        // MARK: - Images

        private fun prune(dir: File) {
            // A download cut off when the system stopped the app leaves its .part behind.
            val stale = System.currentTimeMillis() - 10 * 60_000L
            val parts: Array<File>? = dir.listFiles(FilenameFilter { _, name -> name.endsWith(".part") })
            if (parts != null) for (part in parts) if (part.lastModified() < stale) part.delete()
            val files: Array<File>? = dir.listFiles(FilenameFilter { _, name -> !name.endsWith(".part") })
            if (files == null || files.size <= 24) return
            files.sortWith(Comparator { a, b -> java.lang.Long.compare(a.lastModified(), b.lastModified()) })
            for (j in 0 until files.size - 24) files[j].delete()
        }

        // MARK: - List cache

        private fun parse(a: JSONArray): MutableList<Pick> {
            val out: MutableList<Pick> = ArrayList()
            for (i in 0 until a.length()) {
                val o: JSONObject = a.optJSONObject(i) ?: continue
                val p = Pick()
                p.id = o.optInt("id")
                p.kind = o.optString("kind")
                p.title = o.optString("title")
                p.backdrop = o.optString("backdrop")
                p.link = if (o.optString("link").isEmpty()) null else o.optString("link")
                p.meta = o.optString("meta")
                p.app = if (o.optString("app").isEmpty()) null else o.optString("app")
                p.pkg = if (o.optString("pkg").isEmpty()) null else o.optString("pkg")
                p.detailed = o.optBoolean("detailed")
                out.add(p)
            }
            return out
        }

        private fun read(input: InputStream): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var n = input.read(buffer)
            while (n > 0) {
                out.write(buffer, 0, n)
                n = input.read(buffer)
            }
            return out.toString(StandardCharsets.UTF_8.name())
        }
    }
}
