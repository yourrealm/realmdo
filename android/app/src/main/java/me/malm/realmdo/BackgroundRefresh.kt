package me.malm.realmdo

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Whether the refresh alarm reaches the app while it sits in the background,
 * and the way to the settings that decide it.
 *
 * Found on a Galaxy S24 (One UI 8.5, 2026-09-19): Samsung's Freecess freezes
 * a cached app and holds its alarms until it next comes to the foreground, so
 * the pinned task stayed stale all night. The ongoing notification does not
 * exempt the app; the battery setting "Unrestricted" (the Doze whitelist)
 * does. No API says "frozen", so the app measures it: every alarm reschedules
 * itself 15 minutes out and records that due time, and an alarm that fires,
 * or an app that opens, hours past it has the proof.
 */
object BackgroundRefresh {
    /** Lateness past which the alarm was held, not just batched. Inexact alarms drift by minutes. */
    const val HELD_MS = 60 * 60 * 1000L

    /** Without the app in the foreground for this long, an on-time alarm proves the chain runs on its own. */
    private const val AWAY_MS = 60 * 60 * 1000L

    data class Status(val exempt: Boolean, val restricted: Boolean, val heldMs: Long) {
        val held: Boolean get() = heldMs > HELD_MS
    }

    /** Take stock on resume. Call before anything reschedules the alarm. */
    fun onResume(ctx: Context): Status {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        prefs.refreshHeld = verdict(now, prefs.refreshDue, now - prefs.lastResume, prefs.refreshHeld)
        prefs.lastResume = now
        return Status(exempt = exempt(ctx), restricted = restricted(ctx), heldMs = prefs.refreshHeld)
    }

    /** The alarm fired. Call before the refresh reschedules it. */
    fun onAlarm(ctx: Context) {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        prefs.refreshHeld = verdict(now, prefs.refreshDue, now - prefs.lastResume, prefs.refreshHeld)
    }

    /**
     * How long the alarm has been held, in ms, or 0. [due] is when the pending
     * alarm should fire (0 when none is set), [away] how long the app has been
     * out of the foreground, [previous] the last verdict. Overdue by more than
     * [HELD_MS]: held. Roughly on time after a long absence: the chain ran on
     * its own, so cleared. Anything else says nothing new.
     */
    fun verdict(now: Long, due: Long, away: Long, previous: Long): Long {
        if (due == 0L) return previous
        val late = now - due
        return when {
            late > HELD_MS -> late
            late >= 0 && away > AWAY_MS -> 0
            else -> previous
        }
    }

    /** "9 h 22 min" or "45 min". */
    fun label(ms: Long): String {
        val minutes = ms / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    /** Battery "Unrestricted": the Doze whitelist, which Samsung's freezer honours too. */
    fun exempt(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** Battery "Restricted": no background work at all. */
    fun restricted(ctx: Context): Boolean =
        ctx.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true

    /** The system's "allow unrestricted battery use?" dialog for this app. */
    @SuppressLint("BatteryLife")
    fun exemptionDialog(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${ctx.packageName}".toUri())

    /**
     * Samsung's Battery page, where Background usage limits (Sleeping apps)
     * is one tap down, when this phone has it. The limits page itself
     * (`ACTION_START_APP_POWER_MANAGEMENT_SETTING`) demands a system
     * permission, so a resolved page is only offered when it demands none.
     */
    fun samsungBatteryPage(ctx: Context): Intent? {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return null
        val i = Intent("com.samsung.android.sm.ACTION_BATTERY")
        val pm = ctx.packageManager
        val found = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(i, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return i.takeIf { found?.activityInfo?.permission == null && found != null }
    }

    /** The app's own page in system settings; the fallback when a page is missing. */
    fun appInfo(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri())

    fun open(ctx: Context, intent: Intent) {
        runCatching { ctx.startActivity(intent) }.onFailure { ctx.startActivity(appInfo(ctx)) }
    }
}
