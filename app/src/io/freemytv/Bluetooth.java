package io.freemytv;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bluetooth for XGIMI projectors, whose own Bluetooth screen is Chinese only: the paired devices (connect, disconnect,
 * forget) and pairing new ones (speakers, headphones). The remote is itself a paired Bluetooth device, so peripherals
 * (remotes, keyboards, game controllers) are only listed, and Bluetooth is never turned off here. Connecting, and
 * forgetting, are hidden calls that need only BLUETOOTH_ADMIN on Android 11; discovery needs location there (granted
 * through ADB in Beam Home mode, HomeMode; the location itself is never read).
 */
final class Bluetooth {
    private Bluetooth() {}

    /** A device's A2DP sink (it plays audio): speakers whose class says nothing (JBL's) still list it. */
    private static final ParcelUuid AUDIO_SINK = ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB");

    static final class Device {
        BluetoothDevice device;
        String name, address;
        boolean bonded, connected, peripheral, audio;
        int rssi;
    }

    static boolean available() {
        return HomeMode.XGIMI && adapter() != null;
    }

    @SuppressWarnings("deprecation")
    private static BluetoothAdapter adapter() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    /** The paired devices: connected first, then by name; peripherals (the remote) last. */
    static List<Device> paired() {
        List<Device> out = new ArrayList<>();
        BluetoothAdapter a = adapter();
        if (a == null) return out;
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                Device dev = of(d, true);
                if (dev != null) out.add(dev);
            }
        } catch (SecurityException e) {
            AppLog.w("bluetooth: " + e.getMessage());
        }
        Collections.sort(out, (x, y) -> x.peripheral != y.peripheral ? (x.peripheral ? 1 : -1)
                : x.connected != y.connected ? (x.connected ? -1 : 1) : x.name.compareToIgnoreCase(y.name));
        return out;
    }

    /** A device found by the search, or null when it has no name (nothing to pick it by). */
    static Device of(BluetoothDevice d, boolean bonded) {
        String name;
        try {
            name = Build.VERSION.SDK_INT >= 30 ? d.getAlias() : null;   // the name the person gave it, if any
            if (name == null) name = d.getName();
        } catch (SecurityException e) {
            name = null;
        }
        if (name == null || name.trim().isEmpty()) return null;
        Device dev = new Device();
        dev.device = d;
        dev.name = name.trim();
        dev.address = d.getAddress();
        dev.bonded = bonded;
        BluetoothClass k = d.getBluetoothClass();
        int major = k == null ? BluetoothClass.Device.Major.UNCATEGORIZED : k.getMajorDeviceClass();
        dev.peripheral = major == BluetoothClass.Device.Major.PERIPHERAL;
        dev.audio = major == BluetoothClass.Device.Major.AUDIO_VIDEO || hasUuid(d, AUDIO_SINK);
        dev.connected = bonded && connected(d);
        return dev;
    }

    private static boolean hasUuid(BluetoothDevice d, ParcelUuid uuid) {
        ParcelUuid[] uuids = d.getUuids();
        if (uuids == null) return false;
        for (ParcelUuid u : uuids) if (uuid.equals(u)) return true;
        return false;
    }

    private static boolean connected(BluetoothDevice d) {
        try {
            return (Boolean) BluetoothDevice.class.getMethod("isConnected").invoke(d);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** The A2DP profile (speakers, headphones), handed to `listener` once it's ready. */
    static void openAudio(Context c, BluetoothProfile.ServiceListener listener) {
        BluetoothAdapter a = adapter();
        if (a != null) a.getProfileProxy(c, listener, BluetoothProfile.A2DP);
    }

    static void closeAudio(BluetoothProfile a2dp) {
        BluetoothAdapter a = adapter();
        if (a != null && a2dp != null) a.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
    }

    static boolean search() {
        BluetoothAdapter a = adapter();
        try {
            return a != null && (a.isDiscovering() || a.startDiscovery());
        } catch (SecurityException e) {
            AppLog.w("bluetooth search: " + e.getMessage());
            return false;
        }
    }

    static void stopSearch() {
        BluetoothAdapter a = adapter();
        try {
            if (a != null && a.isDiscovering()) a.cancelDiscovery();
        } catch (SecurityException ignored) {
        }
    }

    static boolean pair(Device d) {
        stopSearch();   // pairing is slow and unreliable while the radio is searching
        try {
            return d.device.createBond();
        } catch (SecurityException e) {
            AppLog.w("bluetooth pair: " + e.getMessage());
            return false;
        }
    }

    /** Forgets (unpairs) a device; never a peripheral, which could be the remote. */
    static boolean forget(Device d) {
        if (d.peripheral) return false;
        return call(d.device, "removeBond");
    }

    /** Connects or disconnects a paired speaker or headphones (A2DP), through the profile proxy. */
    static boolean connect(BluetoothProfile a2dp, Device d, boolean on) {
        if (a2dp == null || d.peripheral || !d.audio) return false;
        try {
            return (Boolean) a2dp.getClass().getMethod(on ? "connect" : "disconnect", BluetoothDevice.class).invoke(a2dp, d.device);
        } catch (ReflectiveOperationException | RuntimeException e) {
            AppLog.w("bluetooth " + (on ? "connect" : "disconnect") + ": " + e);
            return false;
        }
    }

    private static boolean call(BluetoothDevice d, String method) {
        try {
            return (Boolean) BluetoothDevice.class.getMethod(method).invoke(d);
        } catch (ReflectiveOperationException | RuntimeException e) {
            AppLog.w("bluetooth " + method + ": " + e);
            return false;
        }
    }
}
