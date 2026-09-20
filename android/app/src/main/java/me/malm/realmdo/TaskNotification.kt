package me.malm.realmdo

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The top task as one pinned notification: its title in the status-bar chip,
 * its standing on the second line, and the next few tasks in the expanded
 * view. On Android 16 it asks for Live Update promotion (status-bar chip,
 * lock screen, Now Bar on One UI) only while the top task is important, so
 * the chip means "something is due" rather than "the app is running"; below
 * 16, and while nothing is important, it is a plain ongoing notification.
 * Nothing counts down, so nothing re-posts on a timer: the notification
 * changes only when the list does. A refresh alarm every [REFRESH_MS]
 * catches changes made elsewhere and slots rolling over during the day.
 *
 * Lessons carried over from the workout app's rest timer: a body
 * `contentIntent` suppresses the One UI status-bar chip, so "Open" is an
 * action button instead; the chip text comes from `shortCriticalText`.
 */
object TaskNotification {
    private const val CHANNEL_ID = "top_task"
    const val NOTIF_ID = 7301
    const val ACTION_DONE = "me.malm.realmdo.DONE"
    const val ACTION_REFRESH = "me.malm.realmdo.REFRESH"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_SLOT = "slot"
    private const val REFRESH_MS = 15 * 60 * 1000L

    /** Fetch the list and post the top task, or clear when there is none. Blocking. */
    fun refresh(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.liveUpdate) {
            cancel(ctx)
            return
        }
        val api = Api.from(prefs) ?: return cancel(ctx)
        // A failed fetch keeps whatever is showing; the next refresh retries.
        val tasks = runCatching { api.tasks() }.getOrElse { return scheduleRefresh(ctx) }
        show(ctx, tasks)
    }

    /** Post the top of an already fetched list, or clear when nothing is visible. */
    fun show(ctx: Context, tasks: List<Task>) {
        if (!Prefs(ctx).liveUpdate) return cancel(ctx)
        val visible = tasks.filter { it.urgency >= 0 }
        if (visible.isEmpty()) {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
        } else {
            post(ctx, visible)
        }
        scheduleRefresh(ctx)
    }

    /** How many tasks after the top one the expanded view lists. */
    private const val NEXT_SHOWN = 3

    /** [visible] is the ranked list, top first, hidden tasks already dropped. */
    fun post(ctx: Context, visible: List<Task>) {
        ensureChannel(ctx)
        val task = visible.first()
        val standing = detail(task)
        val next = visible.drop(1).take(NEXT_SHOWN)
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(icon(task))
            .setColor(ctx.getColor(R.color.accent))
            .setContentTitle(task.title)
            .setShortCriticalText(chip(task))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_stat_task, ctx.getString(R.string.action_done), doneIntent(ctx, task))
            .addAction(R.drawable.ic_stat_task, ctx.getString(R.string.action_open), openIntent(ctx))
            .setRequestPromotedOngoing(task.important)
        // Collapsed: the standing. Expanded: the standing, then what comes next.
        val collapsed = standing ?: if (next.isEmpty()) null else "then ${next.first().title}"
        collapsed?.let { b.setContentText(it) }
        if (next.isNotEmpty()) {
            val lines = listOfNotNull(standing) + next.map { "· ${it.title}" }
            b.setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
        }
        val nm = NotificationManagerCompat.from(ctx)
        if (nm.areNotificationsEnabled()) nm.notify(NOTIF_ID, b.build())
    }

    private fun icon(task: Task): Int = when (task.kind) {
        "interval" -> R.drawable.ic_stat_interval
        "slot" -> R.drawable.ic_stat_slot
        else -> R.drawable.ic_stat_once
    }

    fun cancel(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
        cancelRefresh(ctx)
    }

    /** Whether the device will promote it. Only true on Android 16 with the permission. */
    fun canPromote(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return nm?.canPostPromotedNotifications() == true
    }

    /** A short click as eyes-free confirmation of a lock-screen "Done". */
    fun vibrateTick(ctx: Context) {
        val vm = ctx.getSystemService(VibratorManager::class.java) ?: return
        val v = vm.defaultVibrator
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    /**
     * One inexact, idle-tolerant alarm; each fire schedules the next. The due
     * time is kept so [BackgroundRefresh] can tell a held alarm from a late one.
     */
    private fun scheduleRefresh(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = refreshIntent(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val due = System.currentTimeMillis() + REFRESH_MS
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
        Prefs(ctx).refreshDue = due
    }

    private fun cancelRefresh(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        refreshIntent(ctx, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
        val prefs = Prefs(ctx)
        prefs.refreshDue = 0
        prefs.refreshHeld = 0
    }

    private fun refreshIntent(ctx: Context, flags: Int): PendingIntent? {
        val i = Intent(ctx, TaskActionReceiver::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(ctx, NOTIF_ID, i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun doneIntent(ctx: Context, task: Task): PendingIntent {
        val i = Intent(ctx, TaskActionReceiver::class.java)
            .setAction(ACTION_DONE)
            .putExtra(EXTRA_TASK_ID, task.id)
            .putExtra(EXTRA_SLOT, task.pendingSlot)
        // Distinct request code from the refresh alarm; extras follow the latest post.
        return PendingIntent.getBroadcast(
            ctx, NOTIF_ID + 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx, NOTIF_ID + 2, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT,
        )
        ch.description = ctx.getString(R.string.channel_description)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }
}
