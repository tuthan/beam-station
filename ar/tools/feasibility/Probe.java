package feasibility;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;

/**
 * Station AR's Phase 00 feasibility probe: a throwaway dex run as the shell with app_process from /data/local/tmp,
 * deleted afterwards. Nothing in it is installed or changes a setting.
 *
 *   sensors <seconds> <csv>     every Rokid glasses sensor event (Game RV, RV, gyroscope, accelerometer) to a file
 *   codecs                      decoders, their max instances and performance points (HEVC and AVC)
 *   decode <seconds> <file>...  decodes the files at once, as fast as they go, to ImageReaders; frames per second each
 *   vd <w> <h> <dpi> <seconds>  a virtual display (PUBLIC | OWN_CONTENT_ONLY) into an ImageReader; prints its id, then
 *                               frames per second and the picture's mean brightness every second
 */
public final class Probe {
    private static PrintStream out = System.out;
    private static volatile boolean snap;

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        switch (args[0]) {
            case "sensors": sensors(Integer.parseInt(args[1]), args[2]); break;
            case "codecs": codecs(); break;
            case "decode": decode(Integer.parseInt(args[1]), java.util.Arrays.copyOfRange(args, 2, args.length)); break;
            case "vd": vd(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4])); break;
            default: throw new IllegalArgumentException(args[0]);
        }
        System.exit(0);
    }

    // MARK: - A context for the shell (as scrcpy builds one)

    private static Context context;

    static Context context() throws Exception {
        if (context != null) return context;
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> c = at.getDeclaredConstructor();
        c.setAccessible(true);
        Object thread = c.newInstance();
        Field current = at.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);
        Method system = at.getDeclaredMethod("getSystemContext");
        Context base = (Context) system.invoke(thread);
        context = new ContextWrapper(base) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public Context getApplicationContext() { return this; }
        };
        return context;
    }

    // MARK: - Test 4: the IMU

    static void sensors(int seconds, String csv) throws Exception {
        SensorManager sm = (SensorManager) context().getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        HandlerThread ht = new HandlerThread("sensors");
        ht.start();
        Handler h = new Handler(ht.getLooper());
        final PrintStream file = new PrintStream(new FileOutputStream(csv), false);
        file.println("type,name,t_ns,v0,v1,v2,v3,v4");
        final long[] counts = new long[64];
        SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                int t = e.sensor.getType();
                if (t < counts.length) counts[t]++;
                StringBuilder b = new StringBuilder(96);
                b.append(t).append(',').append(e.sensor.getStringType()).append(',').append(e.timestamp);
                for (int i = 0; i < 5; i++) b.append(',').append(i < e.values.length ? e.values[i] : 0f);
                file.println(b);
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };
        for (Sensor s : all) {
            out.println(String.format(Locale.US, "sensor type=%d name=%s vendor=%s minDelay=%d wakeup=%b",
                    s.getType(), s.getName(), s.getVendor(), s.getMinDelay(), s.isWakeUpSensor()));
            if (!"Rokid".equals(s.getVendor())) continue;
            int t = s.getType();
            if (t == Sensor.TYPE_GAME_ROTATION_VECTOR || t == Sensor.TYPE_ROTATION_VECTOR
                    || t == Sensor.TYPE_GYROSCOPE || t == Sensor.TYPE_ACCELEROMETER
                    || t == Sensor.TYPE_PROXIMITY || t == Sensor.TYPE_LIGHT) {
                out.println("  registered: " + sm.registerListener(l, s, SensorManager.SENSOR_DELAY_FASTEST, h));
            }
        }
        out.println("boot " + SystemClock.elapsedRealtimeNanos() + " uptime " + SystemClock.uptimeMillis() * 1000000L
                + " nano " + System.nanoTime());
        long start = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - start < seconds * 1000L) {
            Thread.sleep(Math.min(10_000L, seconds * 1000L));
            out.println(String.format(Locale.US, "t=%ds accel=%d gyro=%d rv=%d grv=%d proximity=%d light=%d",
                    (SystemClock.elapsedRealtime() - start) / 1000, counts[1], counts[4], counts[11], counts[15],
                    counts[8], counts[5]));
        }
        sm.unregisterListener(l);
        file.close();
    }

    // MARK: - Test 6: decoders

    static void codecs() {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!type.equals("video/hevc") && !type.equals("video/avc")) continue;
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                MediaCodecInfo.VideoCapabilities v = caps.getVideoCapabilities();
                out.println(String.format(Locale.US, "%s %s maxInstances=%d hw=%b lowLatency=%b tunneled=%b secure=%b widths=%s heights=%s rates=%s",
                        info.getName(), type, caps.getMaxSupportedInstances(), info.isHardwareAccelerated(),
                        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
                        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback),
                        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback),
                        v.getSupportedWidths(), v.getSupportedHeights(), v.getSupportedFrameRates()));
                List<MediaCodecInfo.VideoCapabilities.PerformancePoint> points = v.getSupportedPerformancePoints();
                if (points != null) for (MediaCodecInfo.VideoCapabilities.PerformancePoint p : points) out.println("    " + p);
                out.println("    1080p60 supported: " + v.areSizeAndRateSupported(1920, 1080, 60)
                        + ", 3840x1080@60: " + v.areSizeAndRateSupported(3840, 1080, 60)
                        + ", achievable 1080p: " + v.getAchievableFrameRatesFor(1920, 1080));
            }
        }
    }

    static void decode(int seconds, String[] files) throws Exception {
        final int n = files.length;
        final long[] frames = new long[n];
        Thread[] threads = new Thread[n];
        final long until = SystemClock.elapsedRealtime() + seconds * 1000L;
        for (int i = 0; i < n; i++) {
            final int k = i;
            final String path = files[i];
            threads[i] = new Thread(() -> {
                try {
                    decodeOne(path, until, frames, k);
                } catch (Exception e) {
                    out.println("decoder " + k + ": " + e);
                }
            });
            threads[i].start();
        }
        long start = SystemClock.elapsedRealtime();
        long[] last = new long[n];
        while (SystemClock.elapsedRealtime() < until) {
            Thread.sleep(1000);
            StringBuilder b = new StringBuilder("t=" + (SystemClock.elapsedRealtime() - start) / 1000 + "s");
            for (int i = 0; i < n; i++) {
                b.append(" fps").append(i).append('=').append(frames[i] - last[i]);
                last[i] = frames[i];
            }
            out.println(b);
        }
        for (Thread t : threads) t.join(5000);
    }

    private static void decodeOne(String path, long until, long[] frames, int k) throws Exception {
        HandlerThread ht = new HandlerThread("reader" + k);
        ht.start();
        while (SystemClock.elapsedRealtime() < until) {
            MediaExtractor ex = new MediaExtractor();
            ex.setDataSource(path);
            int track = -1;
            MediaFormat format = null;
            for (int t = 0; t < ex.getTrackCount(); t++) {
                format = ex.getTrackFormat(t);
                if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) { track = t; break; }
            }
            ex.selectTrack(track);
            int w = format.getInteger(MediaFormat.KEY_WIDTH), h = format.getInteger(MediaFormat.KEY_HEIGHT);
            ImageReader reader = ImageReader.newInstance(w, h, android.graphics.ImageFormat.PRIVATE, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireNextImage();
                if (img != null) {
                    frames[k]++;
                    img.close();
                }
            }, new Handler(ht.getLooper()));
            MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            out.println("decoder " + k + ": " + codec.getName() + " " + w + "x" + h + " " + path);
            codec.configure(format, reader.getSurface(), null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (SystemClock.elapsedRealtime() < until) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(2000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = ex.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 2000);
                if (o >= 0) {
                    codec.releaseOutputBuffer(o, true);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            codec.stop();
            codec.release();
            reader.close();
            ex.release();
        }
    }

    // MARK: - Tests 1 and 5: a virtual display

    static void vd(int w, int h, int dpi, int seconds) throws Exception {
        Constructor<DisplayManager> c = DisplayManager.class.getDeclaredConstructor(Context.class);
        c.setAccessible(true);
        DisplayManager dm = c.newInstance(context());
        HandlerThread ht = new HandlerThread("vd");
        ht.start();
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        final long[] frames = new long[1];
        final int[] luma = new int[1];
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            frames[0]++;
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer b = p.getBuffer();
            int stride = p.getRowStride();
            long sum = 0;
            int count = 0;
            for (int y = h / 8; y < h; y += h / 8) {
                for (int x = w / 8; x < w; x += w / 8) {
                    int i = y * stride + x * 4;
                    sum += ((b.get(i) & 0xff) + (b.get(i + 1) & 0xff) + (b.get(i + 2) & 0xff)) / 3;
                    count++;
                }
            }
            luma[0] = (int) (sum / count);
            if (snap) {
                snap = false;
                try (FileOutputStream f = new FileOutputStream("/data/local/tmp/stationar-vd.png")) {
                    android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(stride / 4, h,
                            android.graphics.Bitmap.Config.ARGB_8888);
                    b.rewind();
                    bm.copyPixelsFromBuffer(b);
                    android.graphics.Bitmap.createBitmap(bm, 0, 0, w, h)
                            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, f);
                } catch (Exception e) {
                    out.println("snapshot: " + e);
                }
            }
            img.close();
        }, new Handler(ht.getLooper()));
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        VirtualDisplay display = dm.createVirtualDisplay("stationar-probe", w, h, dpi, reader.getSurface(), flags);
        out.println("display " + display.getDisplay().getDisplayId() + " flags=" + display.getDisplay().getFlags());
        long start = SystemClock.elapsedRealtime();
        long last = 0;
        while (SystemClock.elapsedRealtime() - start < seconds * 1000L) {
            Thread.sleep(1000);
            long t = (SystemClock.elapsedRealtime() - start) / 1000;
            out.println("t=" + t + "s fps=" + (frames[0] - last) + " luma=" + luma[0]);
            last = frames[0];
            if (t % 5 == 0) snap = true;   // a picture of the display every 5 s: /data/local/tmp/stationar-vd.png
        }
        display.release();
        reader.close();
    }
}
