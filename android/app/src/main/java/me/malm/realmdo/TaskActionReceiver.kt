package me.malm.realmdo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Background work for the notification: "Done" taps and the refresh alarm.
 * Each does one short round of HTTP inside `goAsync()`, whose budget (about
 * ten seconds) the 5 s API timeouts fit under.
 */
class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = ctx.applicationContext
        val result = goAsync()
        thread(name = "realmdo-action") {
            try {
                when (action) {
                    TaskNotification.ACTION_DONE -> done(app, intent)
                    TaskNotification.ACTION_REFRESH -> {
                        BackgroundRefresh.onAlarm(app)
                        TaskNotification.refresh(app)
                    }
                }
            } finally {
                result.finish()
            }
        }
    }

    private fun done(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(TaskNotification.EXTRA_TASK_ID, -1)
        if (id < 0) return
        val api = Api.from(Prefs(ctx)) ?: return
        TaskNotification.vibrateTick(ctx)
        // A failure leaves the notification as is, so the tap can be retried.
        if (runCatching { api.done(id, intent.getStringExtra(TaskNotification.EXTRA_SLOT)) }.isSuccess) {
            TaskNotification.refresh(ctx)
        }
    }
}
