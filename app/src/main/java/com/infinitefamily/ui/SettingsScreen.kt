@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.infinitefamily.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.infinitefamily.domain.*
import com.infinitefamily.work.FamilyLocationWorker
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: FamilyViewModel, onOpenTerminology: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val data by vm.data.collectAsState()
    val settings by vm.settings.collectAsState()
    val restore by vm.restore.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var explain by remember { mutableStateOf(false) }
    var confirmRemoveSample by remember { mutableStateOf(false) }
    val notifyOn = settings[SettingKeys.NOTIFY] == "1"
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportBackup(uri) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.prepareRestore(uri) }

    // Permission chain: foreground location -> background location -> notifications. Only what is needed.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++; vm.setNotify(true) }
    fun askNotifOrFinish() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setNotify(true)
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++; askNotifOrFinish() }
    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        if (!ok) notice = "Location permission was not granted, so family alerts stay off."
        else if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else askNotifOrFinish()
    }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClose) { Text("Close") }
                    Text("Settings", style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider(color = Pencil.LightGrey)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    SectionTitle("Backup and restore")
                    Muted("One ZIP file with every person, relationship, marriage, sibling order, photo, drawing choice and terminology change. It restores the whole tree on another phone. Nothing leaves your device unless you move the file yourself.")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PButton("Save backup…", { exporter.launch("uravu-verse-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip") })
                        PButton("Restore…", { importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) })
                    }

                    SectionTitle("Relationship terms")
                    Muted("Tamil and Malayalam terms differ between families and regions. Correct or add your family's own terms; the calculation itself does not change.")
                    PButton("Edit terminology", onOpenTerminology, Modifier.padding(top = 8.dp))

                    SectionTitle("Family nearby alerts")
                    Muted("Optional. Tells you when you arrive in a city where relatives are listed. It compares your own city with the city names in profiles on this phone. No maps, no sharing, and nobody is tracked.")
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(notifyOn, { on -> if (on) explain = true else { vm.setNotify(false); notice = null } })
                        Spacer(Modifier.width(10.dp)); Text(if (notifyOn) "On" else "Off (default)")
                    }
                    notice?.let { Text(it, color = Pencil.Red, modifier = Modifier.padding(top = 6.dp)) }
                    if (notifyOn) {
                        key(refresh) {
                            val fg = FamilyLocationWorker.hasCoarsePermission(ctx); val bg = FamilyLocationWorker.hasBackgroundPermission(ctx)
                            Muted("Location while using the app: ${if (fg) "allowed" else "not allowed"}")
                            Muted("Location in the background: ${if (bg) "allowed" else "not allowed"}")
                            if (!bg) Text("Without 'Allow all the time' alerts can only be checked occasionally, so they may be late or missing. Open the phone's app settings > Permissions > Location to change it.", color = Pencil.Red, style = MaterialTheme.typography.bodySmall)
                        }
                        var home by remember { mutableStateOf(settings[SettingKeys.HOME_CITY].orEmpty()) }
                        OutlinedTextField(home, { home = it; vm.setHomeCity(it) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                            label = { Text("Your home city (no alerts there)") }, placeholder = { Text(data.root?.location ?: "") })
                    }
                    Muted("Limits: checked about once an hour at city level. Android can delay or skip background work to save battery, so arrival alerts are best-effort, not guaranteed. Only cities known to the built-in city list can be matched.")

                    SectionTitle("Sample family")
                    val n = SampleFamily.sampleCount(data)
                    if (n == 0) {
                        Muted("Adds a demonstration family (divorce, step-family, adoption, half-siblings) around the reference person. It can be removed again.")
                        PButton("Add sample family", { vm.loadSample() }, Modifier.padding(top = 8.dp), enabled = data.index.parentLinks(data.rootId ?: "").isEmpty())
                    } else {
                        Muted("$n sample people are in your tree.")
                        PButton("Remove sample family", { confirmRemoveSample = true }, Modifier.padding(top = 8.dp))
                    }

                    SectionTitle("About")
                    Text("Uravu Verse 1.0 – an offline family tree and relationship guide. All data stays on this device.")
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }

    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text("Turn on family alerts?") },
        text = { Text("Uravu Verse will ask for:\n• Approximate location – to know which city you are in.\n• Location 'all the time' – only so the check can run when the app is closed.\n• Notifications – to show the alert.\n\nIt never records or shares where you go. You can turn this off at any time.") },
        confirmButton = { TextButton({
            explain = false; notice = null
            if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) fgLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            else fgLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }) { Text("Continue") } },
        dismissButton = { TextButton({ explain = false }) { Text("Not now") } },
    )
    if (confirmRemoveSample) AlertDialog(
        onDismissRequest = { confirmRemoveSample = false },
        title = { Text("Remove sample family?") },
        text = { Text("${SampleFamily.sampleCount(data)} sample people and their marriages and parent links will be removed. People you added yourself are kept, but any link to a sample person is removed.") },
        confirmButton = { TextButton({ confirmRemoveSample = false; vm.removeSample() }) { Text("Remove", color = Pencil.Red) } },
        dismissButton = { TextButton({ confirmRemoveSample = false }) { Text("Cancel") } },
    )
    when (val r = restore) {
        is RestoreState.Ready -> {
            val d = r.parsed.snapshot.data
            AlertDialog(
                onDismissRequest = { vm.dismissRestore() },
                title = { Text("Restore this backup?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Backup made ${if (r.parsed.createdAt > 0) DateFormat.getDateTimeInstance().format(Date(r.parsed.createdAt)) else "(date unknown)"}:")
                        Text("• ${d.persons.size} people\n• ${d.marriages.size} marriages\n• ${d.links.size} parent-child links\n• ${r.parsed.photos.size} photos")
                        Spacer(Modifier.height(10.dp))
                        Text("This REPLACES everything currently in the app (${data.persons.size} people, ${data.marriages.size} marriages). Save a backup of the current tree first if you might need it.", color = Pencil.Red)
                        if (r.parsed.warnings.isNotEmpty()) { Spacer(Modifier.height(8.dp)); r.parsed.warnings.take(5).forEach { Muted("• $it") } }
                    }
                },
                confirmButton = { TextButton({ vm.confirmRestore() }) { Text("Replace my data", color = Pencil.Red) } },
                dismissButton = { TextButton({ vm.dismissRestore() }) { Text("Cancel") } },
            )
        }
        is RestoreState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissRestore() }, title = { Text("This backup cannot be used") },
            text = { Text(r.reason + "\n\nYour current data has not been changed.") },
            confirmButton = { TextButton({ vm.dismissRestore() }) { Text("OK") } },
        )
        RestoreState.Idle -> {}
    }
}

@Composable
fun TerminologyScreen(vm: FamilyViewModel, onClose: () -> Unit) {
    val overrides by vm.overrides.collectAsState()
    val term by vm.terminology.collectAsState()
    var q by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<TermEntry?>(null) }
    val entries = remember(q) { DefaultTerminology.entries.values.filter { q.isBlank() || it.relationEn.contains(q, true) || it.code.contains(q, true) } }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClose) { Text("Close") }
                    Text("Terminology", style = MaterialTheme.typography.titleMedium)
                }
                Muted("Defaults are common usage, not universal. Tap a relationship to set your family's own words. Blank fields use the default.", Modifier.padding(horizontal = 16.dp))
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true, label = { Text("Search relationships") })
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    items(entries, key = { it.code }) { e ->
                        val r = term.resolve(e.code)
                        val custom = overrides.any { it.code == e.code }
                        Column(Modifier.fillMaxWidth().clickable { editing = e }.padding(vertical = 8.dp)) {
                            Text(e.relationEn + if (custom) "  ✎" else "", style = MaterialTheme.typography.titleSmall)
                            Muted("Tamil: ${r.ta.joinToString(" / ") { it.text }.ifBlank { "–" }}    Malayalam: ${r.ml.joinToString(" / ") { it.text }.ifBlank { "–" }}")
                        }
                        HorizontalDivider(color = Pencil.Faint)
                    }
                }
            }
        }
    }
    editing?.let { e ->
        val cur = { l: Language -> overrides.firstOrNull { it.code == e.code && it.language == l }?.text ?: "" }
        var en by remember(e) { mutableStateOf(cur(Language.EN)) }
        var ta by remember(e) { mutableStateOf(cur(Language.TA)) }
        var ml by remember(e) { mutableStateOf(cur(Language.ML)) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(e.relationEn) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(en, { en = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("English term") }, placeholder = { Text(e.addressEn) })
                    OutlinedTextField(ta, { ta = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Tamil term") }, placeholder = { Text(e.ta.joinToString(" / ") { it.text }) })
                    OutlinedTextField(ml, { ml = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Malayalam term") }, placeholder = { Text(e.ml.joinToString(" / ") { it.text }) })
                    Muted("Applies when ${vm.data.value.root?.name ?: "you"} or anyone else addresses this relationship, and is included in backups.")
                }
            },
            confirmButton = { TextButton({ vm.setOverride(e.code, Language.EN, en); vm.setOverride(e.code, Language.TA, ta); vm.setOverride(e.code, Language.ML, ml); editing = null }) { Text("Save") } },
            dismissButton = { TextButton({ vm.setOverride(e.code, Language.EN, ""); vm.setOverride(e.code, Language.TA, ""); vm.setOverride(e.code, Language.ML, ""); editing = null }) { Text("Reset to default") } },
        )
    }
}
