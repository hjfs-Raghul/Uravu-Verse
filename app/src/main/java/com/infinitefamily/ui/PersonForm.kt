@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.infinitefamily.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.infinitefamily.domain.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Editable copy of a person. Only name and gender are mandatory. */
@Stable
class PersonDraft(p: Person? = null, presetGender: Gender? = null) {
    val id = p?.id ?: FamilyEditor.newId()
    var name by mutableStateOf(p?.name ?: "")
    var gender by mutableStateOf(p?.gender ?: presetGender)
    var dob by mutableStateOf(p?.dob ?: "")
    var deceased by mutableStateOf(p?.deceased ?: false)
    var deathDate by mutableStateOf(p?.deathDate ?: "")
    var occupation by mutableStateOf(p?.occupation ?: "")
    var location by mutableStateOf(p?.location ?: "")
    var notes by mutableStateOf(p?.notes ?: "")
    var relativeAge by mutableStateOf(p?.relativeAge ?: RelativeAge.UNKNOWN)
    var photoFile by mutableStateOf(p?.photoFile)
    var stickerCategory by mutableStateOf(p?.stickerCategory)
    var stickerVariant by mutableIntStateOf(p?.stickerVariant ?: 0)

    val valid get() = name.isNotBlank() && gender != null

    fun toPerson(base: Person?): Person = (base ?: Person(id, "", Gender.OTHER, createdAt = System.currentTimeMillis())).copy(
        name = name.trim(), gender = gender ?: Gender.OTHER, dob = dob.trim().ifBlank { null }, deceased = deceased,
        deathDate = if (deceased) deathDate.trim().ifBlank { null } else null, occupation = occupation.trim(), location = location.trim(),
        notes = notes.trim(), relativeAge = if (dob.isBlank()) relativeAge else (base?.relativeAge ?: relativeAge),
        photoFile = photoFile, stickerCategory = stickerCategory, stickerVariant = stickerVariant,
    )
}

@Composable
fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var picker by remember { mutableStateOf(false) }
    val bad = value.isNotBlank() && PartialDate.parse(value) == null
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(), singleLine = true, label = { Text(label) }, isError = bad,
        placeholder = { Text("YYYY, YYYY-MM or YYYY-MM-DD") },
        supportingText = { if (bad) Text("Use YYYY, YYYY-MM or YYYY-MM-DD") },
        trailingIcon = { IconButton({ picker = true }) { Icon(Icons.Default.DateRange, "Pick date") } },
    )
    if (picker) {
        val st = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { picker = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    picker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ picker = false }) { Text("Cancel") } },
        ) { DatePicker(st) }
    }
}

@Composable
fun PersonFormFields(draft: PersonDraft, vm: FamilyViewModel, minimal: Boolean, rootName: String, genDelta: Int = 0) {
    val scope = rememberCoroutineScope()
    var more by remember { mutableStateOf(!minimal) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { vm.importPhoto(uri)?.let { draft.photoFile = it } ?: vm.say("That photo could not be read.") }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(draft.name, { draft.name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name *") })
        Text("Gender *", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Gender.MALE to "Male", Gender.FEMALE to "Female", Gender.OTHER to "Other").forEach { (g, l) ->
                FilterChip(draft.gender == g, { draft.gender = g }, { Text(l) })
            }
        }
        if (minimal) TextButton({ more = !more }) { Text(if (more) "Fewer details" else "More details (optional)") }
        if (more) {
            DateField("Date of birth", draft.dob, { draft.dob = it })
            if (draft.dob.isBlank()) {
                Text("Age compared with $rootName", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(RelativeAge.OLDER to "Older", RelativeAge.SAME to "About the same", RelativeAge.YOUNGER to "Younger", RelativeAge.UNKNOWN to "Unknown").forEach { (a, l) ->
                        FilterChip(draft.relativeAge == a, { draft.relativeAge = a }, { Text(l) })
                    }
                }
                Muted("Only a hint when the birth date is unknown. Brothers and sisters are ordered by dragging.")
            }
            OutlinedTextField(draft.occupation, { draft.occupation = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Occupation") })
            OutlinedTextField(draft.location, { draft.location = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Location (city)") })
            OutlinedTextField(draft.notes, { draft.notes = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Notes") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(draft.deceased, { draft.deceased = it }); Spacer(Modifier.width(10.dp)); Text("Deceased")
            }
            if (draft.deceased) DateField("Date of death (optional)", draft.deathDate, { draft.deathDate = it })

            Text("Picture", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton("Choose photo", { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                if (draft.photoFile != null) PButton("Use drawing", { draft.photoFile = null })
            }
            if (draft.photoFile == null) StickerChooser(draft, genDelta)
            else Muted("The photo is shown on the sticker. Remove it to use a pencil drawing instead.")
        }
    }
}

@Composable
private fun StickerChooser(draft: PersonDraft, genDelta: Int) {
    val temp = draft.toPerson(null)
    val auto = StickerCategory.choose(temp.copy(stickerCategory = null), LocalDate.now(), genDelta)
    val female = temp.gender == Gender.FEMALE
    val current = StickerCategory.byId(draft.stickerCategory, female) ?: auto
    var otherGender by remember { mutableStateOf(current.female != female) }
    Text("Drawing", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(draft.stickerCategory == null, { draft.stickerCategory = null }, { Text("Automatic (${auto.label})") })
        StickerCategory.forGender(if (otherGender) !female else female).forEach { c ->
            FilterChip(draft.stickerCategory == c.id, { draft.stickerCategory = c.id }, { Text(c.label) })
        }
        FilterChip(otherGender, { otherGender = !otherGender }, { Text(if (female) "Show male drawings" else "Show female drawings") })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (v in 0 until current.variants) {
            val selected = Math.floorMod(draft.stickerVariant, current.variants) == v
            Box((if (selected) Modifier.border(3.dp, Pencil.Red) else Modifier).clickable { draft.stickerVariant = v + current.variants * 100 }.padding(2.dp)) {
                SketchThumb(current, v, 64.dp)
            }
        }
    }
}

/** Full-screen editor for an existing profile. */
@Composable
fun PersonEditorDialog(vm: FamilyViewModel, personId: String, onClose: () -> Unit) {
    val data by vm.data.collectAsState()
    val base = data.persons[personId] ?: run { onClose(); return }
    val draft = remember(personId) { PersonDraft(base) }
    val gens = rememberGenerations(data)
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClose) { Text("Cancel") }
                    Text("Edit profile", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    PButton("Save", {
                        vm.edit("Saved ${draft.name.trim()}", onDone = { r -> if (r is EditResult.Ok) onClose() else if (r is EditResult.Fail) error = r.message }) { FamilyEditor.updatePerson(it, draft.toPerson(base)) }
                    }, filled = true, enabled = draft.valid)
                }
                HorizontalDivider(color = Pencil.LightGrey)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    error?.let { Text(it, color = Pencil.Red, modifier = Modifier.padding(bottom = 8.dp)) }
                    PersonFormFields(draft, vm, minimal = false, rootName = data.root?.name ?: "the reference person", genDelta = gens[personId] ?: 0)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
