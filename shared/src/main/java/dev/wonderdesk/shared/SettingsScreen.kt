package dev.wonderdesk.shared

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

fun settingsMatch(label: String, query: String): Boolean = label.contains(query.trim(), ignoreCase = true)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val op = rememberOperation()
    val setLook = LocalLookPreference.current
    var query by rememberSaveable { mutableStateOf("") }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    val links = remember { listOf(
        "Default home launcher" to Settings.ACTION_HOME_SETTINGS,
        "Display and wallpaper" to Settings.ACTION_DISPLAY_SETTINGS,
        "Sound and volume" to Settings.ACTION_SOUND_SETTINGS,
        "Wi-Fi" to Settings.ACTION_WIFI_SETTINGS,
        "Bluetooth and devices" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "Notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        "Accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "Security (Android)" to Settings.ACTION_SECURITY_SETTINGS,
        "Storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "Battery saver" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "Date and time" to Settings.ACTION_DATE_SETTINGS,
        "Keyboard and input methods" to Settings.ACTION_INPUT_METHOD_SETTINGS,
        "Location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "Installed applications" to Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
        "All Android settings" to Settings.ACTION_SETTINGS
    ) }
    Text("These controls open official Android settings. Wonderdesk does not replace the lock screen, notification shade or protected system controls.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { appearanceOpen = !appearanceOpen }) { Text(if (appearanceOpen) "Hide appearance options" else "Appearance (this app)…") }
    if (appearanceOpen) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(WonderLook.AQUA, WonderLook.BLISS).forEach { look ->
            TextButton(onClick = { setLook(look) }, modifier = Modifier.weight(1f)) { Text(look.label) }
        }
    }
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(WonderLook.DARK, WonderLook.SUPER).forEach { look ->
            TextButton(onClick = { setLook(look) }, modifier = Modifier.weight(1f)) { Text(look.label) }
        }
      }
    }
    OutlinedTextField(query, { query = it.take(256) }, Modifier.fillMaxWidth(), label = { Text("Find a setting") }, singleLine = true,
        trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
    OperationStatus(op)
    val filtered = remember(links, query) { links.filter { settingsMatch(it.first, query) } }
    if (query.isNotBlank() && filtered.isEmpty()) Text("No matching settings", style = MaterialTheme.typography.bodySmall)
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(filtered) { (label, action) ->
            AquaSecondaryButton(onClick = { context.launchSafely(Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName), op::notice) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
        }
    }
}
