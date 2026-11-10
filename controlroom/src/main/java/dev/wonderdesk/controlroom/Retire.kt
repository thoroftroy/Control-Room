package dev.wonderdesk.controlroom

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.wonderdesk.shared.*

/** Apps each Wonderdesk app replaces. Only apps actually installed are shown. */
val REPLACES: Map<String, List<String>> = mapOf(
    "dev.wonderdesk.wayfinder" to listOf("com.google.android.apps.nbu.files", "com.sec.android.app.myfiles", "com.mi.android.globalFileexplorer", "com.estrongs.android.pop",
        "com.alphainventor.filemanager", "com.lonelycatgames.Xplore", "com.cxinventor.file.explorer", "com.coloros.filemanager", "com.oneplus.filemanager", "com.android.filemanager"),
    "dev.wonderdesk.memories" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d", "com.android.gallery3d", "com.miui.gallery", "com.oneplus.gallery", "com.coloros.gallery3d",
        "com.simplemobiletools.gallery.pro", "com.google.android.GoogleCamera.Gallery"),
    "dev.wonderdesk.cinema" to listOf("org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro", "com.google.android.videos", "com.samsung.android.video", "com.miui.videoplayer"),
    "dev.wonderdesk.chronicle" to listOf("com.google.android.calendar", "com.samsung.android.calendar", "com.android.calendar", "com.xiaomi.calendar", "com.oneplus.calendar"),
    "dev.wonderdesk.timepiece" to listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock", "com.oneplus.deskclock", "com.coloros.alarmclock"),
    "dev.wonderdesk.scribble" to listOf("com.google.android.keep", "com.samsung.android.app.notes", "com.miui.notes", "md.obsidian", "com.simplemobiletools.notes.pro", "com.oneplus.note"),
    "dev.wonderdesk.abacus" to listOf("com.google.android.calculator", "com.sec.android.app.popupcalculator", "com.android.calculator2", "com.miui.calculator", "com.oneplus.calculator"),
    "dev.wonderdesk.pages" to listOf("com.google.android.apps.pdfviewer", "com.adobe.reader", "com.xodo.pdf.reader"),
    "dev.wonderdesk.packrat" to listOf("com.rarlab.rar", "com.winzip.android", "ru.zdevs.zarchiver"),
    "dev.wonderdesk.launcher" to listOf("com.teslacoilsw.launcher", "com.microsoft.launcher", "bitpit.launcher", "ch.deletescape.lawnchair.plah", "app.lawnchair"),
    "com.blissplayer.music.debug" to listOf("com.google.android.apps.youtube.music", "com.sec.android.app.music", "com.android.music", "com.miui.player", "com.oneplus.music"),
    "dev.wonderdesk.vault" to listOf("com.google.android.apps.authenticator2"),
)

data class Retiree(val pkg: String, val label: String, val replacedBy: String, val system: Boolean, val enabled: Boolean)

object Retire {
    fun candidates(c: Context): List<Retiree> {
        val pm = c.packageManager
        return REPLACES.flatMap { (ours, theirs) ->
            if (runCatching { pm.getPackageInfo(ours, 0) }.isFailure) return@flatMap emptyList()
            val ourName = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(ours, 0)).toString() }.getOrDefault(ours)
            theirs.mapNotNull { pkg ->
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                Retiree(pkg, pm.getApplicationLabel(ai).toString(), ourName, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, ai.enabled)
            }
        }.distinctBy { it.pkg }
    }

    /** Archiving (Android 15+) keeps the icon and data but removes the app; tapping it brings it back. */
    fun canArchive(r: Retiree) = Build.VERSION.SDK_INT >= 35 && !r.system

    /**
     * Starts the gentlest removal Android allows without root: archive, uninstall (both ask the
     * user), or for built-in apps the App info page where the user taps Disable.
     */
    fun start(c: Context, r: Retiree, report: (String) -> Unit) {
        if (canArchive(r)) {
            val ok = runCatching {
                val pi = PendingIntent.getBroadcast(c, r.pkg.hashCode(), Intent(c, ArchiveResult::class.java).setPackage(c.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                c.packageManager.packageInstaller.requestArchive(r.pkg, pi.intentSender)
            }.isSuccess
            if (ok) return
        }
        if (!r.system) c.launchSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${r.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), report)
        else c.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${r.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), report)
    }

}
/** Shows Android's confirmation when archiving needs the user's OK. */
class ArchiveResult : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Panel listing replaced apps with one button to step through all of them. */
@Composable
fun RetirePanel() {
    val context = LocalContext.current
    val p = wonderPalette()
    val toast = LocalToast.current
    val resumed = rememberIsResumed()
    var list by remember { mutableStateOf<List<Retiree>>(emptyList()) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var queue by remember { mutableStateOf<List<Retiree>>(emptyList()) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        val fresh = Retire.candidates(context)
        if (list.isEmpty()) chosen = fresh.filter { it.enabled }.map { it.pkg }.toSet()
        list = fresh
        // Coming back from the last prompt: move on to the next app in the queue.
        if (queue.isNotEmpty()) {
            val rest = queue.drop(1).filter { r -> fresh.any { it.pkg == r.pkg && it.enabled } }
            queue = rest
            rest.firstOrNull()?.let { Retire.start(context, it, toast) } ?: toast("Done. Wonderdesk apps now handle these jobs")
        }
    }
    val active = list.filter { it.enabled }
    Panel(title = "Apps Wonderdesk replaces", footer = if (active.isEmpty()) null else
        "Android asks you to confirm each one. Downloaded apps are ${if (Build.VERSION.SDK_INT >= 35) "archived (the icon stays and your data is kept, tap to bring one back)" else "uninstalled"}; built-in apps open their App info page, where you tap Disable. Nothing changes without your OK.") {
        if (list.isEmpty()) ListRow("Nothing to tidy up", subtitle = "None of the apps that Wonderdesk replaces are installed", icon = WonderIcons.CheckCircle, tint = p.success)
        list.forEachIndexed { i, r ->
            if (i > 0) PanelDivider()
            val icon = remember(r.pkg) { runCatching { context.packageManager.getApplicationIcon(r.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
            ListRow(r.label, subtitle = if (!r.enabled) "Turned off" else "Replaced by ${r.replacedBy}" + if (r.system) " · built in" else "",
                leading = { icon?.let { Image(it, null, Modifier.size(32.dp)) } },
                trailing = {
                    if (r.enabled) Checkbox(r.pkg in chosen, { on -> chosen = if (on) chosen + r.pkg else chosen - r.pkg })
                    else TextButton(onClick = { context.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${r.pkg}")), toast) }) { Text("Turn on") }
                },
                onClick = { if (r.enabled) Retire.start(context, r, toast) })
        }
        if (active.isNotEmpty()) {
            PanelDivider(0.dp)
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${chosen.count { c -> active.any { it.pkg == c } }} selected", Modifier.weight(1f), color = p.secondaryText, style = MaterialTheme.typography.bodySmall)
                WButton("Turn off selected", { confirm = true }, icon = WonderIcons.Archive, kind = ButtonKind.Primary, enabled = active.any { it.pkg in chosen })
            }
        }
    }
    if (confirm) ConfirmDialog("Turn off ${active.count { it.pkg in chosen }} apps?", "Android will show a prompt for each one. Confirm it, then come back to Control Room and the next one opens.",
        "Start", onConfirm = { queue = active.filter { it.pkg in chosen }; queue.firstOrNull()?.let { Retire.start(context, it, toast) } }, onDismiss = { confirm = false })
}
