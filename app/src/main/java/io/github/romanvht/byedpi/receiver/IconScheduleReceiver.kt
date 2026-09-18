package io.github.romanvht.byedpi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.romanvht.byedpi.utility.LauncherIcons

/** Смена даты/времени, полуночный будильник, обновление приложения — пересчитать иконку. */
class IconScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LauncherIcons.update(context)
    }

    companion object {
        const val ACTION_CHECK = "io.github.romanvht.byedpi.CHECK_ICON"
    }
}
