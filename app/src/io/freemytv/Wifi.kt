package io.freemytv

import android.content.Context
import android.os.SystemClock
import java.io.IOException

/**
 * Wi-Fi for XGIMI projectors, whose own Wi-Fi screen is Chinese only, and the Rokid Station, whose own is deep in
 * Rokid's Settings: networks nearby, connect with a password, forget.
 * Android gives apps no way to join a network, so this goes through the TV's own ADB (`cmd wifi`); the networks it adds
 * belong to the shell, which is never turned off (HomeMode.wifiOwners). Network names come from the air: each one goes
 * to the shell single-quoted, and names or passwords with control characters are refused.
 */
internal object Wifi {
    class Network {
        @JvmField var ssid = ""
        /** "open", "owe", "wpa2" or "wpa3" for `cmd wifi`; "" for what it can't join (enterprise, WEP). */
        @JvmField var security = ""
        @JvmField var rssi = 0
        @JvmField var savedId = -1
        @JvmField var connected = false

        fun saved() = savedId >= 0

        fun needsPassword() = security == "wpa2" || security == "wpa3"

        /** Signal, 1 to 4 bars. */
        fun bars() = if (rssi >= -55) 4 else if (rssi >= -67) 3 else if (rssi >= -78) 2 else 1
    }

    class Status {
        @JvmField var enabled = false
        @JvmField var connected = ""
        @JvmField val networks: MutableList<Network> = ArrayList()
    }

    @JvmStatic
    fun available() = HomeMode.XGIMI || HomeMode.ROKID

    private val CONNECTED = Regex("Wifi is connected to \"(.*)\"")
    private val SAVED = Regex("""^(\d+)\s+(.+?)\s+(\S+)\s*$""")
    private val SCAN = Regex("""^\s*[0-9a-fA-F:]{17}\s+\d+\s+(-?\d+)\s+[\d.]+\s+(.*?)\s+((?:\[[^\]]*\])*)\s*$""")

    /** What the projector sees now: on or off, the network in use, and the networks nearby (saved ones marked). */
    @JvmStatic
    @Throws(IOException::class)
    fun read(c: Context): Status = Adb.connect(c, 10_000, null).use { read(it) }

    /** Asks for a new scan; its results show in read() a few seconds later. */
    @JvmStatic
    @Throws(IOException::class)
    fun scan(c: Context) {
        Adb.connect(c, 10_000, null).use { it.shell("cmd wifi start-scan") }
    }

    private fun read(adb: Adb): Status {
        val s = Status()
        val status = adb.shell("cmd wifi status")
        s.enabled = status.contains("Wifi is enabled")
        CONNECTED.find(status)?.let { s.connected = it.groupValues[1] }
        val saved = HashMap<String, Int>()
        for (line in adb.shell("cmd wifi list-networks").split("\n")) {
            val n = SAVED.matchEntire(line) ?: continue
            saved[n.groupValues[2]] = n.groupValues[1].toInt()
        }
        val bySsid = HashMap<String, Network>()
        for (line in adb.shell("cmd wifi list-scan-results").split("\n")) {
            val n = SCAN.matchEntire(line) ?: continue
            val ssid = n.groupValues[2]
            if (ssid.isEmpty() || !printable(ssid)) continue
            val rssi = n.groupValues[1].toInt()
            val known = bySsid[ssid]
            if (known != null && known.rssi >= rssi) continue
            val net = known ?: Network()
            net.ssid = ssid
            net.rssi = rssi
            net.security = security(n.groupValues[3])
            net.savedId = saved[ssid] ?: -1
            net.connected = ssid == s.connected
            bySsid[ssid] = net
        }
        s.networks.addAll(bySsid.values)
        // The one in use first, then the strongest.
        s.networks.sortWith(compareBy({ !it.connected }, { -it.rssi }))
        return s
    }

    private fun security(flags: String) = when {
        flags.contains("EAP") || flags.contains("WEP") -> ""
        flags.contains("PSK") -> "wpa2"
        flags.contains("SAE") -> "wpa3"
        flags.contains("OWE") -> "owe"
        else -> "open"
    }

    @JvmStatic
    @Throws(IOException::class)
    fun setEnabled(c: Context, on: Boolean) {
        Adb.connect(c, 10_000, null).use { it.shell("cmd wifi set-wifi-enabled " + if (on) "enabled" else "disabled") }
    }

    /**
     * Joins a network (saving it), and waits up to 20 s for it. False: it didn't connect (a wrong password, most
     * likely); a network that wasn't saved before is forgotten again, so a wrong password doesn't stay saved.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun connect(c: Context, net: Network, password: String): Boolean {
        if (!printable(net.ssid) || !printable(password)) return false
        Adb.connect(c, 10_000, null).use { adb ->
            adb.shell("cmd wifi connect-network " + quote(net.ssid) + " " + net.security
                    + if (net.needsPassword()) " " + quote(password) else "")
            val until = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < until) {
                SystemClock.sleep(1500)
                if (net.ssid == read(adb).connected) return true
            }
            if (!net.saved()) {
                for (now in read(adb).networks) {
                    if (now.ssid == net.ssid && now.saved()) adb.shell("cmd wifi forget-network " + now.savedId)
                }
            }
            return false
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun forget(c: Context, net: Network) {
        if (!net.saved()) return
        Adb.connect(c, 10_000, null).use { it.shell("cmd wifi forget-network " + net.savedId) }
    }

    /** A WPA password: 8 to 63 printable ASCII characters. */
    @JvmStatic
    fun validPassword(p: String) = p.length in 8..63 && p.all { it.code in 0x20..0x7E }

    private fun printable(s: String) = s.none { it.code < 0x20 || it.code == 0x7F }

    /** One shell word: single quotes, with each ' closed, escaped and reopened. */
    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
