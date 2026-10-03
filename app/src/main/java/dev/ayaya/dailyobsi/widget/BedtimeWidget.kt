package dev.ayaya.dailyobsi.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import dev.ayaya.dailyobsi.MainActivity
import dev.ayaya.dailyobsi.R
import dev.ayaya.dailyobsi.model.BedtimeStyle
import dev.ayaya.dailyobsi.model.bedtimeCountdown
import dev.ayaya.dailyobsi.model.nextBedtimeChange
import dev.ayaya.dailyobsi.storage.AppPreferences
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Time left until the static bedtime from Settings, like the user's
 * `bc-print.sh` (logic in model/Bedtime.kt).
 *
 * Plain RemoteViews, not Glance. A Glance update starts a WorkManager
 * session, which is too heavy for a once-a-minute refresh and can be delayed
 * when the app is in the background (see WidgetKeys.kt). This just sets one
 * TextView.
 *
 * Battery: no ticking. Each update schedules one exact alarm for the moment
 * the text next changes ([nextBedtimeChange]: the next minute, or 03:00 at
 * night). The alarm is `RTC`, not `RTC_WAKEUP`: with the screen off nothing
 * runs, and the pending alarm fires as soon as the phone wakes up.
 *
 * Main thread: `onReceive` runs on the main thread, so the work (reading
 * prefs, building views, scheduling) happens in `goAsync` on Dispatchers.Default.
 */
class BedtimeWidgetReceiver : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_DISABLED -> cancelAlarm(context)
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
            AppWidgetManager.ACTION_APPWIDGET_ENABLED,
            ACTION_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val pending = goAsync()
                val app = context.applicationContext
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        updateBedtimeWidgets(app)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_TICK = "dev.ayaya.dailyobsi.BEDTIME_TICK"

        /** Redraw now, e.g. after the bedtime or style changed in Settings. */
        fun refresh(context: Context) {
            context.sendBroadcast(tickIntent(context))
        }
    }
}

private fun updateBedtimeWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(ComponentName(context, BedtimeWidgetReceiver::class.java))
    if (ids.isEmpty()) {
        cancelAlarm(context)
        return
    }
    val prefs = AppPreferences(context)
    val bedtime = prefs.bedtime
    val now = LocalDateTime.now()
    val views = RemoteViews(context.packageName, prefs.bedtimeStyle.layout).apply {
        setTextViewText(R.id.bedtime_text, bedtimeCountdown(now, bedtime))
        setOnClickPendingIntent(
            R.id.bedtime_root,
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }
    manager.updateAppWidget(ids, views)
    scheduleAlarm(context, nextBedtimeChange(now, bedtime))
}

private val BedtimeStyle.layout: Int
    get() = when (this) {
        BedtimeStyle.ACCENT -> R.layout.widget_bedtime_accent
        BedtimeStyle.THIN -> R.layout.widget_bedtime_thin
        BedtimeStyle.WHITE -> R.layout.widget_bedtime_white
        BedtimeStyle.CARD -> R.layout.widget_bedtime_card
    }

private fun scheduleAlarm(context: Context, at: LocalDateTime) {
    val alarms = context.getSystemService(AlarmManager::class.java) ?: return
    val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val intent = alarmIntent(context)
    // Exact so the minute flips on time; USE_EXACT_ALARM grants it on 13+. Without it,
    // a short window (the system may stretch it, so the text can lag a bit).
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
        alarms.setExact(AlarmManager.RTC, millis, intent)
    } else {
        alarms.setWindow(AlarmManager.RTC, millis, 60_000L, intent)
    }
}

private fun cancelAlarm(context: Context) {
    context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
}

private fun tickIntent(context: Context) =
    Intent(context, BedtimeWidgetReceiver::class.java).setAction(BedtimeWidgetReceiver.ACTION_TICK)

private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    tickIntent(context),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
