package dev.wonderdesk.controlroom

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wonderdesk.shared.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Collects crash, freeze and native-crash reports from every Wonderdesk app. Only Wonderdesk apps
 * (and adb, for testing) may write; reports stay on the phone until you share them.
 */
class CrashProvider : ContentProvider() {
    override fun onCreate() = true

    private fun allowed(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == Process.SHELL_UID) return true
        val pkgs = context?.packageManager?.getPackagesForUid(uid).orEmpty()
        return pkgs.any { it.startsWith("dev.wonderdesk.") || it.startsWith("com.blissplayer.") }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!allowed()) return null
        val json = values?.getAsString("json")?.takeIf { it.length < 200_000 } ?: return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        // Trust the caller's real package name over what the report says.
        context?.packageManager?.getPackagesForUid(Binder.getCallingUid())?.firstOrNull()?.takeIf { Binder.getCallingUid() != Process.SHELL_UID }?.let { o.put("pkg", it) }
        CrashLog.add(context!!, o)
        return uri
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        if (!allowed()) return null
        val c = MatrixCursor(arrayOf("pkg", "kind", "time", "version", "summary", "detail"))
        CrashLog.all(context!!).forEach { r -> c.addRow(arrayOf(r.optString("pkg"), r.optString("kind"), r.optLong("time"), r.optString("version"), r.optString("summary"), r.optString("detail"))) }
        return c
    }

    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int { if (!allowed()) return 0; val n = CrashLog.all(context!!).size; CrashLog.clear(context!!); return n }
    override fun getType(uri: Uri): String? = null
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "crash-reports.json")
    @Synchronized fun all(c: Context): List<JSONObject> = runCatching { JSONArray(file(c).readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.getOrDefault(emptyList())
    @Synchronized fun add(c: Context, o: JSONObject) {
        val list = (listOf(o) + all(c)).distinctBy { it.optString("pkg") + it.optLong("time") }.take(300)
        file(c).writeText(JSONArray(list).toString())
    }
    @Synchronized fun clear(c: Context) { file(c).delete() }

    fun report(c: Context, list: List<JSONObject>): String = buildString {
        append("Wonderdesk crash report (${list.size} entries)\n\n")
        list.forEach { r ->
            append("=== ${r.optString("pkg")} ${r.optString("version")} · ${r.optString("kind")} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r.optLong("time")))}\n")
            append(r.optString("device")).append('\n').append(r.optString("summary")).append("\n\n").append(r.optString("detail")).append("\n\n")
        }
    }
}

/** Panel in Control Room's Wonderdesk tab. */
@Composable
fun CrashPanel() {
    val context = LocalContext.current
    val p = wonderPalette()
    val resumed = rememberIsResumed()
    var list by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumed) { if (resumed) list = CrashLog.all(context) }
    val byApp = list.groupBy { it.optString("pkg") }
    Panel(title = "Crash reports", footer = if (list.isEmpty()) "When a Wonderdesk app crashes or freezes, the details land here so they can be fixed." else null) {
        if (list.isEmpty()) ListRow("No crashes recorded", icon = WonderIcons.CheckCircle, tint = p.success)
        byApp.entries.forEachIndexed { i, (pkg, rs) ->
            if (i > 0) PanelDivider()
            val name = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            ListRow(name, subtitle = "${rs.size} report${if (rs.size == 1) "" else "s"} · last ${relativeTime(rs.maxOf { it.optLong("time") }).replaceFirstChar { it.lowercase() }} · ${rs.first().optString("summary").take(80)}",
                icon = WonderIcons.Error, tint = p.danger, chevron = true, onClick = { open = pkg })
        }
        if (list.isNotEmpty()) {
            PanelDivider(0.dp)
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                WButton("Share all", { context.launchSafely(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Wonderdesk crash report")
                    .putExtra(Intent.EXTRA_TEXT, CrashLog.report(context, list).take(900_000)), "Share crash report")) {} }, icon = WonderIcons.Share, kind = ButtonKind.Plain)
                WButton("Copy", { context.copyText("Crash report", CrashLog.report(context, list)) }, icon = WonderIcons.ContentCopy, kind = ButtonKind.Plain)
                Spacer(Modifier.weight(1f))
                WButton("Clear", { CrashLog.clear(context); list = emptyList() }, kind = ButtonKind.Plain)
            }
        }
    }
    open?.let { pkg ->
        val rs = byApp[pkg].orEmpty()
        WonderDialog(onDismissRequest = { open = null }, title = { Text("Crashes") }, text = {
            SelectionContainer {
                Text(CrashLog.report(context, rs), Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }, confirmButton = { TextButton(onClick = { context.copyText("Crash report", CrashLog.report(context, rs)); open = null }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { open = null }) { Text("Close") } })
    }
}
