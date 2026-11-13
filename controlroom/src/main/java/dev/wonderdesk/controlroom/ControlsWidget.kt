package dev.wonderdesk.controlroom

import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import android.widget.RemoteViews
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.wonderdesk.shared.*

/**
 * Quick controls on the home screen: up to four of flashlight, Do Not Disturb, auto-rotate, brightness, screen timeout,
 * ring mode and Wi-Fi. A control that needs a permission Control Room doesn't have opens Control Room to ask for it.
 */
class ControlsWidget : AppWidgetProvider() {
    companion object {
        const val TAP = "dev.wonderdesk.controlroom.WIDGET_TAP"
        /** key, name, icon. */
        val CONTROLS = listOf(Triple("torch", "Flashlight", R.drawable.wd_ic_torch), Triple("dnd", "Do Not Disturb", R.drawable.wd_ic_dnd), Triple("rotate", "Auto-rotate", R.drawable.wd_ic_rotate),
            Triple("bright", "Brightness", R.drawable.wd_ic_bright), Triple("timeout", "Screen timeout", R.drawable.wd_ic_timer), Triple("ring", "Ring mode", R.drawable.wd_ic_ring), Triple("wifi", "Wi-Fi", R.drawable.wd_ic_wifi))
        val DEFAULT = listOf("torch", "dnd", "rotate", "bright")
        private val TILES = listOf(Triple(R.id.w_t0, R.id.w_i0, R.id.w_l0), Triple(R.id.w_t1, R.id.w_i1, R.id.w_l1), Triple(R.id.w_t2, R.id.w_i2, R.id.w_l2), Triple(R.id.w_t3, R.id.w_i3, R.id.w_l3))
        private const val TIMEOUTS = "30000,60000,120000,300000,600000"

        private fun state(c: Context): android.content.SharedPreferences = c.getSharedPreferences("controls-widget", Context.MODE_PRIVATE)

        /** Whether the control is on, and the short label under its icon. */
        private fun status(c: Context, key: String): Pair<Boolean, String> = runCatching {
            val cr = c.contentResolver
            when (key) {
                "torch" -> state(c).getBoolean("torch", false).let { it to if (it) "On" else "Light" }
                "dnd" -> (c.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL).let { it to if (it) "Quiet" else "Alerts" }
                "rotate" -> (Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1).let { it to if (it) "Rotate" else "Locked" }
                "bright" -> if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1) true to "Auto"
                            else false to "${Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255}%"
                "timeout" -> false to (Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60_000) / 1000).let { if (it >= 60) "${it / 60} min" else "$it s" }
                "ring" -> when (c.getSystemService(AudioManager::class.java).ringerMode) { AudioManager.RINGER_MODE_NORMAL -> true to "Ring"; AudioManager.RINGER_MODE_VIBRATE -> false to "Vibrate"; else -> false to "Silent" }
                else -> false to "Wi-Fi"
            }
        }.getOrDefault(false to "")

        fun build(c: Context, id: Int): RemoteViews {
            val s = WidgetPrefs(c, id)
            val look = runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)
            val chosen = s.str("controls", DEFAULT.joinToString(",")).split(',').filter { k -> CONTROLS.any { it.first == k } }.ifEmpty { DEFAULT }.take(4)
            fun make(layout: Int, count: Int) = RemoteViews(c.packageName, layout).apply {
                setInt(R.id.w_root, "setBackgroundResource", when (look) { WidgetLook.GLASS -> R.drawable.wd_widget_bg; WidgetLook.DARK -> R.drawable.wd_widget_bg_dark; WidgetLook.CLEAR -> R.drawable.wd_widget_bg_clear })
                TILES.take(count).forEachIndexed { i, (tile, icon, label) ->
                    val key = chosen.getOrNull(i)
                    if (key == null) { setViewVisibility(tile, android.view.View.GONE); return@forEachIndexed }
                    setViewVisibility(tile, android.view.View.VISIBLE)
                    val (on, text) = status(c, key)
                    setImageViewResource(icon, CONTROLS.first { it.first == key }.third)
                    setInt(tile, "setBackgroundResource", if (on) R.drawable.wd_btn_gel else R.drawable.wd_key_op)
                    setInt(icon, "setColorFilter", if (on) 0xFFFFFFFF.toInt() else 0xFF173A66.toInt())
                    setTextViewText(label, text); setTextColor(label, if (on) 0xFFFFFFFF.toInt() else 0xFF173A66.toInt())
                    setOnClickPendingIntent(tile, widgetBroadcast(c, ControlsWidget::class.java, 9500 + (id % 100) * 10 + i, TAP) { it.putExtra("key", key) })
                }
            }
            if (android.os.Build.VERSION.SDK_INT < 31) return make(R.layout.widget_controls, 4)
            return RemoteViews(linkedMapOf(android.util.SizeF(30f, 30f) to make(R.layout.widget_controls_small, 1), android.util.SizeF(110f, 30f) to make(R.layout.widget_controls_wide, 2),
                android.util.SizeF(200f, 30f) to make(R.layout.widget_controls, 4)))
        }

        private fun openApp(c: Context) = runCatching { c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

        fun toggle(c: Context, key: String) {
            val cr = c.contentResolver
            val canWrite = Settings.System.canWrite(c)
            when (key) {
                "torch" -> runCatching {
                    val cm = c.getSystemService(CameraManager::class.java)
                    val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    val on = !state(c).getBoolean("torch", false); cm.setTorchMode(id, on); state(c).edit().putBoolean("torch", on).apply()
                }
                "dnd" -> { val nm = c.getSystemService(NotificationManager::class.java)
                    if (!nm.isNotificationPolicyAccessGranted) openApp(c)
                    else nm.setInterruptionFilter(if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
                "rotate" -> if (!canWrite) openApp(c) else Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1) 0 else 1)
                "bright" -> if (!canWrite) openApp(c) else {
                    // Auto → 25% → 50% → 100% → Auto
                    val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1
                    val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
                    when {
                        auto -> { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0); Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 64) }
                        cur < 100 -> Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
                        cur < 200 -> Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 255)
                        else -> Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 1)
                    }
                }
                "timeout" -> if (!canWrite) openApp(c) else {
                    val list = TIMEOUTS.split(',').map { it.toInt() }
                    val cur = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
                    Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, list.firstOrNull { it > cur } ?: list.first())
                }
                "ring" -> runCatching {
                    val am = c.getSystemService(AudioManager::class.java)
                    val nm = c.getSystemService(NotificationManager::class.java)
                    am.ringerMode = when (am.ringerMode) { AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                        AudioManager.RINGER_MODE_VIBRATE -> if (nm.isNotificationPolicyAccessGranted) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                        else -> AudioManager.RINGER_MODE_NORMAL }
                }
                "wifi" -> runCatching { c.startActivity(Intent(if (android.os.Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { runCatching { mgr.updateAppWidget(it, build(c, it)) } } }
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == TAP) { i.getStringExtra("key")?.let { toggle(c, it) }; refreshWidgets(c, ControlsWidget::class.java) }
    }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { WidgetPrefs(c, it).clear() } }
}

class ControlsWidgetSetup : WidgetSetupActivity() {
    override fun redraw() { AppWidgetManager.getInstance(this).updateAppWidget(widgetId, ControlsWidget.build(this, widgetId)) }
    @Composable override fun Content() {
        val c = LocalContext.current
        val s = remember { WidgetPrefs(c, widgetId) }
        var look by remember { mutableStateOf(runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)) }
        var chosen by remember { mutableStateOf(s.str("controls", ControlsWidget.DEFAULT.joinToString(",")).split(',').filter { it.isNotBlank() }) }
        WidgetSetup("Control Room", "Quick controls", onCancel = { finish() }, onSave = { s.put("look" to look.name, "controls" to chosen.joinToString(",")); save() }) {
            Panel(title = "Controls (up to 4)", footer = "They appear in the order you tick them. A small widget shows the first one or two.") {
                ControlsWidget.CONTROLS.forEachIndexed { i, (k, name, _) ->
                    if (i > 0) PanelDivider()
                    val on = k in chosen
                    ListRow(name, subtitle = if (on) "Button ${chosen.indexOf(k) + 1}" else null,
                        trailing = { Checkbox(on, { t -> chosen = if (t) (chosen + k).take(4) else chosen - k }, enabled = on || chosen.size < 4) },
                        onClick = { chosen = if (on) chosen - k else if (chosen.size < 4) chosen + k else chosen })
                }
            }
            WidgetLookPicker(look) { look = it }
        }
    }
}
