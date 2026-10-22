package io.freemytv;

import android.content.Context;
import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wi-Fi for XGIMI projectors, whose own Wi-Fi screen is Chinese only, and the Rokid Station, whose own is deep in
 * Rokid's Settings: networks nearby, connect with a password, forget.
 * Android gives apps no way to join a network, so this goes through the TV's own ADB (`cmd wifi`); the networks it adds
 * belong to the shell, which is never turned off (HomeMode.wifiOwners). Network names come from the air: each one goes
 * to the shell single-quoted, and names or passwords with control characters are refused.
 */
final class Wifi {
    private Wifi() {}

    static final class Network {
        String ssid;
        /** "open", "owe", "wpa2" or "wpa3" for `cmd wifi`; "" for what it can't join (enterprise, WEP). */
        String security;
        int rssi, savedId = -1;
        boolean connected;

        boolean saved() {
            return savedId >= 0;
        }

        boolean needsPassword() {
            return security.equals("wpa2") || security.equals("wpa3");
        }

        /** Signal, 1 to 4 bars. */
        int bars() {
            return rssi >= -55 ? 4 : rssi >= -67 ? 3 : rssi >= -78 ? 2 : 1;
        }
    }

    static final class Status {
        boolean enabled;
        String connected = "";
        final List<Network> networks = new ArrayList<>();
    }

    static boolean available() {
        return HomeMode.XGIMI || HomeMode.ROKID;
    }

    private static final Pattern CONNECTED = Pattern.compile("Wifi is connected to \"(.*)\"");
    private static final Pattern SAVED = Pattern.compile("^(\\d+)\\s+(.+?)\\s+(\\S+)\\s*$");
    private static final Pattern SCAN = Pattern.compile(
            "^\\s*[0-9a-fA-F:]{17}\\s+\\d+\\s+(-?\\d+)\\s+[\\d.]+\\s+(.*?)\\s+((?:\\[[^\\]]*\\])*)\\s*$");

    /** What the projector sees now: on or off, the network in use, and the networks nearby (saved ones marked). */
    static Status read(Context c) throws IOException {
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            return read(adb);
        }
    }

    /** Asks for a new scan; its results show in read() a few seconds later. */
    static void scan(Context c) throws IOException {
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            adb.shell("cmd wifi start-scan");
        }
    }

    private static Status read(Adb adb) throws IOException {
        Status s = new Status();
        String status = adb.shell("cmd wifi status");
        s.enabled = status.contains("Wifi is enabled");
        Matcher m = CONNECTED.matcher(status);
        if (m.find()) s.connected = m.group(1);
        Map<String, Integer> saved = new HashMap<>();
        for (String line : adb.shell("cmd wifi list-networks").split("\n")) {
            Matcher n = SAVED.matcher(line);
            if (n.matches()) saved.put(n.group(2), Integer.parseInt(n.group(1)));
        }
        Map<String, Network> bySsid = new HashMap<>();
        for (String line : adb.shell("cmd wifi list-scan-results").split("\n")) {
            Matcher n = SCAN.matcher(line);
            if (!n.matches()) continue;
            String ssid = n.group(2);
            if (ssid.isEmpty() || !printable(ssid)) continue;
            int rssi = Integer.parseInt(n.group(1));
            Network known = bySsid.get(ssid);
            if (known != null && known.rssi >= rssi) continue;
            Network net = known != null ? known : new Network();
            net.ssid = ssid;
            net.rssi = rssi;
            net.security = security(n.group(3));
            net.savedId = saved.containsKey(ssid) ? saved.get(ssid) : -1;
            net.connected = ssid.equals(s.connected);
            bySsid.put(ssid, net);
        }
        s.networks.addAll(bySsid.values());
        // The one in use first, then the strongest.
        Collections.sort(s.networks, (a, b) -> a.connected != b.connected ? (a.connected ? -1 : 1) : b.rssi - a.rssi);
        return s;
    }

    private static String security(String flags) {
        if (flags.contains("EAP") || flags.contains("WEP")) return "";
        if (flags.contains("PSK")) return "wpa2";
        if (flags.contains("SAE")) return "wpa3";
        if (flags.contains("OWE")) return "owe";
        return "open";
    }

    static void setEnabled(Context c, boolean on) throws IOException {
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            adb.shell("cmd wifi set-wifi-enabled " + (on ? "enabled" : "disabled"));
        }
    }

    /**
     * Joins a network (saving it), and waits up to 20 s for it. False: it didn't connect (a wrong password, most
     * likely); a network that wasn't saved before is forgotten again, so a wrong password doesn't stay saved.
     */
    static boolean connect(Context c, Network net, String password) throws IOException {
        if (!printable(net.ssid) || !printable(password)) return false;
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            adb.shell("cmd wifi connect-network " + quote(net.ssid) + " " + net.security
                    + (net.needsPassword() ? " " + quote(password) : ""));
            long until = SystemClock.elapsedRealtime() + 20_000;
            while (SystemClock.elapsedRealtime() < until) {
                SystemClock.sleep(1500);
                if (net.ssid.equals(read(adb).connected)) return true;
            }
            if (!net.saved()) {
                for (Network now : read(adb).networks) {
                    if (now.ssid.equals(net.ssid) && now.saved()) adb.shell("cmd wifi forget-network " + now.savedId);
                }
            }
            return false;
        }
    }

    static void forget(Context c, Network net) throws IOException {
        if (!net.saved()) return;
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            adb.shell("cmd wifi forget-network " + net.savedId);
        }
    }

    /** A WPA password: 8 to 63 printable ASCII characters. */
    static boolean validPassword(String p) {
        if (p.length() < 8 || p.length() > 63) return false;
        for (int i = 0; i < p.length(); i++) if (p.charAt(i) < 0x20 || p.charAt(i) > 0x7E) return false;
        return true;
    }

    private static boolean printable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7F) return false;
        }
        return true;
    }

    /** One shell word: single quotes, with each ' closed, escaped and reopened. */
    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
