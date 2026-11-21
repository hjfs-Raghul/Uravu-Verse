@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.infinitefamily.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.infinitefamily.domain.*
import com.infinitefamily.ui.canvas.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class Dlg {
    data class Wizard(val anchor: String?, val kind: RelKind?, val existingFirst: Boolean = false) : Dlg()
    data class Edit(val id: String) : Dlg()
    data class Deceased(val id: String) : Dlg()
    data class Delete(val id: String) : Dlg()
    data class Reorder(val id: String) : Dlg()
    data class Marriage(val id: String) : Dlg()
    data class Explorer(val a: String?, val b: String?) : Dlg()
}

@Composable
fun MainScreen(vm: FamilyViewModel) {
    val ready by vm.ready.collectAsState()
    if (!ready) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Opening your family sheet…", style = MaterialTheme.typography.titleMedium) }; return }

    val data by vm.data.collectAsState()
    val autoLayout by vm.layout.collectAsState()
    val settings by vm.settings.collectAsState()
    val manual = remember(settings[ManualLayout.KEY]) { ManualLayout.decode(settings[ManualLayout.KEY]) }
    val layout = remember(autoLayout, manual) { ManualLayout.apply(autoLayout, manual) }
    val movedCount = remember(autoLayout, manual) { ManualLayout.count(autoLayout, manual) }
    val scene by vm.scene.collectAsState()
    val engine by vm.engine.collectAsState()
    val scope = rememberCoroutineScope()
    val controller = remember { CanvasController() }
    val photos = remember { PhotoCache(vm.repo.photos, scope) }
    val snackbar = remember { SnackbarHostState() }

    var selectedId by remember { mutableStateOf<String?>(null) }
    var sheetFor by remember { mutableStateOf<String?>(null) }
    var pulseId by remember { mutableStateOf<String?>(null) }
    var pathIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }

    // Start centred on the reference person.
    LaunchedEffect(layout, controller.size) {
        val root = data.rootId?.let { layout.positions[it] }
        if (!controller.initialised && root != null && controller.size.width > 0) {
            controller.jumpTo(Offset(root.x, root.y), 0.8f); controller.initialised = true
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            launch {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar(m.text, if (m.undoable) "Undo" else null, withDismissAction = !m.undoable, duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) vm.undo()
            }
        }
    }

    fun goTo(id: String) {
        val n = layout.positions[id] ?: return
        selectedId = id; pulseId = id
        scope.launch { controller.focus(Offset(n.x, n.y), maxOf(controller.scale, 0.8f)); delay(3500); if (pulseId == id) pulseId = null }
    }
    fun showPath(ids: List<String>) {
        pathIds = ids
        val ns = ids.mapNotNull { layout.positions[it] }
        if (ns.isNotEmpty()) scope.launch {
            controller.fit(floatArrayOf(ns.minOf { it.x } - 100f, ns.minOf { it.y } - 120f, ns.maxOf { it.x } + 100f, ns.maxOf { it.y } + 120f))
        }
    }

    BackHandler(enabled = showSearch || pathIds.isNotEmpty()) { if (showSearch) showSearch = false else pathIds = emptyList() }

    var legendOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = legendOpen) { legendOpen = false }
    Box(Modifier.fillMaxSize().background(Pencil.Paper)) {
        TreeCanvas(
            controller, data, layout, scene, photos, selectedId, pulseId, pathIds,
            onTap = { id ->
                if (id != null) { selectedId = id; sheetFor = id }
                else { selectedId = null; pulseId = null; pathIds = emptyList() }
            },
            onMove = { id, x, y -> vm.moveNode(id, x, y) },
        )

        // Top controls
        Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton("Search") { showSearch = true }
            PillButton("Relations") { dialog = Dlg.Explorer(data.rootId, selectedId) }
            PillButton("Settings") { showSettings = true }
        }

        // Bottom controls
        if (legendOpen) {
            // Invisible scrim: a tap anywhere outside the legend closes it.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { legendOpen = false } })
        }
        Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(10.dp)) { Legend(legendOpen, { legendOpen = !legendOpen }) }
        Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(10.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pathIds.isNotEmpty()) PillButton("Clear path") { pathIds = emptyList() }
            if (movedCount > 0) PillButton("Reset layout") { vm.resetLayout(); vm.say("Back to the automatic layout.") }
            PillButton("Fit tree") { scope.launch { controller.fit(layout.bounds) } }
            PillButton("Go to ${data.root?.name ?: "home"}") { data.rootId?.let { goTo(it) } }
            PButton("+  Quick add", { dialog = Dlg.Wizard(selectedId, null) }, filled = true)
        }

        if (data.persons.size == 1) {
            Text("Tap a sticker to add family around it, or use Quick add.\nPress and hold a sticker, then drag it to arrange the sheet your way.\nSettings › Sample family shows a demonstration tree.",
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 130.dp, start = 24.dp, end = 24.dp), color = Pencil.Grey,
                style = MaterialTheme.typography.bodyMedium)
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 4.dp))

        if (showSearch) {
            Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(10.dp).fillMaxWidth(), shadowElevation = 2.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Pencil.Grey)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Search people", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton({ showSearch = false }) { Text("Close") }
                    }
                    PersonPicker(vm, vm.repo.photos, onPick = { showSearch = false; goTo(it.id) })
                }
            }
        }
    }

    sheetFor?.let { id ->
        PersonSheet(vm, id, onDismiss = { sheetFor = null }, onAction = { a ->
            when (a) {
                is SheetAction.Wizard -> { sheetFor = null; dialog = Dlg.Wizard(id, a.kind, a.existingFirst) }
                SheetAction.Edit -> { sheetFor = null; dialog = Dlg.Edit(id) }
                SheetAction.Deceased -> { sheetFor = null; dialog = Dlg.Deceased(id) }
                SheetAction.Living -> vm.edit("Marked living", undoable = true) { FamilyEditor.setDeceased(it, id, false) }
                SheetAction.Delete -> { sheetFor = null; dialog = Dlg.Delete(id) }
                SheetAction.Reorder -> { sheetFor = null; dialog = Dlg.Reorder(id) }
                SheetAction.Explore -> { sheetFor = null; dialog = Dlg.Explorer(data.rootId, id) }
                SheetAction.ShowPath -> {
                    sheetFor = null
                    val root = data.rootId
                    val prim = root?.let { engine.relation(it, id).primary }
                    if (root != null && prim != null) showPath(listOf(root) + prim.steps.map { it.to }) else vm.say("No path to the reference person yet.")
                }
                SheetAction.SetRoot -> vm.edit("Reference person changed") { FamilyEditor.setRoot(it, id) }
                is SheetAction.EditMarriage -> { sheetFor = null; dialog = Dlg.Marriage(a.id) }
                is SheetAction.GoTo -> { sheetFor = a.personId; goTo(a.personId) }
            }
        })
    }

    when (val d = dialog) {
        is Dlg.Wizard -> RelationWizard(vm, d.anchor, d.kind, onClose = { dialog = null }, existingFirst = d.existingFirst)
        is Dlg.Edit -> PersonEditorDialog(vm, d.id) { dialog = null }
        is Dlg.Deceased -> DeceasedDialog(vm, d.id) { dialog = null }
        is Dlg.Delete -> DeleteDialog(vm, d.id) { dialog = null; if (selectedId == d.id) selectedId = null }
        is Dlg.Reorder -> ReorderDialog(vm, d.id) { dialog = null }
        is Dlg.Marriage -> MarriageEditDialog(vm, d.id) { dialog = null }
        is Dlg.Explorer -> RelationExplorer(vm, d.a, d.b, onShowPath = { showPath(it) }, onClose = { dialog = null })
        null -> {}
    }
    if (showSettings) SettingsScreen(vm, onOpenTerminology = { showTerms = true }, onClose = { showSettings = false })
    if (showTerms) TerminologyScreen(vm) { showTerms = false }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(1.3.dp, Pencil.Graphite),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Pencil.Paper.copy(alpha = 0.95f), contentColor = Pencil.Graphite)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
