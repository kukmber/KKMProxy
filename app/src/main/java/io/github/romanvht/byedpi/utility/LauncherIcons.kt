package io.github.romanvht.byedpi.utility

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import io.github.romanvht.byedpi.receiver.IconScheduleReceiver
import java.util.Calendar

/** Сезонная иконка приложения: переключает activity-alias'ы из манифеста. */
object LauncherIcons {
    private const val TAG = "LauncherIcons"

    enum class Icon(val alias: String) {
        REGULAR(".IconRegular"),
        NEW_YEAR(".IconNewYear"),
        PEAR(".IconPear"),
    }

    fun iconFor(date: Calendar): Icon {
        val month = date.get(Calendar.MONTH)
        val day = date.get(Calendar.DAY_OF_MONTH)
        return when {
            month == Calendar.OCTOBER && day == 12 -> Icon.PEAR
            month == Calendar.DECEMBER || month == Calendar.JANUARY -> Icon.NEW_YEAR
            else -> Icon.REGULAR
        }
    }

    /** Включает нужную иконку (если ещё не включена) и планирует проверку на следующую полночь. */
    fun update(context: Context) {
        val wanted = iconFor(Calendar.getInstance())
        val pm = context.packageManager
        // Сначала включаем нужный alias, потом выключаем остальные — чтобы значок не пропадал
        setEnabled(context, pm, wanted, true)
        Icon.values().filter { it != wanted }.forEach { setEnabled(context, pm, it, false) }
        scheduleNextCheck(context)
    }

    private fun setEnabled(context: Context, pm: PackageManager, icon: Icon, enabled: Boolean) {
        val component = ComponentName(context.packageName, context.packageName + icon.alias)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val current = pm.getComponentEnabledSetting(component)
        val effectiveEnabled = when (current) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == Icon.REGULAR
            else -> current == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (effectiveEnabled == enabled) return
        runCatching { pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP) }
            .onSuccess { Log.i(TAG, "${icon.name} -> $enabled") }
            .onFailure { Log.w(TAG, "Не удалось переключить ${icon.name}", it) }
    }

    private fun scheduleNextCheck(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 5)
            set(Calendar.MILLISECOND, 0)
        }
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, IconScheduleReceiver::class.java).setAction(IconScheduleReceiver.ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Неточного будильника достаточно: смена иконки может подождать несколько минут
        alarm.set(AlarmManager.RTC, midnight.timeInMillis, intent)
    }
}
