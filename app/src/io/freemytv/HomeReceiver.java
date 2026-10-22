package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System notices for the main process: the TV booted, this app was updated, or the sleep timer rang. Not exported. */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
            if (Power.available()) Power.setTimer(context, 0);   // alarms don't outlive a reboot
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Station Home mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
        } else if (Power.SLEEP_TIMER.equals(action)) {
            PendingResult result = goAsync();   // the TV's ADB is a network call
            new Thread(() -> {
                Power.timerRang(context);
                result.finish();
            }).start();
        }
    }
}
