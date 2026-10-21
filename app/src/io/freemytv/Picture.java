package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Picture settings for XGIMI projectors, whose own are Chinese only: picture mode, HDR and motion smoothing, through
 * XGIMI's picture service (IGimiVideo, exported, with no permission), which hands them to the projector's platform as
 * XGIMI's Settings does. The service can't list a model's picture modes (XGIMI's Settings keeps a list per model), so
 * this is only on the models listed here: the Play 6. Game mode isn't here: its on/off is kept per HDMI input by XGIMI's
 * Settings, and IGimiVideo's game call only picks its latency. Its calls block: never on the main thread.
 */
final class Picture {
    private Picture() {}

    private static final ComponentName SERVICE = new ComponentName("com.xgimi.xgimiservice",
            "com.xgimi.xgimiservice.services.XgimiServices");
    private static final String XGIMI = "com.xgimi.aidl.IXgimiService", VIDEO = "com.xgimi.aidl.IGimiVideo";
    /** IXgimiService's getGimiVideo, then IGimiVideo's calls, numbered as XGIMI's own client numbers them. */
    private static final int GET_VIDEO = 8, GET_MODE = 1, SET_MODE = 2, SET_MOTION = 17, GET_MOTION = 18,
            SET_HDR = 34, GET_HDR = 35;

    /** The Play 6's picture modes in its menu's order: AI, Movie, Sport, TV, Custom, Office, Performance. */
    static final int[] MODES = {10, 1, 9, 7, 3, 25, 5};
    /** Motion smoothing: off, low, medium, high; XGIMI's menu has it in Movie, TV and Custom only. */
    static final int[] MOTION = {0, 1, 2, 3};
    private static final int[] MOTION_MODES = {1, 7, 3};

    static final class State {
        int mode, motion;
        boolean hdr;

        boolean hasMotion() {
            return indexOf(MOTION_MODES, mode) >= 0;
        }
    }

    /** XGIMI's Play 6 (its code name is dayao). */
    static boolean available() {
        return HomeMode.XGIMI && "Product_dayao".equals(Build.PRODUCT);
    }

    static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return -1;
    }

    private interface Call<T> {
        T run(IBinder video) throws RemoteException;
    }

    /** The settings now, or null when the service can't be reached. */
    static State read(Context c) {
        return withVideo(c, Picture::readAll);
    }

    private static State readAll(IBinder video) throws RemoteException {
        State s = new State();
        s.mode = getInt(video, GET_MODE);
        s.hdr = getInt(video, GET_HDR) != 0;
        s.motion = getInt(video, GET_MOTION);
        return s;
    }

    static State setMode(Context c, int mode) {
        return set(c, SET_MODE, mode);
    }

    static State setHdr(Context c, boolean on) {
        return set(c, SET_HDR, on ? 1 : 0);   // a boolean goes in a Parcel as an int
    }

    static State setMotion(Context c, int level) {
        return set(c, SET_MOTION, level);
    }

    /** Changes one setting, then reads them all back (a picture mode brings its own HDR and motion). */
    private static State set(Context c, int code, int value) {
        return withVideo(c, video -> {
            Parcel in = Parcel.obtain(), out = Parcel.obtain();
            try {
                in.writeInterfaceToken(VIDEO);
                in.writeInt(value);
                video.transact(code, in, out, 0);
                out.readException();
            } finally {
                in.recycle();
                out.recycle();
            }
            AppLog.w("picture: call " + code + " = " + value);
            SystemClock.sleep(300);   // the platform applies some of them on its own thread
            return readAll(video);
        });
    }

    private static int getInt(IBinder b, int code) throws RemoteException {
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            in.writeInterfaceToken(VIDEO);
            b.transact(code, in, out, 0);
            out.readException();
            return out.readInt();
        } finally {
            in.recycle();
            out.recycle();
        }
    }

    /** Binds XGIMI's service, runs `call` on its picture interface, unbinds. Null when any of it fails. */
    private static <T> T withVideo(Context c, Call<T> call) {
        Context app = c.getApplicationContext();
        CountDownLatch ready = new CountDownLatch(1);
        IBinder[] service = new IBinder[1];
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service[0] = binder;
                ready.countDown();
            }

            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        try {
            if (!app.bindService(new Intent(SERVICE.getClassName()).setComponent(SERVICE), conn, Context.BIND_AUTO_CREATE)) {
                AppLog.w("picture: XGIMI's service isn't there");
                return null;
            }
            if (!ready.await(5, TimeUnit.SECONDS)) {
                AppLog.w("picture: XGIMI's service didn't answer");
                return null;
            }
            Parcel in = Parcel.obtain(), out = Parcel.obtain();
            IBinder video;
            try {
                in.writeInterfaceToken(XGIMI);
                service[0].transact(GET_VIDEO, in, out, 0);
                out.readException();
                video = out.readStrongBinder();
            } finally {
                in.recycle();
                out.recycle();
            }
            return video == null ? null : call.run(video);
        } catch (RemoteException | RuntimeException | InterruptedException e) {
            AppLog.w("picture: " + e);
            return null;
        } finally {
            try {
                app.unbindService(conn);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
