@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.infinitefamily.ui

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

/** Relationship between any two people, both directions, with terms, paths and alternatives. */
@Composable
fun RelationExplorer(vm: FamilyViewModel, initialA: String?, initialB: String?, onShowPath: (List<String>) -> Unit, onClose: () -> Unit) {
    val data by vm.data.collectAsState()
    val engine by vm.engine.collectAsState()
    var aId by remember { mutableStateOf(initialA ?: data.rootId) }
    var bId by remember { mutableStateOf(initialB) }
    var picking by remember { mutableStateOf(0) } // 1 = A, 2 = B
    val a = aId?.let { data.persons[it] }
    val b = bId?.let { data.persons[it] }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClose) { Text("Close") }
                    Text("How are they related?", style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider(color = Pencil.LightGrey)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (picking != 0) {
                        Text(if (picking == 1) "Choose the first person" else "Choose the second person", style = MaterialTheme.typography.titleSmall)
                        PersonPicker(vm, vm.repo.photos, onPick = { if (picking == 1) aId = it.id else bId = it.id; picking = 0 })
                        TextButton({ picking = 0 }) { Text("Cancel") }
                        return@Column
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ picking = 1 }, Modifier.weight(1f)) { Text(a?.name ?: "Choose person", maxLines = 1) }
                        TextButton({ val t = aId; aId = bId; bId = t }) { Text("⇄") }
                        OutlinedButton({ picking = 2 }, Modifier.weight(1f)) { Text(b?.name ?: "Choose person", maxLines = 1) }
                    }
                    if (a == null || b == null) { Spacer(Modifier.height(12.dp)); Muted("Pick two people to see how each is related to the other and how they address each other.") }
                    else {
                        val pair = remember(engine, a.id, b.id) { engine.pair(a.id, b.id) }
                        SectionTitle("${b.name} → ${a.name}")
                        RelationBlock(pair.aToB, a.name, b.name)
                        SectionTitle("${a.name} → ${b.name}")
                        RelationBlock(pair.bToA, b.name, a.name)
                        pair.aToB.primary?.let { prim ->
                            Spacer(Modifier.height(14.dp))
                            PButton("Show path on the tree", { onShowPath(listOf(a.id) + prim.steps.map { it.to }); onClose() })
                        }
                    }
                }
            }
        }
    }
}
