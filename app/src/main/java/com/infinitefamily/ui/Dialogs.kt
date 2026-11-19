@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.infinitefamily.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.infinitefamily.domain.*
import kotlin.math.roundToInt

@Composable
fun DeceasedDialog(vm: FamilyViewModel, personId: String, onClose: () -> Unit) {
    val data by vm.data.collectAsState()
    val p = data.persons[personId] ?: return
    var date by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Mark ${p.name} as deceased") },
        text = {
            Column {
                Text("Their profile, marriages and children stay exactly where they are. Only the sticker is faded.")
                Spacer(Modifier.height(10.dp))
                DateField("Date of death (optional)", date, { date = it })
            }
        },
        confirmButton = { TextButton({ vm.edit("${p.name} marked as deceased", undoable = true) { FamilyEditor.setDeceased(it, personId, true, date.ifBlank { null }) }; onClose() },
            enabled = date.isBlank() || PartialDate.parse(date) != null) { Text("Mark deceased") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

@Composable
fun DeleteDialog(vm: FamilyViewModel, personId: String, onClose: () -> Unit) {
    val data by vm.data.collectAsState()
    val p = data.persons[personId] ?: return
    val impact = remember(data, personId) { FamilyEditor.deleteImpact(data, personId) }
    fun nm(id: String) = data.persons[id]?.name ?: "?"
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Delete ${p.name}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (impact.blocked != null) { Text(impact.blocked, color = Pencil.Red); return@Column }
                Text("Only this profile is removed. Nobody else is deleted. You can undo right after.")
                SectionTitle("What changes")
                if (impact.removedMarriages.isEmpty() && impact.removedLinks.isEmpty()) Text("This person has no relationships.")
                impact.removedMarriages.forEach { Text("• Marriage ${nm(it.personAId)} & ${nm(it.personBId)} (${statusLabel(it.status)}) is removed") }
                impact.removedLinks.forEach {
                    Text(if (it.parentId == personId) "• ${nm(it.childId)} loses ${p.name} as ${lineLabel(it.type).lowercase()} parent" else "• ${nm(it.parentId)} is no longer ${p.name}'s ${lineLabel(it.type).lowercase()} parent")
                }
                if (impact.childrenLosingParent.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Children remain connected to their other parent where one is recorded.", style = MaterialTheme.typography.bodySmall)
                }
                if (impact.becomeDisconnected.isNotEmpty()) {
                    SectionTitle("Tree structure")
                    Text("${impact.becomeDisconnected.size} people would no longer be connected to ${data.root?.name ?: "the reference person"} and appear as a separate group:", color = Pencil.Red)
                    Text(impact.becomeDisconnected.take(8).joinToString { it.name } + if (impact.becomeDisconnected.size > 8) " …" else "")
                }
            }
        },
        confirmButton = { TextButton({ vm.edit("Deleted ${p.name}", undoable = true) { FamilyEditor.deletePerson(it, personId) }; onClose() }, enabled = impact.blocked == null) { Text("Delete", color = Pencil.Red) } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

@Composable
fun MarriageEditDialog(vm: FamilyViewModel, marriageId: String, onClose: () -> Unit) {
    val data by vm.data.collectAsState()
    val m = data.marriages[marriageId] ?: run { onClose(); return }
    var status by remember { mutableStateOf(m.status) }
    var start by remember { mutableStateOf(m.startDate ?: "") }
    var end by remember { mutableStateOf(m.endDate ?: "") }
    var confirmRemove by remember { mutableStateOf(false) }
    fun nm(id: String) = data.persons[id]?.name ?: "?"
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${nm(m.personAId)} & ${nm(m.personBId)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MarriageStatus.values().forEach { s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(status == s, { status = s }); Text(statusLabel(s)) }
                }
                Muted("Changing the status never changes children or parent links.")
                Spacer(Modifier.height(8.dp))
                DateField("Start (optional)", start, { start = it })
                if (status != MarriageStatus.CURRENT) { Spacer(Modifier.height(6.dp)); DateField("End (optional)", end, { end = it }) }
                Spacer(Modifier.height(8.dp))
                TextButton({ if (confirmRemove) { vm.edit("Marriage record removed", undoable = true) { FamilyEditor.removeMarriage(it, marriageId) }; onClose() } else confirmRemove = true }) {
                    Text(if (confirmRemove) "Tap again to remove this marriage record" else "Remove marriage record", color = Pencil.Red)
                }
            }
        },
        confirmButton = {
            TextButton({
                vm.edit("Marriage updated", undoable = true) {
                    FamilyEditor.updateMarriage(it, m.copy(status = status, startDate = start.ifBlank { null }, endDate = if (status == MarriageStatus.CURRENT) null else end.ifBlank { null }))
                }; onClose()
            }, enabled = (start.isBlank() || PartialDate.parse(start) != null) && (end.isBlank() || PartialDate.parse(end) != null)) { Text("Save") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

/** Drag-and-drop ordering of a person's brothers and sisters (full and half), elder first. */
@Composable
fun ReorderDialog(vm: FamilyViewModel, personId: String, onClose: () -> Unit) {
    val data by vm.data.collectAsState()
    val start = remember(personId) { FamilyEditor.orderedSiblings(vm.data.value, personId).map { it.id } }
    var order by remember { mutableStateOf(start) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    val rowDp = 60.dp
    val rowPx = with(LocalDensity.current) { rowDp.toPx() }
    val mine = data.index.realParentIds(personId)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Birth order") },
        text = {
            Column {
                Muted("Drag the handle to put them in birth order, eldest at the top. Half-brothers and sisters are included so their order is kept.")
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(rowDp * order.size)) {
                    order.forEachIndexed { i, id ->
                        val p = data.persons[id] ?: return@forEachIndexed
                        val isDrag = dragging == id
                        val half = id != personId && data.index.realParentIds(id) != mine
                        Row(
                            Modifier.fillMaxWidth().height(rowDp).offset(y = rowDp * i).zIndex(if (isDrag) 1f else 0f)
                                .graphicsLayer { translationY = if (isDrag) dy else 0f }
                                .background(if (isDrag) Pencil.Faint else Pencil.Paper).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("≡", Modifier.padding(end = 12.dp).pointerInput(id) {
                                detectDragGestures(
                                    onDragStart = { dragging = id; dy = 0f },
                                    onDragEnd = { dragging = null; dy = 0f },
                                    onDragCancel = { dragging = null; dy = 0f },
                                ) { change, drag ->
                                    change.consume()
                                    dy += drag.y
                                    val cur = order.indexOf(id)
                                    val target = (cur + (dy / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
                                    if (target != cur) {
                                        order = order.toMutableList().apply { add(target, removeAt(cur)) }
                                        dy -= (target - cur) * rowPx
                                    }
                                }
                            }, style = MaterialTheme.typography.titleLarge)
                            Column(Modifier.weight(1f)) {
                                Text(p.name + if (id == personId) "  (selected)" else "", style = MaterialTheme.typography.titleSmall)
                                Muted(listOfNotNull(p.dob?.let { "b. $it" }, if (half) "half-sibling" else null).joinToString(" · "))
                            }
                            Text("${i + 1}", color = Pencil.Grey)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ vm.edit("Birth order saved", undoable = true) { FamilyEditor.reorderSiblings(it, order) }; onClose() }) { Text("Save order") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}
