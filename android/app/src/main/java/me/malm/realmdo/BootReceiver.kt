package me.malm.realmdo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Re-posts the top task after a reboot; the alarm chain does not survive one. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = ctx.applicationContext
        val result = goAsync()
        thread(name = "realmdo-boot") {
            try {
                TaskNotification.refresh(app)
            } finally {
                result.finish()
            }
        }
    }
}
