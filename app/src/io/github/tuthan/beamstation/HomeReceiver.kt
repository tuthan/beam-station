package io.github.tuthan.beamstation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** System notices for the main process: the TV booted, this app was updated, or the sleep timer rang. Not exported. */
class HomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                HomeMode.bootCheck(context)
                if (Power.available()) Power.setTimer(context, 0)   // alarms don't outlive a reboot
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> HomeMode.verify(context)   // app updated: complete our mode if something is missing
            Power.SLEEP_TIMER -> {
                val result = goAsync()   // the TV's ADB is a network call
                Thread {
                    Power.timerRang(context)
                    result.finish()
                }.start()
            }
        }
    }
}
