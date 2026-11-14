package dev.wonderdesk.controlroom

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Stay awake: keeps the screen on (for an hour, or until you switch it off) by lengthening the screen timeout,
 * then puts your own timeout back. Tap the Quick Settings tile to go Off → 1 hour → Until off → Off.
 */
object StayAwake {
    private fun prefs(c: Context) = c.getSharedPreferences("stay-awake", Context.MODE_PRIVATE)
    /** 0 = off, 1 = for an hour, 2 = until switched off. */
    fun mode(c: Context) = prefs(c).getInt("mode", 0)

    fun set(c: Context, mode: Int): Boolean {
        if (!Settings.System.canWrite(c)) return false
        val p = prefs(c); val cr = c.contentResolver
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(restorePending(c))
        if (mode == 0) {
            val saved = p.getInt("saved", -1)
            if (saved > 0) Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, saved)
            p.edit().putInt("mode", 0).remove("saved").apply()
        } else {
            if (p.getInt("mode", 0) == 0) p.edit().putInt("saved", Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)).apply()
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE)
            p.edit().putInt("mode", mode).apply()
            if (mode == 1) am.set(AlarmManager.RTC, System.currentTimeMillis() + 3_600_000L, restorePending(c))
        }
        runCatching { TileService.requestListeningState(c, android.content.ComponentName(c, StayAwakeTile::class.java)) }
        return true
    }

    fun label(mode: Int) = when (mode) { 1 -> "For an hour"; 2 -> "Until turned off"; else -> "Off" }
    private fun restorePending(c: Context) = PendingIntent.getBroadcast(c, 0x5A, Intent(c, StayAwakeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class StayAwakeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { StayAwake.set(c, 0) }
}

class StayAwakeTile : TileService() {
    override fun onStartListening() { refresh() }
    override fun onClick() {
        if (!Settings.System.canWrite(this)) {
            // Needs "Modify system settings": open Control Room, whose banner takes you to the switch.
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
            return
        }
        StayAwake.set(this, (StayAwake.mode(this) + 1) % 3); refresh()
    }
    private fun refresh() {
        val t = qsTile ?: return
        val m = StayAwake.mode(this)
        t.state = if (m == 0) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        t.label = "Stay awake"
        if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = StayAwake.label(m)
        t.updateTile()
    }
}
