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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.infinitefamily.domain.*

enum class RelKind(val label: String) { SPOUSE("Spouse / partner"), CHILD("Child"), SIBLING("Brother or sister"), PARENT("Parent") }

private enum class WStep { ANCHOR, KIND, WHO, DETAILS }

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit, sub: String? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column { Text(label, style = MaterialTheme.typography.bodyLarge); if (sub != null) Muted(sub) }
    }
}

private fun demote(d: FamilyData, ids: List<String>, status: MarriageStatus?): EditResult {
    var r: EditResult = EditResult.Ok(d)
    if (status == null) return r
    for (id in ids) for (m in r.let { (it as EditResult.Ok).data }.index.marriages(id).filter { it.status == MarriageStatus.CURRENT }) {
        r = r.then { FamilyEditor.setMarriageStatus(it, m.id, status) }
    }
    return r
}

/**
 * Guided creation of relationships. The user never draws lines: they pick who and how, and the
 * relationship records (and therefore the tree) are created inside one transaction.
 * [anchorId]/[initialKind] may be null for Quick Add, which first asks for the existing person to start from.
 */
@Composable
fun RelationWizard(vm: FamilyViewModel, anchorId: String?, initialKind: RelKind?, onClose: () -> Unit, existingFirst: Boolean = false) {
    val data by vm.data.collectAsState()
    var anchor by remember { mutableStateOf(anchorId) }
    var kind by remember { mutableStateOf(initialKind) }
    var useExisting by remember { mutableStateOf(existingFirst) }
    var existing by remember { mutableStateOf<Person?>(null) }
    val draft = remember { PersonDraft() }
    var forceDup by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // details
    var spouseStatus by remember { mutableStateOf(MarriageStatus.CURRENT) }
    var spouseStart by remember { mutableStateOf("") }
    var spouseEnd by remember { mutableStateOf("") }
    var demoteTo by remember { mutableStateOf<MarriageStatus?>(MarriageStatus.PREVIOUS) }
    var childType by remember { mutableStateOf(LinkType.BIOLOGICAL) }
    var otherParent by remember { mutableStateOf<String?>(null) }
    var pickOther by remember { mutableStateOf(false) }
    var childPlacement by remember { mutableStateOf(0) } // 0 youngest, 1 eldest, 2 unknown
    var sibShape by remember { mutableStateOf("FULL") }
    var sibOther by remember { mutableStateOf<String?>(null) }
    var sibPlacement by remember { mutableStateOf(1) } // 0 elder than anchor, 1 younger, 2 unknown
    var fatherName by remember { mutableStateOf("") }
    var motherName by remember { mutableStateOf("") }
    var parentType by remember { mutableStateOf(LinkType.BIOLOGICAL) }
    var parentMarriage by remember { mutableStateOf<MarriageStatus?>(MarriageStatus.CURRENT) }

    val steps = remember { listOfNotNull(if (anchorId == null) WStep.ANCHOR else null, if (initialKind == null) WStep.KIND else null, WStep.WHO, WStep.DETAILS) }
    var idx by remember { mutableIntStateOf(0) }
    val step = steps[idx]
    val a = anchor?.let { data.persons[it] }
    val aName = a?.name ?: "…"
    val rootName = data.root?.name ?: "the reference person"

    val dupes = remember(draft.name, data) { if (!useExisting && draft.name.isNotBlank()) FamilyEditor.possibleDuplicates(data, draft.name) else emptyList() }
    val whoOk = if (useExisting) existing != null else draft.valid && (dupes.isEmpty() || forceDup)
    val canNext = when (step) { WStep.ANCHOR -> a != null; WStep.KIND -> kind != null; WStep.WHO -> whoOk; WStep.DETAILS -> true }

    fun save() {
        val aId = anchor ?: return
        val k = kind ?: return
        val newPerson = if (!useExisting) draft.toPerson(null) else null
        val whoId = newPerson?.id ?: existing?.id ?: return
        vm.edit("Added ${newPerson?.name ?: existing?.name}", undoable = true, onDone = { r ->
            if (r is EditResult.Ok) onClose() else if (r is EditResult.Fail) error = r.message
        }) { d0 ->
            var r: EditResult = EditResult.Ok(d0)
            if (newPerson != null) r = r.then { FamilyEditor.addPerson(it, newPerson, force = true) }
            when (k) {
                RelKind.SPOUSE -> {
                    r = r.then { demote(it, listOf(aId, whoId), if (spouseStatus == MarriageStatus.CURRENT) demoteTo else null) }
                    r = r.then { FamilyEditor.addMarriage(it, aId, whoId, spouseStatus, spouseStart, if (spouseStatus == MarriageStatus.CURRENT) null else spouseEnd) }
                }
                RelKind.CHILD -> {
                    val other = if (childType == LinkType.STEP) null else otherParent
                    val pl = when (childPlacement) { 1 -> Placement.Eldest; 2 -> Placement.Unknown; else -> Placement.Youngest }
                    r = r.then { FamilyEditor.addChild(it, aId, whoId, other, childType, pl) }
                }
                RelKind.PARENT -> {
                    r = r.then { dd ->
                        val before = dd.index.realParentIds(aId)
                        var x: EditResult = FamilyEditor.addParentLink(dd, whoId, aId, parentType)
                        val pm = parentMarriage
                        if (parentType != LinkType.STEP && pm != null && before.size == 1) {
                            val other = before.first()
                            x = x.then { y -> if (y.marriages.values.any { it.involves(other) && it.involves(whoId) }) EditResult.Ok(y) else FamilyEditor.addMarriage(y, other, whoId, pm) }
                        }
                        x
                    }
                }
                RelKind.SIBLING -> {
                    val hasParents = d0.index.realParentIds(aId).isNotEmpty()
                    val pl = when (sibPlacement) { 0 -> Placement.ElderThan(aId); 2 -> Placement.Unknown; else -> Placement.YoungerThan(aId) }
                    if (!hasParents) {
                        val now = System.currentTimeMillis()
                        val father = Person(FamilyEditor.newId(), fatherName.ifBlank { "Father of $aName" }, Gender.MALE, createdAt = now)
                        val mother = Person(FamilyEditor.newId(), motherName.ifBlank { "Mother of $aName" }, Gender.FEMALE, createdAt = now + 1)
                        r = r.then { FamilyEditor.addParents(it, aId, father, mother) }
                        r = r.then { FamilyEditor.addSibling(it, aId, whoId, SiblingShape.FULL, pl) }
                    } else if (sibShape.startsWith("HALF:")) {
                        val via = sibShape.removePrefix("HALF:")
                        r = r.then { FamilyEditor.addChild(it, via, whoId, sibOther, LinkType.BIOLOGICAL, pl) }
                    } else {
                        r = r.then { FamilyEditor.addSibling(it, aId, whoId, if (sibShape == "ADOPTIVE") SiblingShape.ADOPTIVE else SiblingShape.FULL, pl) }
                    }
                }
            }
            r
        }
    }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClose) { Text("Cancel") }
                    Column(Modifier.weight(1f)) {
                        Text(if (kind != null && a != null) "Add ${kind!!.label.lowercase()}" else "Add a relative", style = MaterialTheme.typography.titleMedium)
                        if (a != null) Muted("for $aName")
                    }
                }
                HorizontalDivider(color = Pencil.LightGrey)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    error?.let { Text(it, color = Pencil.Red, modifier = Modifier.padding(bottom = 8.dp)) }
                    when (step) {
                        WStep.ANCHOR -> {
                            Text("Who is the existing person you are starting from?", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            if (a != null) Text("Selected: ${a.name}", color = Pencil.Red)
                            PersonPicker(vm, vm.repo.photos, onPick = { anchor = it.id })
                        }
                        WStep.KIND -> {
                            Text("${aName}'s …", style = MaterialTheme.typography.titleSmall)
                            RelKind.values().forEach { Choice(it.label, kind == it, { kind = it }) }
                            Muted("Example: to add Appaji's sister's son, start from the aunt and choose Child.")
                        }
                        WStep.WHO -> {
                            Text("Who is the ${kind?.label?.lowercase() ?: "person"}?", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!useExisting, { useExisting = false }, { Text("New person") })
                                FilterChip(useExisting, { useExisting = true }, { Text("Existing person") })
                            }
                            Spacer(Modifier.height(8.dp))
                            if (useExisting) {
                                existing?.let { Text("Selected: ${it.name}", color = Pencil.Red, modifier = Modifier.padding(bottom = 6.dp)) }
                                PersonPicker(vm, vm.repo.photos, onPick = { existing = it }, exclude = setOfNotNull(anchor))
                            } else {
                                PersonFormFields(draft, vm, minimal = true, rootName = rootName)
                                if (dupes.isNotEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text("Someone with this name already exists:", color = Pencil.Red)
                                    dupes.take(4).forEach { p ->
                                        TextButton({ existing = p; useExisting = true }) { Text("Use ${p.name}${if (p.location.isNotBlank()) " (${p.location})" else ""} instead") }
                                    }
                                    TextButton({ forceDup = true }) { Text(if (forceDup) "✓ Creating a new person anyway" else "This is a different person – create new") }
                                }
                            }
                        }
                        WStep.DETAILS -> Details(
                            vm, data, anchor!!, kind!!, existing?.takeIf { useExisting }, aName,
                            spouseStatus, { spouseStatus = it }, spouseStart, { spouseStart = it }, spouseEnd, { spouseEnd = it }, demoteTo, { demoteTo = it },
                            childType, { childType = it }, otherParent, { otherParent = it }, pickOther, { pickOther = it }, childPlacement, { childPlacement = it },
                            sibShape, { sibShape = it }, sibOther, { sibOther = it }, sibPlacement, { sibPlacement = it },
                            fatherName, { fatherName = it }, motherName, { motherName = it },
                            parentType, { parentType = it }, parentMarriage, { parentMarriage = it },
                            if (useExisting) existing?.name else draft.name,
                        )
                    }
                }
                HorizontalDivider(color = Pencil.LightGrey)
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (idx > 0) PButton("Back", { idx--; error = null }) else Spacer(Modifier)
                    if (step == WStep.DETAILS) PButton("Save", { save() }, filled = true)
                    else PButton("Next", { idx++ }, filled = true, enabled = canNext)
                }
            }
        }
    }
}

@Composable
private fun Details(
    vm: FamilyViewModel, data: FamilyData, anchorId: String, kind: RelKind, existing: Person?, aName: String,
    spouseStatus: MarriageStatus, setSpouseStatus: (MarriageStatus) -> Unit, start: String, setStart: (String) -> Unit, end: String, setEnd: (String) -> Unit,
    demoteTo: MarriageStatus?, setDemote: (MarriageStatus?) -> Unit,
    childType: LinkType, setChildType: (LinkType) -> Unit, otherParent: String?, setOther: (String?) -> Unit, pickOther: Boolean, setPickOther: (Boolean) -> Unit,
    childPlacement: Int, setChildPlacement: (Int) -> Unit,
    sibShape: String, setSibShape: (String) -> Unit, sibOther: String?, setSibOther: (String?) -> Unit, sibPlacement: Int, setSibPlacement: (Int) -> Unit,
    fatherName: String, setFather: (String) -> Unit, motherName: String, setMother: (String) -> Unit,
    parentType: LinkType, setParentType: (LinkType) -> Unit, parentMarriage: MarriageStatus?, setParentMarriage: (MarriageStatus?) -> Unit,
    whoName: String?,
) {
    val who = whoName?.ifBlank { "the new person" } ?: "the new person"
    fun nm(id: String) = data.persons[id]?.name ?: "?"
    when (kind) {
        RelKind.SPOUSE -> {
            Text("Marriage between $aName and $who", style = MaterialTheme.typography.titleSmall)
            MarriageStatus.values().forEach { Choice(statusLabel(it), spouseStatus == it, { setSpouseStatus(it) }) }
            DateField("Start (optional)", start, setStart)
            if (spouseStatus != MarriageStatus.CURRENT) { Spacer(Modifier.height(6.dp)); DateField("End (optional)", end, setEnd) }
            val currentOthers = (data.index.marriages(anchorId) + (existing?.let { data.index.marriages(it.id) } ?: emptyList())).filter { it.status == MarriageStatus.CURRENT }
            if (spouseStatus == MarriageStatus.CURRENT && currentOthers.isNotEmpty()) {
                SectionTitle("Existing current marriage")
                Text(currentOthers.joinToString { "${nm(it.personAId)} & ${nm(it.personBId)}" })
                Muted("What happened to it? Nothing is assumed, and children are never affected.")
                listOf(MarriageStatus.PREVIOUS to "Mark as previous marriage", MarriageStatus.DIVORCED to "Mark as divorced", MarriageStatus.WIDOWED to "Mark as widowed").forEach { (s, l) ->
                    Choice(l, demoteTo == s, { setDemote(s) })
                }
                Choice("Keep it as current", demoteTo == null, { setDemote(null) })
            }
        }
        RelKind.CHILD -> {
            Text("$who as child of $aName", style = MaterialTheme.typography.titleSmall)
            LinkType.values().forEach {
                Choice(lineLabel(it), childType == it, { setChildType(it) }, when (it) {
                    LinkType.BIOLOGICAL -> "Born to $aName"; LinkType.ADOPTIVE -> "Adopted by $aName"; LinkType.STEP -> "Child of $aName's partner (kept separate from biological parentage)"
                })
            }
            if (childType != LinkType.STEP) {
                SectionTitle("Other parent")
                val partners = data.index.marriages(anchorId).map { it.other(anchorId) }.distinct()
                Choice("None / not recorded", otherParent == null && !pickOther, { setOther(null); setPickOther(false) })
                partners.forEach { p -> Choice(nm(p), otherParent == p, { setOther(p); setPickOther(false) }, data.index.marriages(anchorId).first { it.other(anchorId) == p }.let { statusLabel(it.status) }) }
                Choice("Someone else (existing person)…", pickOther, { setPickOther(true) })
                if (pickOther) {
                    otherParent?.takeIf { it !in partners }?.let { Text("Selected: ${nm(it)}", color = Pencil.Red) }
                    PersonPicker(vm, vm.repo.photos, onPick = { setOther(it.id) }, exclude = setOf(anchorId))
                }
                if (existing == null) {
                    SectionTitle("Birth order")
                    Choice("Youngest of the children", childPlacement == 0, { setChildPlacement(0) })
                    Choice("Eldest of the children", childPlacement == 1, { setChildPlacement(1) })
                    Choice("Unknown (drag to reorder later)", childPlacement == 2, { setChildPlacement(2) })
                }
            }
        }
        RelKind.PARENT -> {
            Text("$who as parent of $aName", style = MaterialTheme.typography.titleSmall)
            LinkType.values().forEach { Choice(lineLabel(it), parentType == it, { setParentType(it) }) }
            val before = data.index.realParentIds(anchorId)
            if (parentType != LinkType.STEP && before.size == 1) {
                SectionTitle("Marriage with ${nm(before.first())}")
                Muted("$aName already has ${nm(before.first())} recorded as a parent.")
                MarriageStatus.values().forEach { Choice(statusLabel(it), parentMarriage == it, { setParentMarriage(it) }) }
                Choice("No marriage record", parentMarriage == null, { setParentMarriage(null) })
            }
        }
        RelKind.SIBLING -> {
            val parents = data.index.realParentIds(anchorId).toList()
            Text("$who as brother or sister of $aName", style = MaterialTheme.typography.titleSmall)
            if (parents.isEmpty()) {
                Muted("Brothers and sisters are derived from shared parents, and $aName has none recorded yet. These parents will be created together (rename or complete them later).")
                OutlinedTextField(fatherName, setFather, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Father's name") }, placeholder = { Text("Father of $aName") })
                OutlinedTextField(motherName, setMother, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Mother's name") }, placeholder = { Text("Mother of $aName") })
            } else {
                SectionTitle("Which parents are shared?")
                Choice("Same parents (full brother/sister)", sibShape == "FULL", { setSibShape("FULL") }, parents.joinToString(" & ") { nm(it) })
                Choice("Adopted by the same parents", sibShape == "ADOPTIVE", { setSibShape("ADOPTIVE") })
                parents.forEach { p -> Choice("Half-sibling – only ${nm(p)} is the parent", sibShape == "HALF:$p", { setSibShape("HALF:$p"); setSibOther(null) }) }
                if (sibShape.startsWith("HALF:")) {
                    val via = sibShape.removePrefix("HALF:")
                    Text("Other parent", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                    Choice("None / not recorded", sibOther == null, { setSibOther(null) })
                    data.index.marriages(via).map { it.other(via) }.distinct().filter { it !in parents }.forEach { p -> Choice(nm(p), sibOther == p, { setSibOther(p) }) }
                }
            }
            SectionTitle("Birth order")
            Choice("Elder than $aName", sibPlacement == 0, { setSibPlacement(0) })
            Choice("Younger than $aName", sibPlacement == 1, { setSibPlacement(1) })
            Choice("Not known – shows both addressing terms until ordered", sibPlacement == 2, { setSibPlacement(2) })
        }
    }
}
