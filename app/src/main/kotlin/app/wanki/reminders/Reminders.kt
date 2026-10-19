package app.wanki.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.wanki.MainActivity
import app.wanki.R
import app.wanki.graph
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The daily "time to review" notification. An inexact alarm (15-minute window, no special
 * permission) fires at the chosen time; the receiver posts the notification only when cards or
 * text pieces are due and schedules the next day. Rescheduled on boot and whenever the app starts.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    private const val NOTIFICATION_ID = 1
    private const val WINDOW_MS = 15 * 60_000L

    fun schedule(context: Context) {
        val settings = context.graph.settings
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context)
        alarms.cancel(pi)
        if (!settings.reminderEnabled) return
        alarms.setWindow(AlarmManager.RTC_WAKEUP, nextTrigger(settings.reminderMinutes), WINDOW_MS, pi)
    }

    /** Next moment at [minutes] after midnight, local time, strictly in the future. */
    fun nextTrigger(minutes: Int, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
        val time = LocalTime.of(minutes / 60 % 24, minutes % 60)
        val today = LocalDate.now(zone).atTime(time).atZone(zone).toInstant().toEpochMilli()
        return if (today > now) today else LocalDate.now(zone).plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal suspend fun fire(context: Context) {
        val tree = context.graph.repository.deckTree()
        val due = tree.roots.mapNotNull { tree.row(it.id) }.sumOf { it.dueCount }
        val fresh = tree.roots.mapNotNull { tree.row(it.id) }.sumOf { it.newCount }
        val texts = context.graph.texts.today().pieces
        if (due + fresh + texts == 0 || !canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildList {
            if (due > 0) add("к повторению: $due")
            if (fresh > 0) add("новых: $fresh")
            if (texts > 0) add("текстов: $texts")
        }.joinToString(", ")
        val title = when {
            texts == 0 -> "Пора повторить карточки"
            due + fresh == 0 -> "Пора повторить тексты"
            else -> "Пора повторить"
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.replaceFirstChar { it.uppercase() })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Напоминания о повторении", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    const val ACTION = "app.wanki.REMIND"
}

/** The alarm (and boot / app update, which clear alarms). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val graph = context.graph
        graph.appScope.launch {
            try {
                if (intent.action == Reminders.ACTION) Reminders.fire(context)
                Reminders.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}
