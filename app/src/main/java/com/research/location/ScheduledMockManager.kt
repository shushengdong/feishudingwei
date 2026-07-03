package com.research.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Simple scheduled mock — auto-enable/disable at specified times.
 *
 * Uses AlarmManager for reliability (survives app close).
 * The alarm triggers a broadcast that toggles the mock config.
 */
object ScheduledMockManager {
    private const val PREFS = "schedule_prefs"
    private const val KEY_ENABLED = "schedule_enabled"
    private const val KEY_START_HOUR = "start_hour"
    private const val KEY_START_MIN = "start_min"
    private const val KEY_END_HOUR = "end_hour"
    private const val KEY_END_MIN = "end_min"
    private const val REQUEST_ENABLE = 1001
    private const val REQUEST_DISABLE = 1002

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, 0).getBoolean(KEY_ENABLED, false)

    fun getSchedule(ctx: Context): Pair<String, String> {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val start = String.format("%02d:%02d", p.getInt(KEY_START_HOUR, 8), p.getInt(KEY_START_MIN, 55))
        val end = String.format("%02d:%02d", p.getInt(KEY_END_HOUR, 9), p.getInt(KEY_END_MIN, 5))
        return start to end
    }

    fun setSchedule(ctx: Context, enabled: Boolean, startH: Int, startM: Int, endH: Int, endM: Int) {
        val p = ctx.getSharedPreferences(PREFS, 0).edit()
        p.putBoolean(KEY_ENABLED, enabled)
        p.putInt(KEY_START_HOUR, startH)
        p.putInt(KEY_START_MIN, startM)
        p.putInt(KEY_END_HOUR, endH)
        p.putInt(KEY_END_MIN, endM)
        p.apply()

        if (enabled) registerAlarms(ctx, startH, startM, endH, endM)
        else cancelAlarms(ctx)
    }

    private fun registerAlarms(ctx: Context, startH: Int, startM: Int, endH: Int, endM: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Enable alarm (start mock)
        val enableIntent = Intent(ctx, ScheduleReceiver::class.java).apply { action = "ENABLE_MOCK" }
        val enablePending = PendingIntent.getBroadcast(ctx, REQUEST_ENABLE, enableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val enableTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startH); set(Calendar.MINUTE, startM); set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }
        am.setRepeating(AlarmManager.RTC_WAKEUP, enableTime.timeInMillis,
            AlarmManager.INTERVAL_DAY, enablePending)

        // Disable alarm (stop mock)  
        val disableIntent = Intent(ctx, ScheduleReceiver::class.java).apply { action = "DISABLE_MOCK" }
        val disablePending = PendingIntent.getBroadcast(ctx, REQUEST_DISABLE, disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val disableTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, endH); set(Calendar.MINUTE, endM); set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }
        am.setRepeating(AlarmManager.RTC_WAKEUP, disableTime.timeInMillis,
            AlarmManager.INTERVAL_DAY, disablePending)
    }

    private fun cancelAlarms(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val enablePending = PendingIntent.getBroadcast(ctx, REQUEST_ENABLE,
            Intent(ctx, ScheduleReceiver::class.java).apply { action = "ENABLE_MOCK" },
            PendingIntent.FLAG_IMMUTABLE)
        val disablePending = PendingIntent.getBroadcast(ctx, REQUEST_DISABLE,
            Intent(ctx, ScheduleReceiver::class.java).apply { action = "DISABLE_MOCK" },
            PendingIntent.FLAG_IMMUTABLE)
        am.cancel(enablePending)
        am.cancel(disablePending)
    }

    /** Receiver that handles scheduled enable/disable */
    class ScheduleReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                "ENABLE_MOCK" -> {
                    // Re-write config from last saved location
                    val config = ConfigWriter.readConfig()
                    if (config != null && !config.enabled) {
                        ConfigWriter.enableConfig()
                        android.widget.Toast.makeText(ctx, "⏰ 定时Mock已开启", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                "DISABLE_MOCK" -> {
                    ConfigWriter.disableConfig()
                    val pkg = ConfigWriter.readConfig()?.targetPackages?.firstOrNull()
                    if (pkg != null) {
                        try {
                            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                            am.killBackgroundProcesses(pkg)
                        } catch (_: Exception) {}
                    }
                    android.widget.Toast.makeText(ctx, "⏰ 定时Mock已关闭, 恢复正常定位", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
