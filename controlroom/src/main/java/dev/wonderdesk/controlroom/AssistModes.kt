package dev.wonderdesk.controlroom

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*

/** Which apps put their controls on the Wonderdesk Launcher's floating button, as well as in their own notification. */
object AssistModes {
    /** key, name, what it puts on the button. */
    val APPS = listOf(
        Triple("memories", "Memories", "Record screen, Start clip, Save clip and Stop"),
        Triple("blissplayer", "Bliss Player", "Previous, play or pause, next, love"),
        Triple("wayfinder", "Wayfinder", "Stop sharing, and cancel for copy and move jobs"),
        Triple("ripple", "Ripple", "Disconnect from your computer"),
    )
    private fun prefs(c: Context) = c.getSharedPreferences("assist-modes", Context.MODE_PRIVATE)
    fun button(c: Context, key: String) = prefs(c).getBoolean("btn_$key", true)
    fun setButton(c: Context, key: String, on: Boolean) { prefs(c).edit().putBoolean("btn_$key", on).apply() }
    /** Notifications are never turned off here: these are controls for something running, and people who don't use the button need them. */
    fun notification(@Suppress("UNUSED_PARAMETER") c: Context, @Suppress("UNUSED_PARAMETER") key: String) = true
}

/** Read by the Wonderdesk apps (key, notification, button). Read only; changes are made in this app. */
class AssistProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = context!!
        return MatrixCursor(arrayOf("key", "notification", "button")).apply { AssistModes.APPS.forEach { (k, _, _) -> addRow(arrayOf(k, if (AssistModes.notification(c, k)) 1 else 0, if (AssistModes.button(c, k)) 1 else 0)) } }
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.wonderdesk.assist"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** Settings panel for the above. */
@Composable
fun AssistModesPanel() {
    val context = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    Panel(title = "Controls on the floating button", footer = "Turn on the floating button in Wonderdesk Launcher's settings. These apps then also show their controls at the top of its menu. Their notifications always stay, so everything works the same for anyone who doesn't use the button.") {
        AssistModes.APPS.forEachIndexed { i, (key, name, what) ->
            if (i > 0) PanelDivider()
            val on = remember(rev) { AssistModes.button(context, key) }
            SwitchRow(name, on, { AssistModes.setButton(context, key, it); rev++ }, subtitle = what, icon = WonderIcons.TouchApp, tint = Color(0xFF1E7FE8))
        }
    }
}
