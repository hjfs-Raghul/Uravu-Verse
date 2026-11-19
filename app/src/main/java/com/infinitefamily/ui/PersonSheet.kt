@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.infinitefamily.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.infinitefamily.domain.*
import java.time.LocalDate

sealed class SheetAction {
    data class Wizard(val kind: RelKind?, val existingFirst: Boolean = false) : SheetAction()
    object Edit : SheetAction()
    object Deceased : SheetAction()
    object Living : SheetAction()
    object Delete : SheetAction()
    object Reorder : SheetAction()
    object Explore : SheetAction()
    object ShowPath : SheetAction()
    object SetRoot : SheetAction()
    data class EditMarriage(val id: String) : SheetAction()
    data class GoTo(val personId: String) : SheetAction()
}

@Composable
fun PersonSheet(vm: FamilyViewModel, personId: String, onAction: (SheetAction) -> Unit, onDismiss: () -> Unit) {
    val data by vm.data.collectAsState()
    val engine by vm.engine.collectAsState()
    val p = data.persons[personId] ?: return
    val gens = rememberGenerations(data)
    val today = remember { LocalDate.now() }
    val rootId = data.rootId
    val rootName = data.root?.name ?: "the reference person"
    val idx = data.index
    fun nm(id: String) = data.persons[id]?.name ?: "?"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = Pencil.Paper) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonAvatar(p, vm.repo.photos, StickerCategory.choose(p, today, gens[p.id] ?: 0), 76.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(p.name, style = MaterialTheme.typography.titleLarge)
                    Muted(listOfNotNull(p.gender.name.lowercase().replaceFirstChar { it.uppercase() }, Ages.ageText(p, today), if (p.deceased) "Deceased" else null).joinToString(" · "))
                    if (p.location.isNotBlank()) Muted(p.location)
                }
            }

            SectionTitle("Actions")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PButton("Add marriage", { onAction(SheetAction.Wizard(RelKind.SPOUSE)) })
                PButton("Add child", { onAction(SheetAction.Wizard(RelKind.CHILD)) })
                PButton("Add sibling", { onAction(SheetAction.Wizard(RelKind.SIBLING)) })
                PButton("Add parent", { onAction(SheetAction.Wizard(RelKind.PARENT)) })
                PButton("Link existing person", { onAction(SheetAction.Wizard(null, existingFirst = true)) })
                PButton("Edit profile", { onAction(SheetAction.Edit) })
                PButton(if (p.deceased) "Mark living" else "Mark deceased", { onAction(if (p.deceased) SheetAction.Living else SheetAction.Deceased) })
                PButton("Relationship details", { onAction(SheetAction.Explore) })
                if (rootId != null && rootId != p.id) PButton("Show path on canvas", { onAction(SheetAction.ShowPath) })
                if (p.id != rootId) PButton("Make reference person", { onAction(SheetAction.SetRoot) })
                PButton("Delete person", { onAction(SheetAction.Delete) })
            }

            SectionTitle("Relationship to $rootName")
            if (rootId == null || rootId == p.id) Text("This is the reference person; every relationship is calculated from here.")
            else {
                RelationBlock(engine.relation(rootId, p.id), rootName, p.name)
                Spacer(Modifier.height(14.dp))
                val back = engine.relation(p.id, rootId)
                if (back.connected) RelationBlock(back, p.name, rootName, heading = "How ${p.name} addresses $rootName")
            }

            SectionTitle("Profile")
            @Composable fun row(l: String, v: String?) { if (!v.isNullOrBlank()) Row(Modifier.padding(vertical = 2.dp)) { Text(l, Modifier.width(110.dp), color = Pencil.Grey); Text(v) } }
            row("Full name", p.name)
            row("Gender", p.gender.name.lowercase().replaceFirstChar { it.uppercase() })
            row("Date of birth", p.dob)
            row("Age", Ages.ageText(p, today))
            if (p.dob == null && p.relativeAge != RelativeAge.UNKNOWN) row("Compared with $rootName", when (p.relativeAge) { RelativeAge.OLDER -> "Older"; RelativeAge.YOUNGER -> "Younger"; else -> "About the same age" })
            if (p.deceased) row("Died", p.deathDate ?: "Yes")
            row("Occupation", p.occupation)
            row("Location", p.location)
            row("Notes", p.notes)

            SectionTitle("Marriage history")
            val ms = idx.marriages(p.id)
            if (ms.isEmpty()) Muted("No marriages recorded.")
            ms.forEach { m ->
                val o = m.other(p.id)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onAction(SheetAction.GoTo(o)) }) {
                        Text(nm(o), style = MaterialTheme.typography.titleSmall)
                        Muted(listOfNotNull(statusLabel(m.status), m.startDate?.let { "from $it" }, m.endDate?.let { "until $it" }).joinToString(" · "))
                    }
                    TextButton({ onAction(SheetAction.EditMarriage(m.id)) }) { Text("Edit") }
                }
            }

            SectionTitle("Parents, siblings and children")
            val parentLinks = idx.parentLinks(p.id)
            Text("Parents", style = MaterialTheme.typography.labelLarge)
            if (parentLinks.isEmpty()) Muted("None recorded.")
            parentLinks.forEach { l -> Text("${nm(l.parentId)}  ·  ${lineLabel(l.type)}", Modifier.clickable { onAction(SheetAction.GoTo(l.parentId)) }.padding(vertical = 3.dp)) }
            val sibs = FamilyEditor.orderedSiblings(data, p.id).filter { it.id != p.id }
            Text("Brothers and sisters (eldest first)", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge)
            if (sibs.isEmpty()) Muted("None recorded.")
            sibs.forEach { s ->
                val shared = idx.sharedParents(p.id, s.id)
                val tag = when {
                    (idx.parentLinks(p.id) + idx.parentLinks(s.id)).any { it.parentId in shared && it.type == LinkType.ADOPTIVE } -> "adoptive"
                    shared.size >= 2 || (idx.realParentIds(p.id).size == 1 && idx.realParentIds(s.id).size == 1) -> "full"
                    else -> "half"
                }
                Text("${s.name}  ·  $tag", Modifier.clickable { onAction(SheetAction.GoTo(s.id)) }.padding(vertical = 3.dp))
            }
            if (sibs.isNotEmpty()) TextButton({ onAction(SheetAction.Reorder) }) { Text("Reorder by dragging") }
            val kids = idx.childLinks(p.id)
            Text("Children", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge)
            if (kids.isEmpty()) Muted("None recorded.")
            kids.forEach { l -> Text("${nm(l.childId)}  ·  ${lineLabel(l.type)}", Modifier.clickable { onAction(SheetAction.GoTo(l.childId)) }.padding(vertical = 3.dp)) }
            val scene = remember(data) { TreeScene.build(data) }
            val stepKids = scene.stepLinks.filter { it.parentId == p.id && idx.parentLinks(it.childId).none { l -> l.parentId == p.id } }
            val stepParents = scene.stepLinks.filter { it.childId == p.id }
            if (stepKids.isNotEmpty()) { Text("Step-children (through marriage)", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge); stepKids.forEach { Text(nm(it.childId), Modifier.clickable { onAction(SheetAction.GoTo(it.childId)) }.padding(vertical = 3.dp)) } }
            if (stepParents.isNotEmpty()) { Text("Step-parents", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge); stepParents.forEach { Text(nm(it.parentId), Modifier.clickable { onAction(SheetAction.GoTo(it.parentId)) }.padding(vertical = 3.dp)) } }
        }
    }
}
