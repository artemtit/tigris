package com.leslie.tvhome

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Control Center sleep timer: Off → 30 → 60 → 90 min → Off. On expiry the TV sleeps as the Sleep button does. */
object SleepTimer {
    val STEPS = listOf(0, 30, 60, 90)

    /** The step after [minutes]; anything unknown goes back to Off. */
    fun next(minutes: Int): Int = STEPS[(STEPS.indexOf(minutes) + 1) % STEPS.size]

    /** Whole minutes left, rounded up so the label never reads 0 while the timer still runs; 0 when off or past. */
    fun minutesLeft(at: Long, now: Long): Int = if (at <= now) 0 else ((at - now + 59_999) / 60_000).toInt()

    /** Advances the timer one step and (re)schedules or cancels the alarm. Returns the new step. */
    fun cycle(ctx: Context): Int {
        val prefs = Prefs(ctx)
        val current = if (minutesLeft(prefs.sleepTimerAt, System.currentTimeMillis()) > 0) prefs.sleepTimerMinutes else 0
        val step = next(current)
        set(ctx, step)
        return step
    }

    fun set(ctx: Context, minutes: Int) {
        val prefs = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, SleepTimerReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        prefs.sleepTimerMinutes = minutes
        if (minutes == 0) { prefs.sleepTimerAt = 0L; return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        prefs.sleepTimerAt = at
        // Exact where allowed (Android 11 needs no permission); a plain alarm may drift by most of its delay.
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        prefs.sleepTimerMinutes = 0
        prefs.sleepTimerAt = 0L
        // Same path as the Control Center's Sleep button (local adb, KEYCODE_SLEEP); kept alive until it is sent.
        val done = goAsync()
        Thread {
            try { LocalAdb.shell(context.applicationContext, Cleaner.SLEEP, 8_000) } finally { done.finish() }
        }.start()
    }
}
