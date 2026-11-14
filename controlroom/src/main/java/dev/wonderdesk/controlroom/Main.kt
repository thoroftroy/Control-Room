package dev.wonderdesk.controlroom

import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.wonderdesk.shared.*

class MainActivity : WonderActivity() {
    @Composable override fun Content() = ControlRoomApp()
}

/** What Android must allow for this app to work fully; shown as a banner when off and in Settings. */
private val APP_NEEDS by lazy { listOf(
    WonderNeed("Modify system settings", "Lets the brightness, screen timeout and rotation controls change them", access = SpecialAccess.Kind.WRITE_SETTINGS),
    WonderNeed("Do Not Disturb access", "Lets the Do Not Disturb control switch it on and off", required = false, access = SpecialAccess.Kind.DND),
) }

private const val ABOUT = "Control Room puts your phone's everyday controls in one place: volume, brightness, flashlight, screen timeout, rotation and Do Not Disturb, " +
    "plus a searchable list of every Android settings page. It also sets the look of every Wonderdesk app at once."

/* ---------------------------------------- suite appearance provider ---------------------------------------- */

object SuiteLook {
    fun prefs(c: Context) = c.getSharedPreferences("suite-appearance", Context.MODE_PRIVATE)
    fun look(c: Context) = runCatching { WonderLook.valueOf(prefs(c).getString("look", "AQUA")!!) }.getOrDefault(WonderLook.AQUA)
    fun motion(c: Context) = runCatching { WonderMotion.valueOf(prefs(c).getString("motion", "FULL")!!) }.getOrDefault(WonderMotion.FULL)
    fun set(c: Context, look: WonderLook? = null, motion: WonderMotion? = null) {
        prefs(c).edit().apply { look?.let { putString("look", it.name) }; motion?.let { putString("motion", it.name) } }.apply()
        c.contentResolver.notifyChange(GlobalAppearance.URI, null)
        // Each app redraws its widgets (and the launcher its icons) straight away instead of waiting for a restart.
        runCatching {
            c.packageManager.getInstalledPackages(0).map { it.packageName }.filter { it.startsWith("dev.wonderdesk.") || it.startsWith("com.blissplayer") }
                .forEach { pkg -> c.sendBroadcast(android.content.Intent(WONDER_APPEARANCE_CHANGED).setPackage(pkg)) }
        }
    }
}

/** Read-only: every Wonderdesk app asks this for the suite-wide look. Holds no personal data. */
class AppearanceProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = context!!
        return MatrixCursor(arrayOf("look", "motion")).apply { addRow(arrayOf(SuiteLook.look(c).name, SuiteLook.motion(c).name)) }
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.wonderdesk.appearance"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/* ------------------------------------------------------ app ----------------------------------------------- */

@Composable
fun ControlRoomApp() {
    var tab by rememberSaveable { mutableStateOf("controls") }
    WonderApp(needs = APP_NEEDS, 
        appName = "Control Room", about = ABOUT,
        tabs = listOf(WonderTab("controls", "Controls", WonderIcons.Tune), WonderTab("settings", "Settings", WonderIcons.Settings), WonderTab("suite", "Wonderdesk", WonderIcons.Dashboard)),
        selectedTab = tab, onTab = { tab = it },
        title = when (tab) { "settings" -> "All settings"; "suite" -> "Wonderdesk"; else -> "Control Room" },
    ) {
        when (tab) {
            "settings" -> SettingsTab()
            "suite" -> SuiteTab()
            else -> ControlsTab()
        }
    }
}

/** Re-reads system state whenever we come back from a settings screen. */
@Composable
private fun resumeTick(): Int {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    return tick
}

@Composable
private fun ControlsTab() {
    val context = LocalContext.current
    val p = wonderPalette()
    val toast = LocalToast.current
    val tick = resumeTick()
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val nm = remember { context.getSystemService(NotificationManager::class.java) }
    val canWrite = remember(tick) { Settings.System.canWrite(context) }
    val dndAccess = remember(tick) { nm.isNotificationPolicyAccessGranted }
    var torch by remember { mutableStateOf(false) }
    val camera = remember { context.getSystemService(CameraManager::class.java) }
    val torchId = remember { runCatching { camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull() }
    DisposableEffect(Unit) {
        val cb = object : CameraManager.TorchCallback() { override fun onTorchModeChanged(id: String, enabled: Boolean) { if (id == torchId) torch = enabled } }
        camera.registerTorchCallback(cb, null); onDispose { camera.unregisterTorchCallback(cb) }
    }
    var ringer by remember(tick) { mutableIntStateOf(audio.ringerMode) }
    var dnd by remember(tick) { mutableIntStateOf(nm.currentInterruptionFilter) }
    fun needWrite() { toast("Allow Control Room to change system settings"); context.launchSafely(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))) {} }
    fun needDnd() { toast("Allow Control Room to manage Do Not Disturb"); context.launchSafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) {} }
    Page {
        // Big toggle tiles
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Wi-Fi", WonderIcons.Wifi, Color(0xFF1E7FE8), false, Modifier.weight(1f)) { context.launchSafely(Intent(Settings.Panel.ACTION_WIFI)) {} }
            Tile("Bluetooth", WonderIcons.Bluetooth, Color(0xFF3B5BDB), false, Modifier.weight(1f)) { context.launchSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) {} }
            Tile("Internet", WonderIcons.Public, Color(0xFF16A085), false, Modifier.weight(1f)) { context.launchSafely(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) {} }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(if (torch) "Light on" else "Flashlight", WonderIcons.Lightbulb, Color(0xFFF2A516), torch, Modifier.weight(1f), enabled = torchId != null) {
                torchId?.let { runCatching { camera.setTorchMode(it, !torch); context.getSharedPreferences("controls-widget", 0).edit().putBoolean("torch", !torch).apply(); refreshWidgets(context, ControlsWidget::class.java) }.onFailure { toast("The camera is busy") } }
            }
            Tile(if (dnd > NotificationManager.INTERRUPTION_FILTER_ALL) "Do Not Disturb on" else "Do Not Disturb", WonderIcons.DoNotDisturbOn, Color(0xFF6C5CE7),
                dnd > NotificationManager.INTERRUPTION_FILTER_ALL, Modifier.weight(1f)) {
                if (!dndAccess) needDnd() else {
                    val next = if (dnd > NotificationManager.INTERRUPTION_FILTER_ALL) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    nm.setInterruptionFilter(next); dnd = next
                }
            }
            val rot = remember(tick) { mutableStateOf(Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1) }
            Tile(if (rot.value) "Auto-rotate" else "Rotation locked", WonderIcons.RotateRight, Color(0xFF2E9E44), rot.value, Modifier.weight(1f)) {
                if (!canWrite) needWrite() else { Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (rot.value) 0 else 1); rot.value = !rot.value }
            }
        }
        Panel(title = "Sound") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Segmented(listOf("Ring", "Vibrate", "Silent"), when (ringer) { AudioManager.RINGER_MODE_NORMAL -> 0; AudioManager.RINGER_MODE_VIBRATE -> 1; else -> 2 }) { i ->
                    val mode = listOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT)[i]
                    if (mode == AudioManager.RINGER_MODE_SILENT && !dndAccess) needDnd() else runCatching { audio.ringerMode = mode; ringer = mode }.onFailure { needDnd() }
                }
                listOf(
                    Triple("Media", AudioManager.STREAM_MUSIC, WonderIcons.MusicNote), Triple("Ringtone", AudioManager.STREAM_RING, WonderIcons.Phone),
                    Triple("Notifications", AudioManager.STREAM_NOTIFICATION, WonderIcons.Notifications), Triple("Alarms", AudioManager.STREAM_ALARM, WonderIcons.Alarm),
                    Triple("Calls", AudioManager.STREAM_VOICE_CALL, WonderIcons.Phone),
                ).forEach { (label, stream, icon) -> VolumeSlider(label, stream, icon, audio, tick) { needDnd() } }
            }
        }
        Panel(title = "Display") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var auto by remember(tick) { mutableStateOf(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1) }
                var bright by remember(tick) { mutableFloatStateOf(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(WonderIcons.Brightness6, Color(0xFFF2A516), 28.dp); Spacer(Modifier.width(10.dp))
                    Text("Brightness", Modifier.weight(1f))
                    Text("Auto", color = p.secondaryText); Spacer(Modifier.width(6.dp))
                    Switch(auto, { on -> if (!canWrite) needWrite() else { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) 1 else 0); auto = on } },
                        colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
                }
                Slider(bright, { v -> bright = v; if (canWrite) { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0); auto = false
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (v * 255).toInt().coerceIn(1, 255)) } },
                    onValueChangeFinished = { if (!canWrite) needWrite() }, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
                val timeouts = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000)
                val cur = remember(tick) { mutableIntStateOf(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)) }
                Text("Screen turns off after", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    timeouts.forEach { t ->
                        Chip(if (t < 60_000) "${t / 1000}s" else "${t / 60_000} min", cur.intValue == t, {
                            if (!canWrite) needWrite() else { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, t); cur.intValue = t }
                        })
                    }
                }
                var awake by remember(tick) { mutableIntStateOf(StayAwake.mode(context)) }
                Text("Stay awake", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 1, 2).forEach { m -> Chip(StayAwake.label(m), awake == m, { if (!canWrite) needWrite() else if (StayAwake.set(context, m)) { awake = m; cur.intValue = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000) } }) }
                }
                Text("Keeps the screen on, then puts your own timeout back. Also a Quick Settings tile: edit your tiles and add “Stay awake”.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            }
            PanelDivider(0.dp)
            ListRow("Dark theme, font size and more", icon = WonderIcons.DarkMode, tint = Color(0xFF34495E), chevron = true, onClick = { context.launchSafely(Intent(Settings.ACTION_DISPLAY_SETTINGS)) {} })
        }
        if (!canWrite || !dndAccess) Panel(title = "Permissions", footer = "Android asks you once before an app may change these. Control Room never changes anything on its own.") {
            if (!canWrite) ListRow("Allow changing system settings", subtitle = "For brightness, timeout and rotation", icon = WonderIcons.Security, tint = p.warning, chevron = true, onClick = { needWrite() })
            if (!canWrite && !dndAccess) PanelDivider()
            if (!dndAccess) ListRow("Allow Do Not Disturb access", subtitle = "For Do Not Disturb and silent mode", icon = WonderIcons.DoNotDisturbOn, tint = p.warning, chevron = true, onClick = { needDnd() })
        }
    }
}


@Composable
private fun VolumeSlider(label: String, stream: Int, icon: ImageVector, audio: AudioManager, tick: Int, onDenied: () -> Unit) {
    val p = wonderPalette()
    val max = remember { audio.getStreamMaxVolume(stream).coerceAtLeast(1) }
    val min = remember { audio.getStreamMinVolume(stream) }
    var v by remember(tick) { mutableFloatStateOf(audio.getStreamVolume(stream).toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = p.secondaryText, modifier = Modifier.size(22.dp))
        Text(label, Modifier.width(110.dp).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(v, { n -> v = n; runCatching { audio.setStreamVolume(stream, n.toInt(), 0) }.onFailure { onDenied() } }, Modifier.weight(1f),
            valueRange = min.toFloat()..max.toFloat(), steps = (max - min - 1).coerceAtLeast(0), colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
    }
}

@Composable
private fun Tile(label: String, icon: ImageVector, tint: Color, on: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = wonderPalette()
    val shape = RoundedCornerShape(p.panelRadius + 4.dp)
    Column(modifier.height(96.dp).clip(shape)
        .background(if (on) Brush.verticalGradient(listOf(tint.lighten(.25f), tint, tint.darken(.1f))) else Brush.verticalGradient(listOf(p.panel, p.panel)))
        .border(1.dp, if (on) tint else p.panelBorder, shape).clickable(enabled = enabled, onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) Color.White.copy(alpha = .25f) else tint.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) Color.White else tint, modifier = Modifier.size(20.dp))
        }
        Text(label, color = if (on) Color.White else if (enabled) p.text else p.secondaryText, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/* -------------------------------------------------- settings ------------------------------------------------ */

data class SettingLink(val title: String, val keywords: String, val icon: ImageVector, val tint: Color, val action: String, val section: String, val packageScoped: Boolean = false)

val SETTINGS = listOf(
    SettingLink("Wi-Fi", "wifi wireless network internet hotspot", WonderIcons.Wifi, Color(0xFF1E7FE8), Settings.ACTION_WIFI_SETTINGS, "Connections"),
    SettingLink("Bluetooth", "bluetooth headphones pair devices", WonderIcons.Bluetooth, Color(0xFF3B5BDB), Settings.ACTION_BLUETOOTH_SETTINGS, "Connections"),
    SettingLink("Mobile network", "cellular data sim roaming carrier", WonderIcons.Speed, Color(0xFF16A085), Settings.ACTION_NETWORK_OPERATOR_SETTINGS, "Connections"),
    SettingLink("Data usage", "data saver usage limit", WonderIcons.QueryStats, Color(0xFF16A085), Settings.ACTION_DATA_USAGE_SETTINGS, "Connections"),
    SettingLink("Airplane mode", "flight airplane", WonderIcons.Public, Color(0xFFE67E22), Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Connections"),
    SettingLink("Hotspot and tethering", "hotspot tether share internet", WonderIcons.Hub, Color(0xFF16A085), "android.settings.TETHER_SETTINGS", "Connections"),
    SettingLink("VPN", "vpn private network", WonderIcons.Shield, Color(0xFF34495E), Settings.ACTION_VPN_SETTINGS, "Connections"),
    SettingLink("NFC and payments", "nfc tap pay contactless", WonderIcons.Nfc, Color(0xFF8E44AD), Settings.ACTION_NFC_SETTINGS, "Connections"),
    SettingLink("Cast", "cast screen mirror chromecast tv", WonderIcons.Cast, Color(0xFF1E7FE8), Settings.ACTION_CAST_SETTINGS, "Connections"),
    SettingLink("Display", "brightness dark theme font size screen", WonderIcons.Brightness6, Color(0xFFF2A516), Settings.ACTION_DISPLAY_SETTINGS, "Display and sound"),
    SettingLink("Night light", "night light blue light warm", WonderIcons.NightsStay, Color(0xFFE67E22), "android.settings.NIGHT_DISPLAY_SETTINGS", "Display and sound"),
    SettingLink("Wallpaper", "wallpaper background home lock", WonderIcons.Wallpaper, Color(0xFF8E44AD), Intent.ACTION_SET_WALLPAPER, "Display and sound"),
    SettingLink("Sound and vibration", "sound volume ringtone vibrate", WonderIcons.VolumeUp, Color(0xFFE5483C), Settings.ACTION_SOUND_SETTINGS, "Display and sound"),
    SettingLink("Do Not Disturb", "dnd do not disturb quiet focus", WonderIcons.DoNotDisturbOn, Color(0xFF6C5CE7), "android.settings.ZEN_MODE_SETTINGS", "Display and sound"),
    SettingLink("Notifications", "notifications alerts", WonderIcons.Notifications, Color(0xFFE5483C), "android.settings.NOTIFICATION_SETTINGS", "Apps"),
    SettingLink("Apps", "apps applications uninstall storage permissions", WonderIcons.Apps, Color(0xFF1E7FE8), Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, "Apps"),
    SettingLink("Default apps", "default browser home launcher sms phone", WonderIcons.Home, Color(0xFF2E9E44), Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "Apps"),
    SettingLink("Permission manager", "permissions camera microphone location", WonderIcons.Security, Color(0xFF34495E), "android.intent.action.MANAGE_PERMISSIONS", "Apps"),
    SettingLink("Special app access", "all files usage access overlay battery", WonderIcons.Key, Color(0xFF7A7F87), "android.settings.SPECIAL_APP_ACCESS_SETTINGS", "Apps"),
    SettingLink("Battery", "battery saver usage power", WonderIcons.BatteryFull, Color(0xFF2E9E44), Intent.ACTION_POWER_USAGE_SUMMARY, "Device"),
    SettingLink("Battery saver", "battery saver power save", WonderIcons.BatteryChargingFull, Color(0xFFF2A516), Settings.ACTION_BATTERY_SAVER_SETTINGS, "Device"),
    SettingLink("Storage", "storage space free up files", WonderIcons.Storage, Color(0xFF7A7F87), Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "Device"),
    SettingLink("Location", "location gps", WonderIcons.LocationOn, Color(0xFF2E9E44), Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Privacy and security"),
    SettingLink("Security", "screen lock fingerprint face pin", WonderIcons.Lock, Color(0xFF34495E), Settings.ACTION_SECURITY_SETTINGS, "Privacy and security"),
    SettingLink("Fingerprint", "fingerprint biometrics", WonderIcons.Lock, Color(0xFFE5483C), "android.settings.BIOMETRIC_ENROLL", "Privacy and security"),
    SettingLink("Privacy", "privacy permissions dashboard", WonderIcons.Shield, Color(0xFF16A085), Settings.ACTION_PRIVACY_SETTINGS, "Privacy and security"),
    SettingLink("Passwords and accounts", "accounts google sync autofill", WonderIcons.Person, Color(0xFF1E7FE8), Settings.ACTION_SYNC_SETTINGS, "Privacy and security"),
    SettingLink("Autofill service", "autofill passwords vault", WonderIcons.Password, Color(0xFF8E44AD), Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, "Privacy and security", packageScoped = true),
    SettingLink("Accessibility", "accessibility talkback magnify captions", WonderIcons.Accessibility, Color(0xFF1E7FE8), Settings.ACTION_ACCESSIBILITY_SETTINGS, "System"),
    SettingLink("Languages and input", "language keyboard input", WonderIcons.Translate, Color(0xFF16A085), Settings.ACTION_LOCALE_SETTINGS, "System"),
    SettingLink("Keyboard", "keyboard gboard input method", WonderIcons.Code, Color(0xFF7A7F87), Settings.ACTION_INPUT_METHOD_SETTINGS, "System"),
    SettingLink("Date and time", "date time zone clock", WonderIcons.Schedule, Color(0xFF1E7FE8), Settings.ACTION_DATE_SETTINGS, "System"),
    SettingLink("Backup", "backup restore cloud", WonderIcons.Backup, Color(0xFF2E9E44), "android.settings.BACKUP_AND_RESET_SETTINGS", "System"),
    SettingLink("System update", "update android version security patch", WonderIcons.SystemUpdate, Color(0xFF2E9E44), "android.settings.SYSTEM_UPDATE_SETTINGS", "System"),
    SettingLink("Developer options", "developer usb debugging animations", WonderIcons.DeveloperBoard, Color(0xFF34495E), Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "System"),
    SettingLink("About phone", "about phone model build number", WonderIcons.PhoneAndroid, Color(0xFF7A7F87), Settings.ACTION_DEVICE_INFO_SETTINGS, "System"),
    SettingLink("All Android settings", "settings everything", WonderIcons.Settings, Color(0xFF7A7F87), Settings.ACTION_SETTINGS, "System"),
)

private fun Context.openSetting(s: SettingLink, report: (String) -> Unit) {
    val i = Intent(s.action).apply { if (s.packageScoped) data = Uri.parse("package:$packageName") }
    try { startActivity(i) } catch (_: Exception) {
        // Some phones don't have every page; fall back to the closest general one.
        launchSafely(Intent(Settings.ACTION_SETTINGS), report)
    }
}

@Composable
private fun SettingsTab() {
    val context = LocalContext.current
    val toast = LocalToast.current
    var q by rememberSaveable { mutableStateOf("") }
    val shown = SETTINGS.filter { q.isBlank() || it.title.contains(q, true) || it.keywords.contains(q.lowercase()) }
    Column(Modifier.fillMaxSize()) {
        SearchField(q, { q = it }, "Search settings", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), state = rememberTabTopList(), contentPadding = PaddingValues(16.dp)) {
            shown.groupBy { it.section }.forEach { (section, list) ->
                item(key = "section:$section") { SectionLabel(section, Modifier.padding(start = 6.dp, top = 8.dp, bottom = 6.dp)) }
                list.forEachIndexed { i, s ->
                    item(key = "setting:$section:${s.title}:$i") { ListRow(s.title, Modifier.panelItem(i, list.size), icon = s.icon, tint = s.tint, chevron = true, onClick = { context.openSetting(s, toast) }) }
                }
            }
            if (shown.isEmpty()) item { EmptyState("No settings match", "Try another word, or open all Android settings.", icon = WonderIcons.Search,
                action = { WButton("Open Android settings", { context.launchSafely(Intent(Settings.ACTION_SETTINGS), toast) }) }) }
        }
    }
}

/* -------------------------------------------------- wonderdesk ---------------------------------------------- */

private val SUITE = listOf(
    "Abacus" to "dev.wonderdesk.abacus", "Beacon" to "dev.wonderdesk.beacon", "Bliss Player" to "com.blissplayer.music.debug", "Chronicle" to "dev.wonderdesk.chronicle",
    "Cinema" to "dev.wonderdesk.cinema", "Launcher" to "dev.wonderdesk.launcher", "Memories" to "dev.wonderdesk.memories", "Packrat" to "dev.wonderdesk.packrat",
    "Pages" to "dev.wonderdesk.pages", "Pulse" to "dev.wonderdesk.pulse", "Safekeeper" to "dev.wonderdesk.safekeeper", "Scribble" to "dev.wonderdesk.scribble",
    "Timepiece" to "dev.wonderdesk.timepiece", "Vault" to "dev.wonderdesk.vault", "Wayfinder" to "dev.wonderdesk.wayfinder", "Wonderdesk Apphub" to "dev.wonderdesk.apphub",
)

@Composable
private fun SuiteTab() {
    val context = LocalContext.current
    val p = wonderPalette()
    val toast = LocalToast.current
    val tick = resumeTick()
    var look by remember { mutableStateOf(SuiteLook.look(context)) }
    var motion by remember { mutableStateOf(SuiteLook.motion(context)) }
    val installed = remember(tick) { SUITE.filter { (_, pkg) -> runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess } }
    Page {
        Panel(title = "Look for every Wonderdesk app", footer = "Apps follow this unless you give one its own look in that app's Settings. Changes apply when an app is next opened or resumed.") {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WonderLook.entries.forEach { option ->
                    val pal = paletteFor(option)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { look = option; SuiteLook.set(context, look = option); toast("${option.label} set for all apps") }.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().aspectRatio(.75f).clip(RoundedCornerShape(8.dp)).background(pal.window)
                            .border(if (option == look) 3.dp else 1.dp, if (option == look) p.accent else p.panelBorder, RoundedCornerShape(8.dp))) {
                            Box(Modifier.fillMaxWidth().height(16.dp).background(Brush.verticalGradient(pal.toolbar)))
                            Column(Modifier.padding(top = 22.dp, start = 6.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.fillMaxWidth().height(12.dp).background(pal.panel, RoundedCornerShape(3.dp)))
                                Box(Modifier.fillMaxWidth(.6f).height(12.dp).background(pal.accent, RoundedCornerShape(6.dp)))
                                Box(Modifier.fillMaxWidth().height(12.dp).background(pal.panel, RoundedCornerShape(3.dp)))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(option.label, style = MaterialTheme.typography.labelMedium, color = if (option == look) p.accent else p.text, textAlign = TextAlign.Center)
                    }
                }
            }
            PanelDivider(0.dp)
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Motion", style = MaterialTheme.typography.titleSmall)
                Segmented(WonderMotion.entries.map { it.label }, WonderMotion.entries.indexOf(motion)) { motion = WonderMotion.entries[it]; SuiteLook.set(context, motion = motion) }
            }
        }
        Panel {
            ListRow("Wonderdesk Apphub", subtitle = "Install and update Wonderdesk apps", leading = { AppIcon("dev.wonderdesk.apphub") }, chevron = true, onClick = {
                context.packageManager.getLaunchIntentForPackage("dev.wonderdesk.apphub")?.let { context.launchSafely(it, toast) }
                    ?: context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thoroftroy/App-Hub/releases")), toast)
            })
            PanelDivider()
            ListRow("Home screen app", subtitle = "Choose Wonderdesk Launcher or another home app", icon = WonderIcons.Home, tint = Color(0xFF2E9E44), chevron = true, onClick = {
                context.launchSafely(Intent(Settings.ACTION_HOME_SETTINGS), toast)
            })
            PanelDivider()
            ListRow("Default apps", subtitle = "Pick Wonderdesk apps for browser links, files, photos and more", icon = WonderIcons.Apps, tint = Color(0xFF1E7FE8), chevron = true, onClick = {
                context.launchSafely(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), toast)
            })
        }
        RetirePanel()
        CrashPanel()
        Panel(title = "Installed Wonderdesk apps (${installed.size})") {
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(8.dp), userScrollEnabled = false) {
                items(installed) { (name, pkg) ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.launchSafely(it, toast) } }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIcon(pkg, 48.dp)
                        Text(name, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp = 32.dp) {
    val context = LocalContext.current
    val bmp = rememberSuiteArt(pkg, 144)
    if (bmp != null) Image(bmp, null, Modifier.size(size)) else Box(Modifier.size(size))
}
