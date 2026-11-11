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
        if (CrashUpload.auto(context!!)) CrashUpload.sendPending(context!!)
        return uri
    }

    /** "status" tells an app whether reports go out automatically; "send" sends what's waiting; "auto" turns that on. */
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        if (!allowed()) return null
        val c = context ?: return null
        when (method) {
            "status" -> {}
            "send" -> CrashUpload.sendPending(c, pkg = arg)
            "auto" -> { CrashUpload.setAuto(c, arg != "off"); if (arg != "off") CrashUpload.sendPending(c) }
            else -> return null
        }
        return android.os.Bundle().apply { putBoolean("auto", CrashUpload.auto(c)); putInt("pending", CrashLog.all(c).count { !it.optBoolean("sent") }) }
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
    @Synchronized fun markSent(c: Context, keys: Set<String>) {
        val list = all(c).map { if (key(it) in keys) it.put("sent", true) else it }
        file(c).writeText(JSONArray(list).toString())
    }
    fun key(o: JSONObject) = o.optString("pkg") + "@" + o.optLong("time")

    fun report(c: Context, list: List<JSONObject>): String = buildString {
        append("Wonderdesk crash report (${list.size} entries)\n\n")
        list.forEach { r ->
            append("=== ${r.optString("pkg")} ${r.optString("version")} · ${r.optString("kind")} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r.optLong("time")))}\n")
            append(r.optString("device")).append('\n').append(r.optString("summary")).append("\n\n").append(r.optString("detail")).append("\n\n")
        }
    }
}

/**
 * Opt-in crash reporting. Reports go to a private ntfy.sh topic the Wonderdesk developer collects.
 * Only the app, its version, the device model, Android version and the error are sent; file paths,
 * web and content links and email addresses are removed first. Nothing is sent unless you choose to.
 */
object CrashUpload {
    private const val TOPIC = "https://ntfy.sh/wonderdesk-crash-062c8350c3af4072"
    private fun prefs(c: Context) = c.getSharedPreferences("crash-upload", Context.MODE_PRIVATE)
    fun auto(c: Context) = prefs(c).getBoolean("auto", false)
    fun setAuto(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto", on).apply()
    @Volatile private var busy = false

    /** What a report looks like once private details are taken out. */
    fun redact(s: String): String = s
        .replace(Regex("""/(storage|sdcard|mnt|data/user/\d+|data/data)/[^\s:'"),\]]+"""), "/…/[path]")
        .replace(Regex("""content://[^\s'"),\]]+"""), "content://[link]")
        .replace(Regex("""https?://[^\s'"),\]]+"""), "[web link]")
        .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""), "[email]")

    fun preview(o: JSONObject): String = buildString {
        append("${o.optString("pkg")} ${o.optString("version")} · ${o.optString("kind")}\n")
        append(o.optString("device")).append("\n\n")
        append(redact(o.optString("summary"))).append("\n\n")
        append(redact(o.optString("detail")))
    }

    /** Sends unsent reports in the background (all apps, or just [pkg]). Safe to call often. */
    fun sendPending(c: Context, pkg: String? = null, done: ((Int) -> Unit)? = null) {
        if (busy) { done?.invoke(-1); return }
        busy = true
        Thread {
            var sent = 0
            try {
                val todo = CrashLog.all(c).filter { !it.optBoolean("sent") && (pkg == null || it.optString("pkg") == pkg) }.take(20)
                val ok = HashSet<String>()
                for (o in todo) {
                    val body = preview(o).let { if (it.toByteArray().size > 3800) it.take(3700) + "\n…" else it }
                    val title = "${o.optString("pkg").removePrefix("dev.wonderdesk.")} ${o.optString("version")} ${o.optString("kind")}"
                    val r = runCatching {
                        (java.net.URL(TOPIC).openConnection() as java.net.HttpURLConnection).run {
                            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
                            setRequestProperty("Title", title.take(120)); setRequestProperty("Tags", if (o.optString("kind") == "crash") "boom" else "warning")
                            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                            outputStream.use { it.write(body.toByteArray()) }
                            val code = responseCode; disconnect(); code in 200..299
                        }
                    }.getOrDefault(false)
                    if (r) { ok += CrashLog.key(o); sent++ } else break
                }
                if (ok.isNotEmpty()) CrashLog.markSent(c, ok)
            } finally { busy = false }
            done?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post { cb(sent) } }
        }.start()
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
    val toast = LocalToast.current
    var auto by remember { mutableStateOf(CrashUpload.auto(context)) }
    var whatsSent by remember { mutableStateOf(false) }
    val unsent = list.count { !it.optBoolean("sent") }
    Panel(title = "Crash reports", footer = "When a Wonderdesk app crashes or freezes, the details are kept here. Turn on sending to pass them to the Wonderdesk developer automatically: only the app, version, phone model, Android version and the error go out, with file names, links and email addresses removed.") {
        SwitchRow("Send crash reports automatically", auto, { on -> auto = on; CrashUpload.setAuto(context, on); if (on) CrashUpload.sendPending(context) { n -> if (n > 0) { toast("Sent $n report${if (n == 1) "" else "s"}. Thank you"); list = CrashLog.all(context) } } },
            subtitle = "Helps get crashes fixed. Off until you turn it on", icon = WonderIcons.UploadFile, tint = Color(0xFF1E7FE8))
        PanelDivider()
        ListRow("What gets sent", icon = WonderIcons.Info, tint = Color(0xFF7A7F87), chevron = true, onClick = { whatsSent = true })
        PanelDivider()
        if (list.isEmpty()) ListRow("No crashes recorded", icon = WonderIcons.CheckCircle, tint = p.success)
        byApp.entries.forEachIndexed { i, (pkg, rs) ->
            if (i > 0) PanelDivider()
            val name = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            ListRow(name, subtitle = "${rs.size} report${if (rs.size == 1) "" else "s"}${if (rs.all { it.optBoolean("sent") }) " · sent" else ""} · last ${relativeTime(rs.maxOf { it.optLong("time") }).replaceFirstChar { it.lowercase() }} · ${rs.first().optString("summary").take(80)}",
                icon = WonderIcons.Error, tint = p.danger, chevron = true, onClick = { open = pkg })
        }
        if (list.isNotEmpty()) {
            PanelDivider(0.dp)
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                WButton("Share all", { context.launchSafely(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Wonderdesk crash report")
                    .putExtra(Intent.EXTRA_TEXT, CrashLog.report(context, list).take(900_000)), "Share crash report")) {} }, icon = WonderIcons.Share, kind = ButtonKind.Plain)
                WButton("Copy", { context.copyText("Crash report", CrashLog.report(context, list)) }, icon = WonderIcons.ContentCopy, kind = ButtonKind.Plain)
                if (unsent > 0) WButton("Send $unsent", { CrashUpload.sendPending(context) { n -> toast(when { n > 0 -> "Sent $n report${if (n == 1) "" else "s"}. Thank you"; n == 0 -> "Couldn't send. Check your connection"; else -> "Already sending" }); list = CrashLog.all(context) } }, icon = WonderIcons.UploadFile, kind = ButtonKind.Plain)
                Spacer(Modifier.weight(1f))
                WButton("Clear", { CrashLog.clear(context); list = emptyList() }, kind = ButtonKind.Plain)
            }
        }
    }
    if (whatsSent) WonderDialog(onDismissRequest = { whatsSent = false }, title = { Text("What gets sent") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text("Each report is the app name and version, your phone's make and model, the Android version, and the error itself. File paths, web and content links and email addresses are replaced before it leaves your phone. Nothing else about you, your files or your accounts is included.",
                style = MaterialTheme.typography.bodyMedium)
            list.firstOrNull()?.let { o ->
                Spacer(Modifier.height(10.dp)); Text("Your most recent report, exactly as it would be sent:", style = MaterialTheme.typography.labelLarge)
                Text(CrashUpload.preview(o).take(3000), Modifier.padding(top = 6.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }, confirmButton = { TextButton(onClick = { whatsSent = false }) { Text("Close") } })
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
