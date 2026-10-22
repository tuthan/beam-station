package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Free My TV / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5,
                WIFI = 6, WIFI_FORGET = 7, BT = 8, BT_DEVICE = 9, GLASSES = 10, POWER = 11;
        /** The main screen's rows (Remote Buttons, TV Settings, Wi-Fi, Bluetooth, Glasses, Power), shown in the order of mainRows(). */
        private static final int REMOTE_ROW = 4, TV_ROW = 5, WIFI_ROW = 6, BT_ROW = 7, GLASSES_ROW = 8, POWER_ROW = 9;
        private static final String[] WIFI_BARS = {"wifi_1", "wifi_1", "wifi_2", "wifi_3", "wifi_4"};
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_H = 74, ROW_GAP = 8, CHOICE_H = 104;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0;
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy;
        private int[] mainRows = mainRows(false);
        // Wi-Fi (Rokid Station): what the TV sees, the line under the list, the network picked to forget.
        private Wifi.Status wifi;
        private String wifiLine = "";
        private Wifi.Network wifiPicked;
        private boolean wifiBusy;
        // Bluetooth (Rokid Station): paired devices, the ones the search found, and the device being paired or picked.
        private List<Bluetooth.Device> btPaired = new ArrayList<>();
        private final List<Bluetooth.Device> btFound = new ArrayList<>();
        private Bluetooth.Device btPicked, btPairing;
        private boolean btSearching, btListening;
        private String btLine = "";
        private BluetoothProfile a2dp;
        // Glasses (Rokid Station): the glasses' settings (null while none are plugged in), and a change on its way.
        private Rokid.State glasses;
        private String glassesLine = "";
        private int glassesChanges;
        // Power (Rokid Station): the battery, the timeouts (null until read), when the sleep timer goes off, a change on its way.
        private Power.Battery battery;
        private Power.Timeouts timeouts;
        private long timerEnd;
        private String powerLine = "";
        private int powerChanges;
        private final ExecutorService work = Executors.newSingleThreadExecutor();   // ADB and Bluetooth calls
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        /** While the Wi-Fi screen is open: a new scan every 15 s. */
        private final Runnable wifiScan = new Runnable() {
            @Override public void run() {
                if (!running || screen != WIFI) return;
                refreshWifi(true);
                handler.postDelayed(this, 15_000);
            }
        };

        /** While the Bluetooth screen is open: what the search finds, pairing, connections. */
        private final BroadcastReceiver btEvents = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String action = i.getAction();
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    if (d != null && d.getBondState() != BluetoothDevice.BOND_BONDED) {
                        Bluetooth.Device dev = Bluetooth.of(d, false);
                        if (dev != null) {
                            dev.rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                            addFound(dev);
                        }
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                    btSearching = true;
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    btSearching = false;
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                    int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                    if (btPairing != null && d != null && d.getAddress().equals(btPairing.address)) {
                        if (state == BluetoothDevice.BOND_BONDED) {
                            btLine = Ui.tr("Pareado com ", "Paired with ") + btPairing.name + ".";
                            btPairing = null;
                        } else if (state == BluetoothDevice.BOND_NONE) {
                            btLine = Ui.tr("Não deu para parear com ", "Couldn't pair with ") + btPairing.name
                                    + Ui.tr(". Coloque-o no modo de pareamento e tente de novo.", ". Put it in pairing mode and try again.");
                            btPairing = null;
                        }
                    }
                    refreshBt();
                } else {
                    refreshBt();   // connected or disconnected
                }
                invalidate();
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription("Free My TV");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == 0) {
                        String busyText = busyText();
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText)
                                : freeActive() ? "Free My TV" : Ui.tr("Original", "Original");
                        return Ui.tr("Início, ", "Home Screen, ") + value;
                    }
                    if (mainRow(row) == WIFI_ROW) return Ui.tr("Wi-Fi", "Wi-Fi") + ", " + wifiSummary();
                    if (mainRow(row) == BT_ROW) return "Bluetooth" + (btSummary().isEmpty() ? "" : ", " + btSummary());
                    if (mainRow(row) == GLASSES_ROW) return Ui.tr("Óculos", "Glasses") + (glassesSummary().isEmpty() ? "" : ", " + glassesSummary());
                    if (mainRow(row) == POWER_ROW) return Ui.tr("Energia", "Power") + (powerSummary().isEmpty() ? "" : ", " + powerSummary());
                    String[] names = {Ui.tr("Tela de Descanso, ver agora", "Screensaver, preview"), Ui.tr("Ajustes do Início", "Home Settings"),
                            Ui.tr("Prateleira", "Shelf"), Ui.tr("Botões do Controle", "Remote Buttons"), Ui.tr("Ajustes da TV", "TV Settings")};
                    int at = mainRow(row) - 1;
                    return at < names.length ? names[at] : "";
                }
                case WIFI: {
                    int at = pickScroll + row;
                    if (at == 0) return Ui.tr("Wi-Fi", "Wi-Fi") + ", " + wifiOnOff();
                    Wifi.Network n = wifiNet(at);
                    if (n == null) return "";
                    String value = netValue(n);
                    return n.ssid + (value.isEmpty() ? "" : ", " + value) + ", " + at + Ui.tr(" de ", " of ") + wifi.networks.size();
                }
                case WIFI_FORGET: {
                    String[][] rows = forgetRows();
                    return row < rows.length ? rows[row][0] + ", " + rows[row][1] : "";
                }
                case BT: {
                    int at = pickScroll + row;
                    if (at == 0) return Ui.tr("Parear um aparelho novo", "Pair a new device") + (btSearching ? ", " + Ui.tr("Procurando…", "Searching…") : "");
                    Bluetooth.Device d = btItem(at);
                    if (d == null) return "";
                    String value = btValue(d);
                    return d.name + (value.isEmpty() ? "" : ", " + value);
                }
                case BT_DEVICE: {
                    String[][] rows = btActions();
                    return row < rows.length ? rows[row][0] + ", " + rows[row][1] : "";
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "Free My TV, " + Ui.tr("sem anúncios, com a tela de descanso nova", "no ads, with the new screensaver")
                            : "Original, " + Ui.tr("o Início de fábrica da TV", "the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". Em uso", ". In use") : "") + ", " + (row + 1) + Ui.tr(" de 2", " of 2");
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS:
                case GLASSES:
                case POWER: {
                    String[] labels = listLabels(), values = listValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", escolhido", ", selected") : "")
                            + ", " + (at + 1) + Ui.tr(" de ", " of ") + pickLabels.size();
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    return app[1] + ", " + (place >= 0 ? Ui.tr("na prateleira, posição ", "on the shelf, place ") + (place + 1)
                            : Ui.tr("fora da prateleira", "not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = mainTop() + row * (ROW_H + ROW_GAP);
                height = ROW_H;
            } else if (screen == HOME_CHOICE || screen == WIFI_FORGET || screen == BT_DEVICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            if (screen == WIFI) handler.post(wifiScan);
            else if (Wifi.available()) refreshWifi(false);
            if (screen == BT || screen == BT_DEVICE) openBt();
            else if (Bluetooth.available()) refreshBt();
            if (Rokid.available()) refreshGlasses();
            if (Power.available()) refreshPower();
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            handler.removeCallbacks(wifiScan);
            closeBt();
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            if (moving || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        /**
         * Remote Buttons only where the remote's buttons can be reached; Wi-Fi and Bluetooth on the Rokid
         * Station; Glasses while Rokid glasses are plugged in; Power on the Rokid Station; all before TV Settings.
         */
        private static int[] mainRows(boolean glasses) {
            List<Integer> rows = new ArrayList<>(java.util.Arrays.asList(0, 1, 2, 3));
            if (RemoteKeys.supported()) rows.add(REMOTE_ROW);
            if (Wifi.available()) rows.add(WIFI_ROW);
            if (Bluetooth.available()) rows.add(BT_ROW);
            if (glasses) rows.add(GLASSES_ROW);
            if (Power.available()) rows.add(POWER_ROW);
            rows.add(TV_ROW);
            int[] out = new int[rows.size()];
            for (int i = 0; i < out.length; i++) out[i] = rows.get(i);
            return out;
        }

        /** Where the main screen's rows start: higher past seven rows, so what's under them (the TMDB credit) stays on screen. */
        private float mainTop() {
            return LIST_TOP - Math.max(0, mainRows.length - 7) * (ROW_H + ROW_GAP);
        }

        /** The main screen's row at position i. */
        private int mainRow(int i) {
            return mainRows[i];
        }

        /** Where a main screen row is (0 if it isn't shown). */
        private int mainIndex(int row) {
            for (int i = 0; i < mainRows.length; i++) if (mainRows[i] == row) return i;
            return 0;
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return mainRows.length;
                case HOME_CHOICE:
                case WIFI_FORGET: return 2;
                case WIFI: return Math.min(PICK_VISIBLE, 1 + wifiCount());
                case BT: return Math.min(PICK_VISIBLE, 1 + btCount());
                case BT_DEVICE: return btActions().length;
                case HOME_SETTINGS:
                case GLASSES:
                case POWER: return listLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            scene.drawBackground(c);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, 0xEBFFFFFF);
            c.drawText("Free My TV", cx, 612 * k, title);
            c.drawText(Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 704 * k, version);

            if (screen == MAIN) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c);
            else if (screen == WIFI) drawWifi(c); else if (screen == WIFI_FORGET) drawForget(c);
            else if (screen == BT) drawBt(c); else if (screen == BT_DEVICE) drawBtDevice(c); else drawList(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = busyText();
            String mode = freeActive() ? "Free My TV" : Ui.tr("Original", "Original");
            String[] labels = {Ui.tr("Início", "Home Screen"), Ui.tr("Tela de Descanso", "Screensaver"),
                    Ui.tr("Ajustes do Início", "Home Settings"), Ui.tr("Prateleira", "Shelf"),
                    Ui.tr("Botões do Controle", "Remote Buttons"), Ui.tr("Ajustes da TV", "TV Settings"), Ui.tr("Wi-Fi", "Wi-Fi"), "Bluetooth",
                    Ui.tr("Óculos", "Glasses"), Ui.tr("Energia", "Power")};
            String[] values = {working ? "" : mode, Ui.tr("Ver agora", "Preview"), "", "", "", "", Wifi.available() ? wifiSummary() : "", btSummary(),
                    glassesSummary(), powerSummary()};
            for (int i = 0; i < rowCount(); i++) {
                int row = mainRow(i);
                float y = (mainTop() + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(0xF2FFFFFF, INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[row], rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (row >= 2) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                    if (!values[row].isEmpty()) {
                        rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                        c.drawText(TextUtils.ellipsize(values[row], new TextPaint(rowValue), 420 * k, TextUtils.TruncateAt.END).toString(),
                                right - cw - 18 * k, base, rowValue);
                    }
                } else if (i == 0 && working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText;
                    rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(0xFFFFFFFF, INK, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(values[row], right, base, rowValue);
                }
            }

            float y = (mainTop() + rowCount() * (ROW_H + ROW_GAP) + 20) * k;
            String error = state.optString("error");
            if (working && (busyText.contains("Permitir") || busyText.contains("Allow") || busyText.contains("разрешите"))) {
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                // The whole message, on up to five lines: some say what to do (the Wi-Fi one, #14).
                int width = Math.round(760 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 46 * k;
            }
            y = drawComparison(c, y);
            y = drawRecs(c, y + 26 * k);
            drawNotices(c, y + 40 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("Sem chave do TMDB: veja o README (config.properties).", "No TMDB key: see the README (config.properties).");
            else if (!error.isEmpty()) line = Ui.tr("Erro: ", "Error: ") + error + ".";
            else if (count > 0) line = Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.",
                    "On · " + count + " titles from the TV's apps, 3 new ones every hour.");
            else line = Ui.tr("Buscando os títulos…", "Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            if (!Config.WATCHMODE_KEY.trim().isEmpty()) {
                c.drawText(Ui.tr("Links dos títulos por Watchmode.", "Links to the titles by Watchmode."), x, y + 108 * k, version);
            }
            version.setTextAlign(Paint.Align.CENTER);
            return y + (Config.WATCHMODE_KEY.trim().isEmpty() ? 78 : 108) * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARAÇÃO", "COMPARISON"), x, y, header);
            cellValue.setColor(0x73FFFFFF);
            c.drawText(Ui.tr("Original", "Original"), colA, y, cellValue);
            c.drawText("Free My TV", colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Botão Início", "Home button"), "homeMs"}, {Ui.tr("Memória livre", "Free memory"), "memAvailableMB"},
                    {Ui.tr("Memória do Início", "Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("AVISOS", "NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + (Ui.ru ? Russian.text(o.optString("text")) : o.optString("text"));
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("Nenhum ainda.", "None yet."), x, y + 42 * k, notice);
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Início", "Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Free My TV", Ui.tr("Sem anúncios, com a tela de descanso nova", "No ads, with the new screensaver")},
                    {Ui.tr("Original", "Original"), Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("A troca leva uns segundos e a tela pisca nas medições.",
                    "Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return v + (Ui.ru ? " мс" : " ms");
            if (key.equals("memAvailableMB") && v >= 1024) return String.format(Ui.locale(), Ui.ru ? "%.2f ГБ" : "%.2f GB", v / 1024.0);
            return v + (Ui.ru ? " МБ" : " MB");
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("agora", "now");
            if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago");
            if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago");
            return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago");
        }

        private static String versionName(Context c) {
            try {
                return "Free My TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Free My TV";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && (screen == HOME_SETTINGS || screen == REMOTE_KEYS || screen == GLASSES || screen == POWER)) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == PICKER && pickFor == PICK_CITY) {
                                focus = CITY_ROW;
                                screen = HOME_SETTINGS;
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else if (screen == WIFI_FORGET) {
                                screen = WIFI;
                                keepWifiFocus(wifiPicked.ssid);
                            } else if (screen == BT_DEVICE) {
                                screen = BT;
                                keepBtFocus(btPicked.address);
                            } else {
                                if (screen == BT) closeBt();
                                focus = mainIndex(screen == HOME_SETTINGS ? 2 : screen == SHELF_EDIT ? 3 : screen == REMOTE_KEYS ? REMOTE_ROW
                                        : screen == WIFI ? WIFI_ROW : screen == BT ? BT_ROW : screen == GLASSES ? GLASSES_ROW
                                        : screen == POWER ? POWER_ROW : 0);
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] LANGUAGES = {"auto", "pt", "en", "es", "de", "ru"}, UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** Home Settings: language, temperature, theme, sounds, weather, city, quotes, then one row per coin. */
        private static final int CITY_ROW = 5, COIN_ROWS = 7;

        private boolean dollar() {
            return "BRL".equalsIgnoreCase(Config.CURRENCY.trim());
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Idioma", "Language"));
            l.add(Ui.tr("Temperatura", "Temperature"));
            l.add(Ui.tr("Tema", "Theme"));
            l.add(Ui.tr("Sons de navegação", "Navigation sounds"));
            l.add(Ui.tr("Widget do clima", "Weather widget"));
            l.add(Ui.tr("Cidade", "City"));
            l.add(Ui.tr("Widget de cotações", "Quotes widget"));
            l.add("Bitcoin");
            l.add("Ethereum");
            l.add("Solana");
            if (dollar()) l.add(Ui.tr("Dólar", "Dollar"));
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("pt") ? "Português" : lang.equals("en") ? "English" : lang.equals("es") ? "Español"
                    : lang.equals("de") ? "Deutsch" : lang.equals("ru") ? "Русский" : Ui.tr("Automático", "Automatic");
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark") : Ui.tr("Automático", "Automatic");
            v[3] = onOff(Prefs.sounds());
            v[4] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[6] = onOff(Prefs.quotes());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < v.length; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            return v;
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
        }

        private String[] keyLabels() {
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Botão Netflix", "Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Botão Tabii", "Tabii button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("Botão BRAVIA CORE", "BRAVIA CORE button"),
                    Ui.tr("Botão Netflix", "Netflix button"), Ui.tr("Botão Disney+", "Disney+ button"),
                    Ui.tr("Botão Prime Video", "Prime Video button"), Ui.tr("Botão YouTube", "YouTube button"),
                    Ui.tr("Botão Crunchyroll", "Crunchyroll button")};
            return new String[] {Ui.tr("Botão Netflix", "Netflix button"), Ui.tr("Botão YouTube", "YouTube button"),
                    Ui.tr("Botão Prime Video", "Prime Video button"), Ui.tr("Botão TCL Channel", "TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado", "Not installed");
            }
            return v;
        }

        /** The rows of the list screens: Home Settings, Remote Buttons, Glasses, Power. */
        private String[] listLabels() {
            return screen == REMOTE_KEYS ? keyLabels() : screen == GLASSES ? glassesLabels() : screen == POWER ? powerLabels()
                    : settingLabels();
        }

        private String[] listValues() {
            return screen == REMOTE_KEYS ? keyValues() : screen == GLASSES ? glassesValues() : screen == POWER ? powerValues()
                    : settingValues();
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Padrão da Sony", "Sony default") : Ui.tr("Nada", "Nothing"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == GLASSES) {
                adjustGlasses(dir);
                return;
            }
            if (screen == POWER) {
                adjustPower(dir);
                return;
            }
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            switch (focus) {
                case 0: {
                    int at = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language());
                    Prefs.setLanguage(LANGUAGES[(at + dir + LANGUAGES.length) % LANGUAGES.length]);
                    break;
                }
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case 3: Prefs.setSounds(!Prefs.sounds()); break;
                case 4: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case 6: Prefs.setQuotes(!Prefs.quotes()); break;
                default:
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(focus - COIN_ROWS, !Prefs.coin(focus - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if (Ui.tr("Procurando…", "Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Digite o nome da cidade", "Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Cidade", "City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Buscar", "Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Procurando…", "Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("Nenhuma cidade com esse nome.", "No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.tr("Não deu para buscar agora: ", "Couldn't search now: ") + e.getMessage();
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT || screen == WIFI || screen == BT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : screen == WIFI ? 1 + wifiCount() : screen == BT ? 1 + btCount() : pickLabels.size();
        }

        /** The value after (or before) `now` in `values`, round; a value not in the list goes to an end. */
        private static int nextValue(int[] values, int now, int dir) {
            int at = -1;
            for (int i = 0; i < values.length; i++) if (values[i] == now) at = i;
            return values[at < 0 ? (dir > 0 ? 0 : values.length - 1) : (at + dir + values.length) % values.length];
        }

        // MARK: - Glasses (Rokid Station)

        /** The Glasses page: four settings, then Brightness and Display zoom, which open Rokid's own sliders. */
        private static final int GLASSES_ROWS = 6, SLIDER_ROW = 4;

        private static String sceneName(int scene) {
            switch (scene) {
                case 1: return Ui.tr("Padrão", "Standard");
                case 2: return Ui.tr("Filme", "Movie");
                case 3: return Ui.tr("Leitura", "Reading");
                case 4: return Ui.tr("Descanso visual", "Eye care");
                default: return "—";
            }
        }

        private static String hz(int fps) {
            return fps + (Ui.ru ? " Гц" : " Hz");
        }

        /** The main row's value: 3D, or 2D with its refresh rate. */
        private String glassesSummary() {
            return glasses == null ? "" : glasses.is3d() ? "3D" : "2D · " + hz(glasses.fps);
        }

        private String[] glassesLabels() {
            return new String[] {Ui.tr("Modo", "Mode"), Ui.tr("Taxa de atualização", "Refresh rate"), Ui.tr("Imagem", "Picture"),
                    Ui.tr("Alto-falantes", "Speakers"), Ui.tr("Brilho", "Brightness"), Ui.tr("Zoom da tela", "Display zoom")};
        }

        private String[] glassesValues() {
            String[] v = new String[GLASSES_ROWS];
            if (glasses == null) {
                java.util.Arrays.fill(v, "—");
                return v;
            }
            v[0] = glasses.is3d() ? Ui.tr("3D (lado a lado)", "3D (side by side)") : "2D";
            v[1] = hz(glasses.fps);
            v[2] = sceneName(glasses.scene);
            v[3] = glasses.audio == 0 ? Ui.tr("Privado", "Private") : Ui.tr("Público", "Public");
            v[4] = v[5] = Ui.tr("Abrir", "Open");
            return v;
        }

        /** In 3D the glasses run at 60 Hz only: the refresh rate row is locked. */
        private boolean glassesLocked(int row) {
            return row == 1 && glasses != null && glasses.is3d();
        }

        /** Reads the glasses again; the Glasses row comes and goes with them. */
        private void refreshGlasses() {
            if (glassesChanges > 0) return;
            Context app = getContext().getApplicationContext();
            work.execute(() -> {
                Rokid.State s = Rokid.read(app);
                handler.post(() -> {
                    if (glassesChanges > 0) return;
                    boolean had = glasses != null;
                    glasses = s;
                    if (had != (s != null)) {
                        int row = screen == MAIN ? mainRow(focus) : -1;
                        mainRows = mainRows(s != null);
                        if (row >= 0) focus = mainIndex(row);
                        if (screen == GLASSES && s == null) {
                            screen = MAIN;
                            focus = 0;
                        }
                        spoken.focusMoved();
                    }
                    invalidate();
                });
            });
        }

        /** The focused row one step on: shown at once, then what the glasses took (120 Hz reads back as its own mode). */
        private void adjustGlasses(int dir) {
            if (glasses == null || glassesChanges >= 3) return;   // a held arrow doesn't queue a long run of changes
            Rokid.State g = glasses;
            int row = focus, want;
            if (row >= SLIDER_ROW) return;   // Brightness and Display zoom only open, with OK
            if (glassesLocked(row)) {
                glassesLine = Ui.tr("120 Hz é só no 2D.", "120 Hz is for 2D only.");
                return;
            }
            switch (row) {
                case 0: want = g.mode = g.is3d() ? Rokid.MODE_2D : Rokid.MODE_3D; break;
                case 1: want = g.fps = nextValue(Rokid.FPS, g.fps, dir); break;
                case 2: want = g.scene = nextValue(Rokid.SCENES, g.scene, dir); break;
                default: want = g.audio = nextValue(Rokid.AUDIO, g.audio, dir); break;
            }
            glassesChanges++;
            glassesLine = "";
            Context app = getContext().getApplicationContext();
            work.execute(() -> {
                Rokid.State now = row == 0 ? Rokid.setMode(app, want) : row == 1 ? Rokid.setFps(app, want)
                        : row == 2 ? Rokid.setScene(app, want) : Rokid.setAudio(app, want);
                handler.post(() -> {
                    if (--glassesChanges > 0) return;
                    if (now == null) {
                        glassesLine = Ui.tr("Não deu para falar com os óculos.", "Couldn't reach the glasses.");
                        refreshGlasses();
                    } else {
                        glasses = now;
                        int got = row == 0 ? (now.is3d() ? Rokid.MODE_3D : Rokid.MODE_2D) : row == 1 ? now.fps
                                : row == 2 ? now.scene : now.audio;
                        if (got != want) glassesLine = Ui.tr("Os óculos não aceitaram essa mudança.", "The glasses didn't take that change.");
                    }
                    invalidate();
                });
            });
        }

        // MARK: - Power (Rokid Station)

        /** The main row's value: the battery, and whether it's charging. */
        private String powerSummary() {
            return battery == null ? "" : battery.level + "%" + (battery.plugged ? " · " + Ui.tr("Carregando", "Charging") : "");
        }

        private String[] powerLabels() {
            return new String[] {Ui.tr("Bateria", "Battery"), Ui.tr("Tela de descanso após", "Screensaver after"),
                    Ui.tr("Dormir após", "Sleep after"), Ui.tr("Timer para dormir", "Sleep timer")};
        }

        private String[] powerValues() {
            String[] v = new String[4];
            v[0] = battery == null ? "—" : battery.level + "% · " + (battery.plugged ? Ui.tr("Carregando", "Charging") : Ui.tr("Na bateria", "On battery"));
            v[1] = timeouts == null ? "—" : Power.seconds(timeouts.screensaver);
            v[2] = timeouts == null ? "—" : timeouts.sleep < 0 ? Ui.tr("Nunca", "Never") : Power.seconds(timeouts.sleep);
            int left = Power.left(timerEnd);
            v[3] = left == 0 ? Ui.tr("Desligado", "Off") : Power.duration(left);
            return v;
        }

        /** The battery and the timer at once; the timeouts from the TV's ADB. */
        private void refreshPower() {
            if (powerChanges > 0) return;
            Context app = getContext().getApplicationContext();
            battery = Power.battery(app);
            timerEnd = Power.timerEnd(app);
            work.execute(() -> {
                Power.Timeouts t;
                try {
                    t = Power.timeouts(app);
                } catch (IOException e) {
                    AppLog.w("power: " + e.getMessage());
                    t = null;
                }
                Power.Timeouts read = t;
                handler.post(() -> {
                    if (powerChanges > 0) return;
                    if (read != null) timeouts = read;
                    else powerLine = Ui.tr("Não deu para ler os ajustes de energia.", "Couldn't read the power settings.");
                    invalidate();
                });
            });
        }

        /** The focused row one step on: the battery is only shown; the timer is ours; the timeouts are read back. */
        private void adjustPower(int dir) {
            int row = focus;
            if (row == 0) return;
            if (row == 3) {
                Power.setTimer(getContext(), Power.nextTimer(Power.left(timerEnd), dir));
                timerEnd = Power.timerEnd(getContext());
                powerLine = "";
                return;
            }
            if (timeouts == null || powerChanges >= 3) return;   // a held arrow doesn't queue a long run of changes
            Power.Timeouts t = timeouts;
            int want = row == 1 ? (t.screensaver = nextValue(Power.SCREENSAVER, t.screensaver, dir))
                    : (t.sleep = nextValue(Power.SLEEP, t.sleep, dir));
            powerChanges++;
            powerLine = "";
            Context app = getContext().getApplicationContext();
            work.execute(() -> {
                Power.Timeouts now;
                try {
                    now = row == 1 ? Power.setScreensaver(app, want) : Power.setSleep(app, want);
                } catch (IOException e) {
                    AppLog.w("power: " + e.getMessage());
                    now = null;
                }
                Power.Timeouts got = now;
                handler.post(() -> {
                    if (--powerChanges > 0) return;
                    if (got == null) {
                        powerLine = Ui.tr("Não deu para mudar os ajustes de energia.", "Couldn't change the power settings.");
                    } else {
                        timeouts = got;
                        if ((row == 1 ? got.screensaver : got.sleep) != want) {
                            powerLine = Ui.tr("A Station não aceitou essa mudança.", "The Station didn't take that change.");
                        }
                    }
                    invalidate();
                });
            });
        }

        // MARK: - Wi-Fi (Rokid Station)

        private int wifiCount() {
            return wifi == null ? 0 : wifi.networks.size();
        }

        /** The network at list position `at` (0 is the Wi-Fi on/off row). */
        private Wifi.Network wifiNet(int at) {
            return wifi != null && at >= 1 && at <= wifi.networks.size() ? wifi.networks.get(at - 1) : null;
        }

        private String wifiOnOff() {
            return wifi == null ? "…" : onOff(wifi.enabled);
        }

        /** The main row's value: the network in use. */
        private String wifiSummary() {
            if (wifi == null) return "";
            if (!wifi.enabled) return onOff(false);
            return wifi.connected.isEmpty() ? Ui.tr("Não conectado", "Not connected") : wifi.connected;
        }

        private static String netValue(Wifi.Network n) {
            if (n.connected) return Ui.tr("Conectado", "Connected");
            if (n.saved()) return Ui.tr("Salva", "Saved");
            if (n.security.equals("open") || n.security.equals("owe")) return Ui.tr("Aberta", "Open");
            return "";
        }

        private String[][] forgetRows() {
            boolean inUse = wifiPicked != null && wifiPicked.connected;
            return new String[][] {
                {Ui.tr("Esquecer esta rede", "Forget this network"), inUse ? Ui.tr("A Station se desconecta dela agora.", "The Station disconnects from it now.")
                        : Ui.tr("Ela não entra mais sozinha.", "It won't join by itself anymore.")},
                {Ui.tr("Cancelar", "Cancel"), Ui.tr("Manter salva", "Keep it saved")}};
        }

        private void openWifi() {
            screen = WIFI;
            focus = 0;
            pickScroll = 0;
            wifiLine = "";
            handler.removeCallbacks(wifiScan);
            handler.post(wifiScan);
        }

        /** Reads the Wi-Fi in the background; with `scan`, shows what's known at once, scans, and reads again. */
        private void refreshWifi(boolean scan) {
            Context c = getContext();
            work.execute(() -> {
                try {
                    if (scan) {
                        showWifi(Wifi.read(c));
                        Wifi.scan(c);
                        SystemClock.sleep(4000);
                    }
                    showWifi(Wifi.read(c));
                } catch (IOException e) {
                    AppLog.w("wifi: " + e.getMessage());
                    handler.post(() -> {
                        wifiLine = Ui.tr("Não deu para ler o Wi-Fi: ", "Couldn't read the Wi-Fi: ") + e.getMessage();
                        invalidate();
                    });
                }
            });
        }

        private void showWifi(Wifi.Status s) {
            handler.post(() -> {
                Wifi.Network was = screen == WIFI ? wifiNet(pickScroll + focus) : null;
                wifi = s;
                if (screen == WIFI) keepWifiFocus(was == null ? null : was.ssid);
                invalidate();
            });
        }

        /** After the list changes (the networks move with their signal), the focus stays on the same network. */
        private void keepWifiFocus(String ssid) {
            int total = 1 + wifiCount(), visible = Math.min(PICK_VISIBLE, total);
            int at = Math.min(pickScroll + focus, total - 1);
            for (int i = 0; ssid != null && i < wifiCount(); i++) if (wifi.networks.get(i).ssid.equals(ssid)) at = i + 1;
            if (at < pickScroll) pickScroll = at;
            else if (at >= pickScroll + visible) pickScroll = at - visible + 1;
            pickScroll = Math.max(0, Math.min(pickScroll, total - visible));
            focus = at - pickScroll;
        }

        /** OK on the list: Wi-Fi on or off, forget a saved network, or join a new one (asking for its password). */
        private void activateWifi() {
            if (wifiBusy) return;
            int at = pickScroll + focus;
            if (at == 0) {
                if (wifi == null) return;
                boolean on = !wifi.enabled;
                Context c = getContext();
                wifiBusy = true;
                work.execute(() -> {
                    try {
                        Wifi.setEnabled(c, on);
                    } catch (IOException e) {
                        AppLog.w("wifi on/off: " + e.getMessage());
                    }
                    handler.post(() -> wifiBusy = false);
                });
                refreshWifi(true);
                return;
            }
            Wifi.Network n = wifiNet(at);
            if (n == null) return;
            if (n.connected || n.saved()) {
                wifiPicked = n;
                screen = WIFI_FORGET;
                focus = 1;   // Cancel: forgetting is never one press away
            } else if (n.security.isEmpty()) {
                wifiLine = Ui.tr("Esta rede pede um login que o Free My TV não faz: use os Ajustes da TV.",
                        "This network needs a sign-in Free My TV can't do: use TV Settings.");
            } else if (n.needsPassword()) {
                askPassword(n);
            } else {
                joinWifi(n, "");
            }
        }

        private void askPassword(Wifi.Network n) {
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            // A visible password: the TV's keyboard types plain letters, and the person sees what they typed.
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            field.setHint(Ui.tr("Senha do Wi-Fi", "Wi-Fi password"));
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(n.ssid)
                    .setView(field)
                    .setPositiveButton(Ui.tr("Conectar", "Connect"), (d, which) -> typedPassword(n, field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                typedPassword(n, field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void typedPassword(Wifi.Network n, String password) {
            if (!Wifi.validPassword(password)) {
                wifiLine = Ui.tr("A senha tem de 8 a 63 letras, números ou símbolos.", "The password has 8 to 63 letters, numbers or symbols.");
                invalidate();
                return;
            }
            joinWifi(n, password);
        }

        private void joinWifi(Wifi.Network n, String password) {
            Context c = getContext();
            wifiBusy = true;
            wifiLine = Ui.tr("Conectando a ", "Connecting to ") + n.ssid + "…";
            invalidate();
            work.execute(() -> {
                String line;
                try {
                    line = Wifi.connect(c, n, password) ? Ui.tr("Conectado a ", "Connected to ") + n.ssid + "."
                            : Ui.tr("Não deu para conectar a ", "Couldn't connect to ") + n.ssid
                            + (n.needsPassword() ? Ui.tr(". Confira a senha.", ". Check the password.") : ".");
                } catch (IOException e) {
                    line = Ui.tr("Não deu para conectar a ", "Couldn't connect to ") + n.ssid + ": " + e.getMessage();
                }
                String result = line;
                handler.post(() -> {
                    wifiBusy = false;
                    wifiLine = result;
                    invalidate();
                });
                try {
                    showWifi(Wifi.read(c));
                } catch (IOException e) {
                    AppLog.w("wifi: " + e.getMessage());
                }
            });
        }

        private void forgetWifi(Wifi.Network n) {
            Context c = getContext();
            work.execute(() -> {
                try {
                    Wifi.forget(c, n);
                    handler.post(() -> {
                        wifiLine = Ui.tr("Esqueci ", "Forgot ") + n.ssid + ".";
                        invalidate();
                    });
                    showWifi(Wifi.read(c));
                } catch (IOException e) {
                    AppLog.w("wifi forget: " + e.getMessage());
                }
            });
        }

        /** Networks nearby, the one in use first: signal bars, a lock on the ones with a password. */
        private void drawWifi(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Wi-Fi", "Wi-Fi"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            Context ctx = getContext();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(0xF2FFFFFF, INK, f);
                rowLabel.setColor(ink);
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel), left = rowRect.left + 30 * k, right = rowRect.right - 30 * k;
                Wifi.Network n = wifiNet(at);
                if (n == null) {
                    c.drawText(Ui.tr("Wi-Fi", "Wi-Fi"), left, base, rowLabel);
                    c.drawText(wifiOnOff(), right, base, rowValue);
                    continue;
                }
                float bw = Symbols.width(ctx, "wifi_4", 28 * k);
                Symbols.draw(ctx, c, WIFI_BARS[n.bars()], right - bw / 2, rowRect.centerY(), 28 * k, ink);
                right -= bw + 16 * k;
                if (n.needsPassword()) {
                    float lw = Symbols.width(ctx, "lock", 22 * k);
                    Symbols.draw(ctx, c, "lock", right - lw / 2, rowRect.centerY(), 22 * k, blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    right -= lw + 16 * k;
                }
                String value = netValue(n);
                if (!value.isEmpty()) {
                    c.drawText(value, right, base, rowValue);
                    right -= rowValue.measureText(value) + 24 * k;
                }
                c.drawText(TextUtils.ellipsize(n.ssid, new TextPaint(rowLabel), right - left, TextUtils.TruncateAt.END).toString(), left, base, rowLabel);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            String line = !wifiLine.isEmpty() ? wifiLine : wifi == null ? Ui.tr("Procurando redes…", "Searching for networks…")
                    : !wifi.enabled ? Ui.tr("O Wi-Fi está desligado.", "Wi-Fi is off.")
                    : wifi.networks.isEmpty() ? Ui.tr("Procurando redes…", "Searching for networks…")
                    : Ui.tr("OK conecta. As redes salvas entram sozinhas.", "OK connects. Saved networks join by themselves.");
            c.drawText(TextUtils.ellipsize(line, new TextPaint(hint), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(),
                    LIST_X * k + 30 * k, hy, hint);
        }

        /** A saved network: forget it, or keep it (the focus starts on keeping it). */
        private void drawForget(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(TextUtils.ellipsize(wifiPicked.ssid, new TextPaint(heading), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] rows = forgetRows();
            for (int i = 0; i < rows.length; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(rows[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(rows[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
            }
        }

        // MARK: - Bluetooth (Rokid Station)

        private int btCount() {
            return btPaired.size() + btFound.size();
        }

        /** The device at list position `at`: 0 is "Pair a new device", then the paired ones, then what the search found. */
        private Bluetooth.Device btItem(int at) {
            if (at < 1) return null;
            at--;
            if (at < btPaired.size()) return btPaired.get(at);
            at -= btPaired.size();
            return at < btFound.size() ? btFound.get(at) : null;
        }

        /** The main row's value: the speaker or headphones in use. */
        private String btSummary() {
            for (Bluetooth.Device d : btPaired) if (d.connected && d.audio) return d.name;
            return "";
        }

        private String btValue(Bluetooth.Device d) {
            if (!d.bonded) return d == btPairing ? Ui.tr("Pareando…", "Pairing…") : Ui.tr("Parear", "Pair");
            if (d.peripheral) return Ui.tr("Controle", "Remote");
            return d.connected ? Ui.tr("Conectado", "Connected") : "";
        }

        /** What OK offers on a paired device: connect or disconnect (speakers, headphones), then forget it. */
        private String[][] btActions() {
            List<String[]> rows = new ArrayList<>();
            if (btPicked != null && btPicked.audio) {
                rows.add(btPicked.connected
                        ? new String[] {Ui.tr("Desconectar", "Disconnect"), Ui.tr("O som volta para os óculos.", "The sound goes back to the glasses.")}
                        : new String[] {Ui.tr("Conectar", "Connect"), Ui.tr("O som da Station sai por ele.", "Play the Station's sound on it.")});
            }
            rows.add(new String[] {Ui.tr("Esquecer este aparelho", "Forget this device"), Ui.tr("Para usar, pareie de novo.", "Pair it again to use it.")});
            return rows.toArray(new String[0][]);
        }

        /** Listens to the search, pairing and connections, and opens the audio profile (to connect speakers). */
        private void openBt() {
            // A pairing that ended while the screen was away (its broadcast missed) doesn't block the next one.
            if (btPairing != null && btPairing.device.getBondState() != BluetoothDevice.BOND_BONDING) btPairing = null;
            if (!btListening) {
                IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
                f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
                f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
                f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
                getContext().registerReceiver(btEvents, f);
                btListening = true;
                Bluetooth.openAudio(getContext(), new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (btListening) a2dp = proxy; else Bluetooth.closeAudio(proxy);
                    }

                    @Override public void onServiceDisconnected(int profile) {
                        a2dp = null;
                    }
                });
            }
            refreshBt();
        }

        private void closeBt() {
            if (!btListening) return;
            btListening = false;
            getContext().unregisterReceiver(btEvents);
            Bluetooth.stopSearch();
            btSearching = false;
            Bluetooth.closeAudio(a2dp);
            a2dp = null;
        }

        private void refreshBt() {
            work.execute(() -> {
                List<Bluetooth.Device> paired = Bluetooth.paired();
                handler.post(() -> {
                    Bluetooth.Device was = screen == BT ? btItem(pickScroll + focus) : null;
                    btPaired = paired;
                    for (Bluetooth.Device p : paired) {
                        for (int i = btFound.size() - 1; i >= 0; i--) if (btFound.get(i).address.equals(p.address)) btFound.remove(i);
                    }
                    if (screen == BT) keepBtFocus(was == null ? null : was.address);
                    invalidate();
                });
            });
        }

        /** A device the search found: kept once (its strongest signal), speakers and headphones first. */
        private void addFound(Bluetooth.Device dev) {
            Bluetooth.Device was = screen == BT ? btItem(pickScroll + focus) : null;
            for (int i = btFound.size() - 1; i >= 0; i--) {
                Bluetooth.Device old = btFound.get(i);
                if (!old.address.equals(dev.address)) continue;
                if (old.rssi > dev.rssi) dev.rssi = old.rssi;
                btFound.remove(i);
            }
            btFound.add(dev);
            Collections.sort(btFound, (x, y) -> x.audio != y.audio ? (x.audio ? -1 : 1) : y.rssi - x.rssi);
            if (screen == BT) keepBtFocus(was == null ? null : was.address);
        }

        /** After the list changes, the focus stays on the same device. */
        private void keepBtFocus(String address) {
            int total = 1 + btCount(), visible = Math.min(PICK_VISIBLE, total);
            int at = Math.min(pickScroll + focus, total - 1);
            for (int i = 1; address != null && i < total; i++) if (btItem(i).address.equals(address)) at = i;
            if (at < pickScroll) pickScroll = at;
            else if (at >= pickScroll + visible) pickScroll = at - visible + 1;
            pickScroll = Math.max(0, Math.min(pickScroll, total - visible));
            focus = at - pickScroll;
        }

        /** OK on the list: search, pair a found device, or open a paired one's choices (never the remote's). */
        private void activateBt() {
            int at = pickScroll + focus;
            if (at == 0) {
                if (Bluetooth.search()) {
                    btSearching = true;
                    btLine = "";
                } else {
                    btLine = Ui.tr("Não deu para procurar aparelhos agora.", "Couldn't search for devices right now.");
                }
                return;
            }
            Bluetooth.Device d = btItem(at);
            if (d == null) return;
            if (!d.bonded) {
                if (btPairing != null) return;
                if (Bluetooth.pair(d)) {
                    btPairing = d;
                    btLine = Ui.tr("Pareando com ", "Pairing with ") + d.name + "…";
                } else {
                    btLine = Ui.tr("Não deu para parear com ", "Couldn't pair with ") + d.name
                            + Ui.tr(". Coloque-o no modo de pareamento e tente de novo.", ". Put it in pairing mode and try again.");
                }
            } else if (d.peripheral) {
                btLine = Ui.tr("O controle fica como está: pareie ou esqueça nos Ajustes da TV.",
                        "The remote stays as it is: pair or forget it in TV Settings.");
            } else {
                btPicked = d;
                screen = BT_DEVICE;
                focus = 0;
            }
        }

        private void activateBtDevice() {
            Bluetooth.Device d = btPicked;
            boolean connectRow = d.audio && focus == 0;
            screen = BT;
            keepBtFocus(d.address);
            if (!connectRow) {
                btLine = Bluetooth.forget(d) ? Ui.tr("Esqueci ", "Forgot ") + d.name + "."
                        : Ui.tr("Não deu para esquecer ", "Couldn't forget ") + d.name + ".";
                refreshBt();
                return;
            }
            boolean on = !d.connected;
            if (!Bluetooth.connect(a2dp, d, on)) {
                btLine = Ui.tr("Não deu para conectar a ", "Couldn't connect to ") + d.name + ".";
                return;
            }
            if (!on) return;
            String connecting = Ui.tr("Conectando a ", "Connecting to ") + d.name + "…";
            btLine = connecting;
            // It takes a few seconds; the connection broadcasts refresh the list, and this says how it went.
            handler.postDelayed(() -> {
                if (!connecting.equals(btLine)) return;
                boolean ok = false;
                for (Bluetooth.Device p : btPaired) ok |= p.address.equals(d.address) && p.connected;
                btLine = ok ? Ui.tr("Conectado a ", "Connected to ") + d.name + "."
                        : Ui.tr("Não deu para conectar a ", "Couldn't connect to ") + d.name
                        + Ui.tr(". Ligue o aparelho e tente de novo.", ". Turn it on and try again.");
                invalidate();
            }, 10_000);
        }

        /** Pair a new device, the paired ones (the remote last), then the ones the search found. */
        private void drawBt(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText("Bluetooth", LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                Bluetooth.Device d = btItem(at);
                boolean dim = d != null && d.peripheral;
                rowLabel.setColor(blend(dim ? 0x80FFFFFF : 0xF2FFFFFF, dim ? 0x801C1C1E : INK, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel), left = rowRect.left + 30 * k, right = rowRect.right - 30 * k;
                String label = d == null ? Ui.tr("Parear um aparelho novo", "Pair a new device") : d.name;
                String value = d == null ? (btSearching ? Ui.tr("Procurando…", "Searching…") : "") : btValue(d);
                if (!value.isEmpty()) {
                    c.drawText(value, right, base, rowValue);
                    right -= rowValue.measureText(value) + 24 * k;
                }
                c.drawText(TextUtils.ellipsize(label, new TextPaint(rowLabel), right - left, TextUtils.TruncateAt.END).toString(), left, base, rowLabel);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            String line = !btLine.isEmpty() ? btLine : btSearching
                    ? Ui.tr("Coloque o aparelho no modo de pareamento e escolha abaixo.", "Put the device in pairing mode, then pick it below.")
                    : Ui.tr("OK pareia, conecta ou esquece. O controle fica como está.", "OK pairs, connects or forgets. The remote stays as it is.");
            c.drawText(TextUtils.ellipsize(line, new TextPaint(hint), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(),
                    LIST_X * k + 30 * k, hy, hint);
        }

        /** A paired speaker or headphones: connect or disconnect, or forget it. */
        private void drawBtDevice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(TextUtils.ellipsize(btPicked.name, new TextPaint(heading), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] rows = btActions();
            for (int i = 0; i < rows.length; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(rows[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(rows[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
            }
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            shelfOrder.clear();
            // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the limit)
            // without a row to take it off.
            PackageManager pm = getContext().getPackageManager();
            for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
            rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Prateleira", "Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, place >= 0 ? INK : 0x801C1C1E, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                c.drawText(place >= 0 ? "#" + (place + 1) : Ui.tr("Fora", "Off"), rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK põe ou tira da prateleira (até " + HomeApps.MAX_SHELF + "). ← → mudam a ordem.",
                    "OK adds or removes (up to " + HomeApps.MAX_SHELF + "). ← → change the order.",
                    "OK pone o quita del estante (hasta " + HomeApps.MAX_SHELF + "). ← → cambian el orden."), LIST_X * k + 30 * k, hy, hint);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            String title = pickFor == PICK_CITY ? Ui.tr("Cidade", "City") : keyLabels()[pickFor - 1];
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS, eyes = screen == GLASSES, power = screen == POWER;
            String[] labels = listLabels();
            String[] values = listValues();
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(keys ? Ui.tr("Botões do Controle", "Remote Buttons") : eyes ? Ui.tr("Óculos", "Glasses")
                    : power ? Ui.tr("Energia", "Power") : Ui.tr("Ajustes do Início", "Home Settings"),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                boolean locked = screen == HOME_SETTINGS && i >= COIN_ROWS && !Prefs.quotes() || eyes && glassesLocked(i);
                rowValue.setColor(blend(locked ? 0x59FFFFFF : 0xB3FFFFFF, locked ? 0x591C1C1E : 0xB31C1C1E, f));
                if (locked) rowLabel.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + labels.length * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), LIST_X * k + 30 * k, hy, hint);
            if (eyes || power) {
                String line = eyes ? (!glassesLine.isEmpty() ? glassesLine : Ui.tr("O Início volta os óculos para o 2D.", "Home puts the glasses back in 2D."))
                        : !powerLine.isEmpty() ? powerLine : timeouts != null && timeouts.stayOnPlugged
                        ? Ui.tr("Na tomada, a tela de descanso não começa.", "While plugged in, the screensaver doesn't start.") : "";
                c.drawText(TextUtils.ellipsize(line, new TextPaint(hint), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(),
                        LIST_X * k + 30 * k, hy + 38 * k, hint);
            } else if (keys) {
                c.drawText(RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video e Google Play: a própria TV controla esses botões.",
                        "YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                        : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube e Prime Video: a própria TV controla esses botões.",
                        "Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                        : RemoteKeys.SONY ? Ui.tr("Padrão da Sony: o botão abre o mesmo app de sempre.",
                        "Sony default: the button opens what it always did.")
                        : RemoteKeys.FORMOVIE ? Ui.tr("Os outros botões: a própria TV os controla.", "The other buttons: the TV itself controls them.")
                        : RemoteKeys.supported() ? Ui.tr("Disney e Globoplay: a própria TV controla esses botões.",
                        "Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("Botões do controle da TCL: nesta TV funcionam como sempre.",
                        "TCL remote buttons: on this TV they work as always."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == WIFI) {
                activateWifi();
                invalidate();
                spoken.focusMoved();
                return;
            }
            if (screen == WIFI_FORGET) {
                if (focus == 0) forgetWifi(wifiPicked);
                screen = WIFI;
                keepWifiFocus(wifiPicked.ssid);
                invalidate();
                spoken.focusMoved();
                return;
            }
            if (screen == BT || screen == BT_DEVICE) {
                if (screen == BT) activateBt(); else activateBtDevice();
                invalidate();
                spoken.focusMoved();
                return;
            }
            if (screen == POWER) {
                adjustPower(1);
                invalidate();
                return;
            }
            if (screen == GLASSES && focus >= SLIDER_ROW) {
                if (!Rokid.openSlider(c, focus == SLIDER_ROW ? Rokid.BRIGHTNESS : Rokid.ZOOM)) {
                    glassesLine = Ui.tr("Não deu para abrir.", "Couldn't open it.");
                }
                invalidate();
                return;
            }
            if (screen == GLASSES) {
                adjustGlasses(1);
                invalidate();
                return;
            }
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                focus = CITY_ROW;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS && focus == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (mainRow(focus) == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (mainRow(focus) == 1) {
                pause();
                activity.showPreview();
            } else if (mainRow(focus) == 2) {
                screen = HOME_SETTINGS;
                focus = 0;
            } else if (mainRow(focus) == 3) {
                openShelfEditor();
            } else if (mainRow(focus) == REMOTE_ROW) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else if (mainRow(focus) == WIFI_ROW) {
                openWifi();
            } else if (mainRow(focus) == BT_ROW) {
                screen = BT;
                focus = 0;
                pickScroll = 0;
                btLine = "";
                btFound.clear();
                openBt();
            } else if (mainRow(focus) == POWER_ROW) {
                screen = POWER;
                focus = 0;
                powerLine = "";
                refreshPower();
            } else if (mainRow(focus) == GLASSES_ROW) {
                screen = GLASSES;
                focus = 0;
                glassesLine = "";
                refreshGlasses();
            } else {
                HomeApps.openTvSettings(c);
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
