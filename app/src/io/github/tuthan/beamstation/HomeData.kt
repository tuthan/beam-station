package io.github.tuthan.beamstation

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Optional crypto prices (Binance, every 5 min) and weather/sunrise/sunset (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * Neither source needs a key. Location and currency come from config.properties.
 */
internal class HomeData private constructor(c: Context) {
    interface Listener {
        fun onData()
    }

    class Weather {
        @JvmField var temp = Double.NaN
        @JvmField var max = Double.NaN
        @JvmField var min = Double.NaN
        @JvmField var code = -1
        @JvmField var isDay = true
        @JvmField var sunrise = 0L      // today's, in epoch ms
        @JvmField var sunset = 0L
        @JvmField var zone = ""         // the city's time zone (Olson id), as the forecast gives it
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        @JvmField val hours: MutableList<Long> = ArrayList()
        @JvmField val hourTemp: MutableList<Double> = ArrayList()
        @JvmField val hourCode: MutableList<Int> = ArrayList()
        @JvmField val hourDay: MutableList<Boolean> = ArrayList()
    }

    @JvmField val prices = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    /** 24 h change (%), in the same order. */
    @JvmField val changes = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    @JvmField @Volatile var pricesAt = 0L
    @JvmField @Volatile var weatherAt = 0L
    @JvmField @Volatile var weather = Weather()

    private val context: Context
    private val cache: SharedPreferences
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners: MutableList<Listener> = ArrayList()
    @Volatile private var fetchingPrices = false
    @Volatile private var fetchingWeather = false
    @Volatile private var waitingLogged = false
    @Volatile private var pricesFailedAt = 0L
    @Volatile private var weatherFailedAt = 0L

    init {
        context = c
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE)
        inDollars = CURRENCY == cache.getString("dollarsFor", "")
        try {
            if (Prefs.quotes()) {
                val p = cache.getString("prices", null)
                if (p != null) parsePrices(p)
                pricesAt = cache.getLong("pricesAt", 0)
            }
            val w = cache.getString("weather", null)
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace() == cache.getString("weatherPlace", "")) {
                weather = parseWeather(w)
                weatherAt = cache.getLong("weatherAt", 0)
            }
        } catch (ignored: Exception) {
        }
    }

    /** Settings changed (city, coins): fetch again now. */
    fun settingsChanged() {
        pricesAt = 0
        weatherAt = 0
        pricesFailedAt = 0
        weatherFailedAt = 0
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    fun refreshIfStale() {
        if (!networkUp(context)) {
            // Just woken, Wi-Fi not up yet: a fetch would fail on DNS and start a back-off. The next tick tries again.
            if (!waitingLogged) AppLog.w("prices, weather: waiting for the network")
            waitingLogged = true
            return
        }
        waitingLogged = false
        val now = System.currentTimeMillis()
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingPrices && coinCount() > 0 && (now - pricesAt > PRICES_EVERY || now < pricesAt) &&
            !failedRecently(pricesFailedAt, now)
        ) {
            fetchingPrices = true
            io.execute {
                try {
                    val body = try {
                        getPrices()
                    } catch (e: IOException) {
                        if (inDollars || CURRENCY == "USD" || "HTTP 400" != e.message) throw e
                        inDollars = true
                        cache.edit().putString("dollarsFor", CURRENCY).apply()
                        AppLog.w("prices: Binance has no " + CURRENCY + " pairs, using dollars")
                        getPrices()
                    }
                    parsePrices(body)
                    pricesAt = System.currentTimeMillis()
                    cache.edit().putString("prices", body).putLong("pricesAt", pricesAt).apply()
                    notifyListeners()
                } catch (e: Exception) {
                    pricesFailedAt = System.currentTimeMillis()
                    AppLog.w("prices: " + e.message)
                } finally {
                    fetchingPrices = false
                }
            }
        }
        if (!fetchingWeather && (now - weatherAt > WEATHER_EVERY || now < weatherAt) &&
            !failedRecently(weatherFailedAt, now)
        ) {
            fetchingWeather = true
            io.execute {
                try {
                    val body = get(weatherUrl())
                    weather = parseWeather(body)
                    weatherAt = System.currentTimeMillis()
                    cache.edit().putString("weather", body).putLong("weatherAt", weatherAt)
                        .putString("weatherPlace", weatherPlace()).apply()
                    notifyListeners()
                } catch (e: Exception) {
                    weatherFailedAt = System.currentTimeMillis()
                    AppLog.w("weather: " + e.message)
                } finally {
                    fetchingWeather = false
                }
            }
        }
    }

    private fun notifyListeners() {
        main.post { for (l in ArrayList(listeners)) l.onData() }
    }

    private fun parsePrices(body: String) {
        val list = JSONArray(body)
        val quote = quote()
        var found = 0
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val symbol = o.getString("symbol")
            for (k in COINS.indices) {
                if (symbol != COINS[k] + quote) continue
                found++
                val last = o.optString("lastPrice", o.optString("price", "NaN")).toDouble()
                val open = o.optString("openPrice", "NaN").toDouble()
                prices[k] = last
                changes[k] = if (open > 0) (last - open) / open * 100 else Double.NaN
            }
        }
        if (found == 0) pricesAt = 0   // cached in another currency (config changed): fetch again
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    fun isNight(now: Long): Boolean {
        val theme = Prefs.theme()
        if (theme == "dark") return true
        if (theme == "light") return false
        val t = Shaders.timeOfDay(now, 0)
        val rise = Shaders.timeOfDay(weather.sunrise, 6 * 60 * 60_000L)
        val set = Shaders.timeOfDay(weather.sunset, 18 * 60 * 60_000L)
        return t < rise || t >= set
    }

    companion object {
        @JvmField val COINS = arrayOf("BTC", "ETH", "SOL", "USDT")
        @JvmField val COIN_NAMES = arrayOf("Bitcoin", "Ethereum", "Solana", "Dollar")
        private val CURRENCY = Config.CURRENCY.trim { it <= ' ' }.uppercase(Locale.ROOT)
        /**
         * Binance has no pairs in some currencies (INR...) and then refuses the whole request: prices go in dollars
         * instead.
         */
        @Volatile private var inDollars = false

        /** Binance quotes dollars as USDT. */
        private fun quote(): String = if (CURRENCY == "USD" || inDollars) "USDT" else CURRENCY

        private const val WEATHER_QUERY =
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2"

        private fun weatherPlace(): String = Prefs.latitude() + "," + Prefs.longitude()

        private fun weatherUrl(): String = "https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude() +
            "&longitude=" + Prefs.longitude() + WEATHER_QUERY

        private const val PRICES_EVERY = 5 * 60_000L
        private const val WEATHER_EVERY = 30 * 60_000L

        /**
         * BTC, ETH and SOL; in Brazilian reais also the dollar (USDT/BRL), which Binance doesn't list for most
         * currencies.
         */
        @JvmStatic
        fun coinCount(): Int = if (Prefs.quotes()) active().size else 0

        @Volatile private var activeCoins: IntArray? = null
        @Volatile private var activeVersion = -1

        /** Indexes in COINS of the coins the user has on (and that Binance lists in this currency). */
        @JvmStatic
        fun active(): IntArray {
            var a = activeCoins
            if (a != null && activeVersion == Prefs.version) return a
            var n = 0
            val tmp = IntArray(COINS.size)
            for (i in COINS.indices) if (Prefs.coin(i) && (i < 3 || CURRENCY == "BRL")) tmp[n++] = i
            a = tmp.copyOf(n)
            activeCoins = a
            activeVersion = Prefs.version
            return a
        }

        @JvmStatic
        fun coinName(i: Int): String = if (i == 3) Ui.tr("Dólar", "Dollar") else COIN_NAMES[i]

        /** Binance.com refuses the US (HTTP 451); Binance.US answers the same call in dollars. */
        private val PRICE_HOSTS = arrayOf("https://api.binance.com", "https://api.binance.us")

        private fun pricesPath(): String {
            val symbols = StringBuilder()
            val coins = active()
            for (i in coins.indices) {
                symbols.append(if (i == 0) "" else ",").append("%22").append(COINS[coins[i]]).append(quote())
                    .append("%22")
            }
            return "/api/v3/ticker/24hr?type=MINI&symbols=%5B" + symbols + "%5D"
        }

        private fun getPrices(): String {
            var failed: IOException? = null
            for (host in PRICE_HOSTS) {
                try {
                    return get(host + pricesPath())
                } catch (e: IOException) {
                    failed = e
                    // A pair Binance doesn't list (HTTP 400) isn't a refusal: the caller falls back to dollars instead.
                    if ("HTTP 400" == e.message) break
                }
            }
            throw failed!!
        }

        private var instance: HomeData? = null

        @JvmStatic
        @Synchronized
        fun get(c: Context): HomeData {
            if (instance == null) instance = HomeData(c.applicationContext)
            return instance!!
        }

        /** True when the active network has been validated (it reaches the internet); also when the TV can't tell. */
        @JvmStatic
        fun networkUp(c: Context): Boolean {
            try {
                val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager? ?: return true
                val n = cm.activeNetwork
                val caps = if (n == null) null else cm.getNetworkCapabilities(n)
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } catch (e: RuntimeException) {
                return true
            }
        }

        private fun failedRecently(at: Long, now: Long): Boolean = at != 0L && now >= at && now - at < 2 * 60_000L

        private fun parseWeather(body: String): Weather {
            val o = JSONObject(body)
            val zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().id))
            val w = Weather()
            w.zone = o.optString("timezone", "")
            val current = o.getJSONObject("current")
            w.temp = current.getDouble("temperature_2m")
            w.code = current.getInt("weather_code")
            w.isDay = current.optInt("is_day", 1) == 1
            val daily = o.getJSONObject("daily")
            w.max = daily.getJSONArray("temperature_2m_max").getDouble(0)
            w.min = daily.getJSONArray("temperature_2m_min").getDouble(0)
            w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone)
            w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone)
            val hourly: JSONObject? = o.optJSONObject("hourly")
            if (hourly != null) {
                val times = hourly.getJSONArray("time")
                val temps = hourly.getJSONArray("temperature_2m")
                val codes = hourly.getJSONArray("weather_code")
                val days: JSONArray? = hourly.optJSONArray("is_day")
                val now = System.currentTimeMillis()
                for (i in 0 until times.length()) {
                    if (w.hours.size >= 6) break
                    val at = localTime(times.getString(i), zone)
                    if (at <= now) continue
                    w.hours.add(at)
                    w.hourTemp.add(temps.getDouble(i))
                    w.hourCode.add(codes.getInt(i))
                    w.hourDay.add(days == null || days.optInt(i, 1) == 1)
                }
            }
            return w
        }

        private fun localTime(iso: String, zone: TimeZone): Long {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
            f.timeZone = zone
            return f.parse(iso)!!.time
        }

        @JvmStatic
        @Throws(Exception::class)
        fun findCity(name: String): MutableList<Array<String>> {
            val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" +
                Ui.language + "&name=" + URLEncoder.encode(name.trim { it <= ' ' }, "UTF-8")))
            val results: JSONArray? = o.optJSONArray("results")
            val out: MutableList<Array<String>> = ArrayList()
            val seen = HashSet<String>()   // places with the same name, state and country read the same
            if (results != null) for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val city = r.optString("name", "")
                if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue
                val label = StringBuilder(city)
                for (part in arrayOf(r.optString("admin1", ""), r.optString("country", ""))) {
                    if (!part.isEmpty() && part != city) label.append(", ").append(part)
                }
                if (!seen.add(label.toString())) continue
                out.add(arrayOf(label.toString(), city,
                    java.lang.String.format(Locale.US, "%.4f", r.getDouble("latitude")),
                    java.lang.String.format(Locale.US, "%.4f", r.getDouble("longitude"))))
            }
            return out
        }

        private fun get(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("User-Agent", "BeamStation")
            try {
                if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
                c.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var n = input.read(buffer)
                    while (n > 0) {
                        out.write(buffer, 0, n)
                        n = input.read(buffer)
                    }
                    return out.toString(StandardCharsets.UTF_8.name())
                }
            } finally {
                c.disconnect()
            }
        }

        // MARK: - Formatting

        private fun locale(): Locale = Ui.locale()

        /** 580.4k · 21.0k · 1.04k · 5.61 (as short as possible; the dollar always with cents). */
        @JvmStatic
        fun price(v: Double): String {
            if (v.isNaN()) return "—"
            if (v >= 10_000) return java.lang.String.format(locale(), "%.1fk", v / 1000)
            if (v >= 1000) return java.lang.String.format(locale(), "%.2fk", v / 1000)
            return java.lang.String.format(locale(), "%.2f", v)
        }

        /** 416.3k · 12.2k · 1.04k · 531 · 5.05: abbreviated, for the price row on the home screen. */
        @JvmStatic
        fun compact(v: Double): String {
            if (v.isNaN()) return "—"
            if (v >= 10_000) return java.lang.String.format(locale(), "%.1fk", v / 1000)
            if (v >= 1000) return java.lang.String.format(locale(), "%.2fk", v / 1000)
            if (v >= 100) return java.lang.String.format(locale(), "%.0f", v)
            return java.lang.String.format(locale(), "%.2f", v)
        }

        /**
         * $108,234 · $185.20 · R$ 581.234 · 74 264 € (cents only below a thousand), in the configured currency, with
         * the symbol and spaces where the language puts them.
         */
        @JvmStatic
        fun brl(v: Double): String {
            val symbol = currencySymbol()
            if (v.isNaN()) return symbol + "—"
            return Ui.once("brl", v) {
                val f = NumberFormat.getCurrencyInstance(locale()) as DecimalFormat
                val s = f.decimalFormatSymbols
                s.currencySymbol = symbol.trim { it <= ' ' }   // our symbols: ICU would write "US$" outside the US
                f.decimalFormatSymbols = s
                f.minimumFractionDigits = if (v >= 1000) 0 else 2
                f.maximumFractionDigits = if (v >= 1000) 0 else 2
                f.format(v)
            }
        }

        /** The weather in words, from Open-Meteo's WMO code. */
        @JvmStatic
        fun condition(code: Int, day: Boolean): String {
            if (code < 0) return ""
            if (Ui.en) {
                if (code == 0) return "Clear"
                if (code == 1) return if (day) "Mostly Sunny" else "Mostly Clear"
                if (code == 2) return "Partly Cloudy"
                if (code == 3) return "Cloudy"
                if (code == 45 || code == 48) return "Fog"
                if (code >= 51 && code <= 57) return "Drizzle"
                if (code == 65 || code == 67 || code == 82) return "Heavy Rain"
                if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow"
                if (code >= 95) return "Thunderstorms"
                return "Rain"
            }
            if (Ui.ru) {
                if (code == 0) return "Ясно"
                if (code == 1) return "Малооблачно"
                if (code == 2) return "Переменная облачность"
                if (code == 3) return "Облачно"
                if (code == 45 || code == 48) return "Туман"
                if (code >= 51 && code <= 57) return "Морось"
                if (code == 65 || code == 67 || code == 82) return "Сильный дождь"
                if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Снег"
                if (code >= 95) return "Гроза"
                return "Дождь"
            }
            if (Ui.es) {
                if (code == 0) return if (day) "Despejado" else "Noche despejada"
                if (code == 1) return "Mayormente despejado"
                if (code == 2) return "Parcialmente nublado"
                if (code == 3) return "Nublado"
                if (code == 45 || code == 48) return "Niebla"
                if (code >= 51 && code <= 57) return "Llovizna"
                if (code == 65 || code == 67 || code == 82) return "Lluvia fuerte"
                if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Nieve"
                if (code >= 95) return "Tormenta"
                return "Lluvia"
            }
            if (Ui.de) {
                if (code == 0) return if (day) "Sonnig" else "Klar"
                if (code == 1) return if (day) "Überwiegend sonnig" else "Überwiegend klar"
                if (code == 2) return "Teilweise bewölkt"
                if (code == 3) return "Bewölkt"
                if (code == 45 || code == 48) return "Nebel"
                if (code >= 51 && code <= 57) return "Nieselregen"
                if (code == 65 || code == 67 || code == 82) return "Starker Regen"
                if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Schnee"
                if (code >= 95) return "Gewitter"
                return "Regen"
            }
            if (code == 0) return if (day) "Céu limpo" else "Noite limpa"
            if (code == 1) return "Poucas nuvens"
            if (code == 2) return "Parcialmente nublado"
            if (code == 3) return "Nublado"
            if (code == 45 || code == 48) return "Neblina"
            if (code >= 51 && code <= 57) return "Garoa"
            if (code == 65 || code == 67 || code == 82) return "Chuva forte"
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Neve"
            if (code >= 95) return "Tempestade"
            return "Chuva"
        }

        private fun currencySymbol(): String = when (if (inDollars) "USD" else CURRENCY) {
            "USD" -> "$"
            "BRL" -> "R$ "
            "EUR" -> "€"
            "GBP" -> "£"
            "JPY" -> "¥"
            else -> CURRENCY + " "
        }

        /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
        @JvmStatic
        fun degrees(v: Double): String {
            if (v.isNaN()) return "—"
            return "${Math.round(if (fahrenheit()) v * 9 / 5 + 32 else v)}°"
        }

        private fun fahrenheit(): Boolean {
            val unit = Prefs.temperature()
            if (unit == "F") return true
            if (unit == "C") return false
            return "US" == Locale.getDefault().country
        }

        /** "+1.2%" / "−0.8%" (a real minus sign); "+1,2 %" in the languages that space the percent sign. */
        @JvmStatic
        fun change(v: Double): String {
            if (v.isNaN()) return ""
            return Ui.once("change", v) {
                val f = NumberFormat.getPercentInstance(locale())
                f.minimumFractionDigits = 1
                f.maximumFractionDigits = 1
                (if (v >= 0) "+" else "−") + f.format(abs(v) / 100)
            }
        }

        @JvmStatic
        fun hour(at: Long): String = Ui.once("hour", at.toDouble()) {
            if (Ui.en) SimpleDateFormat("h a", Locale.US).format(Date(at))
            else SimpleDateFormat(if (Ui.es || Ui.ru) "HH:mm" else if (Ui.de) "HH 'Uhr'" else "HH'h'", Ui.locale())
                .format(Date(at))
        }

        /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
        @JvmStatic
        fun staleness(at: Long, every: Long): String {
            if (at == 0L) return ""
            val age = System.currentTimeMillis() - at
            if (age < every * 2) return ""
            val minutes = age / 60_000
            if (Ui.en) return if (minutes < 60) "$minutes min ago" else "${minutes / 60} h ago"
            if (Ui.ru) return if (minutes < 60) "$minutes мин назад" else "${minutes / 60} ч назад"
            if (Ui.es) return if (minutes < 60) "hace $minutes min" else "hace ${minutes / 60} h"
            if (Ui.de) return if (minutes < 60) "vor $minutes Min." else "vor ${minutes / 60} Std."
            return if (minutes < 60) "há $minutes min" else "há ${minutes / 60} h"
        }
    }
}
