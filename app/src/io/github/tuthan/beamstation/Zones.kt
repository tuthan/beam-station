package io.github.tuthan.beamstation

import android.content.Context
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * The time zone on XGIMI projectors, whose own date and time screen is Chinese only, and whose China ROM keeps
 * Shanghai's zone wherever it is (automatic time zone needs a SIM). Android lets only the system set the zone, so this
 * goes through the TV's own ADB (`cmd alarm set-timezone`), as Wifi does; the date and time come from the network.
 */
internal object Zones {
    private val REGIONS = setOf(
        "Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")
    private val SAFE = Regex("""[A-Za-z0-9/_+\-]+""")

    /** The zones to pick from (one per place, like Asia/Ho_Chi_Minh), by UTC offset now, then by name. */
    @JvmStatic
    fun all(): List<String> {
        val now = System.currentTimeMillis()
        val ids = TimeZone.getAvailableIDs().filter {
            val slash = it.indexOf('/')
            slash > 0 && it.substring(0, slash) in REGIONS
        }
        val offsets = ids.associateWith { TimeZone.getTimeZone(it).getOffset(now) }
        return ids.sortedWith(compareBy({ offsets.getValue(it) }, { place(it) }))
    }

    /** "Ho Chi Minh · GMT+7" */
    @JvmStatic
    fun label(id: String): String = place(id) + " · " + offset(id)

    /** The place in a zone's id: Asia/Ho_Chi_Minh → Ho Chi Minh, America/Argentina/Buenos_Aires → Buenos Aires. */
    @JvmStatic
    fun place(id: String): String = id.substring(id.lastIndexOf('/') + 1).replace('_', ' ')

    /** "GMT+7", "GMT+5:30", "GMT" */
    @JvmStatic
    fun offset(id: String): String {
        val minutes = TimeZone.getTimeZone(id).getOffset(System.currentTimeMillis()) / 60_000
        if (minutes == 0) return "GMT"
        val h = abs(minutes) / 60
        val m = abs(minutes) % 60
        return "GMT" + (if (minutes < 0) "-" else "+") + h + (if (m == 0) "" else ":%02d".format(Locale.ROOT, m))
    }

    /** A zone's id only if Android knows it: the ids are letters, digits, '/', '_', '+' and '-', safe for the shell. */
    @JvmStatic
    fun known(id: String?): Boolean = id != null && SAFE.matches(id) && id in TimeZone.getAvailableIDs()

    /** Sets the zone, as Settings does when one is picked: automatic time zone off (it can't work here anyway). */
    @JvmStatic
    @Throws(IOException::class)
    fun set(c: Context, id: String) {
        if (!known(id)) throw IOException("unknown time zone $id")
        Adb.connect(c, 10_000, null).use { adb ->
            adb.shell("settings put global auto_time_zone 0")
            adb.shell("cmd alarm set-timezone $id")
        }
        AppLog.w("time zone: $id")
    }
}
