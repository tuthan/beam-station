package io.github.tuthan.beamstation

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.ParcelUuid

/**
 * Bluetooth for XGIMI projectors, whose own Bluetooth screen is Chinese only, and the Rokid Station: the paired
 * devices (connect, disconnect, forget) and pairing new ones (speakers, headphones). The remote is itself a paired
 * Bluetooth device, so peripherals (remotes, keyboards, game controllers) are only listed, and Bluetooth is never
 * turned off here. Connecting, and forgetting, are hidden calls that need only BLUETOOTH_ADMIN on Android 11;
 * discovery needs location there (granted through ADB in our mode, HomeMode; the location itself is never read).
 */
internal object Bluetooth {
    /** A device's A2DP sink (it plays audio): speakers whose class says nothing (JBL's) still list it. */
    private val AUDIO_SINK = ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB")

    class Device(
        @JvmField val device: BluetoothDevice,
        @JvmField val name: String,
        @JvmField val address: String,
        @JvmField val bonded: Boolean,
        @JvmField val peripheral: Boolean,
        @JvmField val audio: Boolean,
        @JvmField val connected: Boolean,
    ) {
        @JvmField var rssi = 0
    }

    @JvmStatic
    fun available() = (HomeMode.XGIMI || HomeMode.ROKID) && adapter() != null

    @Suppress("DEPRECATION")
    private fun adapter(): BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    /** The paired devices: connected first, then by name; peripherals (the remote) last. */
    @JvmStatic
    fun paired(): MutableList<Device> {
        val out = ArrayList<Device>()
        val a = adapter() ?: return out
        try {
            for (d in a.bondedDevices) of(d, true)?.let { out.add(it) }
        } catch (e: SecurityException) {
            AppLog.w("bluetooth: " + e.message)
        }
        out.sortWith(compareBy<Device>({ it.peripheral }, { !it.connected })
            .then(Comparator { x, y -> x.name.compareTo(y.name, ignoreCase = true) }))
        return out
    }

    /** A device found by the search, or null when it has no name (nothing to pick it by). */
    @JvmStatic
    fun of(d: BluetoothDevice, bonded: Boolean): Device? {
        val name = try {
            (if (Build.VERSION.SDK_INT >= 30) d.alias else null) ?: d.name   // the name the person gave it, if any
        } catch (e: SecurityException) {
            null
        }
        if (name == null || name.trim().isEmpty()) return null
        val major = d.bluetoothClass?.majorDeviceClass ?: BluetoothClass.Device.Major.UNCATEGORIZED
        return Device(
            device = d,
            name = name.trim(),
            address = d.address,
            bonded = bonded,
            peripheral = major == BluetoothClass.Device.Major.PERIPHERAL,
            audio = major == BluetoothClass.Device.Major.AUDIO_VIDEO || hasUuid(d, AUDIO_SINK),
            connected = bonded && connected(d),
        )
    }

    private fun hasUuid(d: BluetoothDevice, uuid: ParcelUuid) = d.uuids?.any { uuid == it } ?: false

    private fun connected(d: BluetoothDevice) = call(d, "isConnected", quiet = true)

    /** The A2DP profile (speakers, headphones), handed to `listener` once it's ready. */
    @JvmStatic
    fun openAudio(c: Context, listener: BluetoothProfile.ServiceListener) {
        adapter()?.getProfileProxy(c, listener, BluetoothProfile.A2DP)
    }

    @JvmStatic
    fun closeAudio(a2dp: BluetoothProfile?) {
        val a = adapter()
        if (a != null && a2dp != null) a.closeProfileProxy(BluetoothProfile.A2DP, a2dp)
    }

    @JvmStatic
    fun search(): Boolean {
        val a = adapter()
        return try {
            a != null && (a.isDiscovering || a.startDiscovery())
        } catch (e: SecurityException) {
            AppLog.w("bluetooth search: " + e.message)
            false
        }
    }

    @JvmStatic
    fun stopSearch() {
        val a = adapter()
        try {
            if (a != null && a.isDiscovering) a.cancelDiscovery()
        } catch (ignored: SecurityException) {
        }
    }

    @JvmStatic
    fun pair(d: Device): Boolean {
        stopSearch()   // pairing is slow and unreliable while the radio is searching
        return try {
            d.device.createBond()
        } catch (e: SecurityException) {
            AppLog.w("bluetooth pair: " + e.message)
            false
        }
    }

    /** Forgets (unpairs) a device; never a peripheral, which could be the remote. */
    @JvmStatic
    fun forget(d: Device): Boolean {
        if (d.peripheral) return false
        return call(d.device, "removeBond")
    }

    /** Connects or disconnects a paired speaker or headphones (A2DP), through the profile proxy. */
    @JvmStatic
    fun connect(a2dp: BluetoothProfile?, d: Device, on: Boolean): Boolean {
        if (a2dp == null || d.peripheral || !d.audio) return false
        val method = if (on) "connect" else "disconnect"
        return try {
            a2dp.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(a2dp, d.device) as Boolean
        } catch (e: Exception) {   // ReflectiveOperationException or RuntimeException
            AppLog.w("bluetooth $method: $e")
            false
        }
    }

    /** A hidden BluetoothDevice call that answers true or false; false when it fails. */
    private fun call(d: BluetoothDevice, method: String, quiet: Boolean = false): Boolean = try {
        BluetoothDevice::class.java.getMethod(method).invoke(d) as Boolean
    } catch (e: Exception) {   // ReflectiveOperationException or RuntimeException
        if (!quiet) AppLog.w("bluetooth $method: $e")
        false
    }
}
