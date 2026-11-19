@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.infinitefamily.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinitefamily.data.PhotoStore
import com.infinitefamily.domain.*
import com.infinitefamily.ui.canvas.Sketches
import java.time.LocalDate

@Composable
fun PButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true) {
    val shape = RoundedCornerShape(3.dp)
    if (filled) Button(onClick, modifier, enabled, shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Pencil.Graphite, contentColor = Pencil.Paper)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    } else OutlinedButton(onClick, modifier, enabled, shape = shape, border = BorderStroke(1.4.dp, if (enabled) Pencil.Graphite else Pencil.LightGrey),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Pencil.Graphite)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 18.dp, bottom = 6.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(Modifier.padding(top = 3.dp), color = Pencil.LightGrey)
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = Pencil.Grey)

@Composable
fun rememberGenerations(data: FamilyData): Map<String, Int> = remember(data) { Generations.compute(data) }

/** Mini pencil illustration (used by the sticker chooser and avatars). */
@Composable
fun SketchThumb(category: StickerCategory, variant: Int, size: Dp = 64.dp, faded: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).background(Pencil.Sticker).border(1.dp, Pencil.Grey)) {
        val sc = this.size.width / 120f
        clipRect {
            withTransform({ translate(this@Canvas.size.width / 2, this@Canvas.size.height / 2); scale(sc, sc, Offset.Zero) }) {
                for (pen in Sketches.pens(category, variant)) {
                    val c = Pencil.Graphite.copy(alpha = pen.alpha * (if (faded) 0.42f else 1f))
                    if (pen.fill) drawPath(pen.path, c) else drawPath(pen.path, c, style = Stroke(pen.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

@Composable
fun PersonAvatar(p: Person, store: PhotoStore, category: StickerCategory, size: Dp = 48.dp) {
    val bmp by produceState<ImageBitmap?>(null, p.photoFile) { value = p.photoFile?.let { f -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.decode(f, 200)?.asImageBitmap() } } }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(size).border(1.dp, Pencil.Grey), contentScale = ContentScale.Crop, alpha = if (p.deceased) 0.45f else 1f,
        colorFilter = if (p.deceased) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null)
    else SketchThumb(category, p.stickerVariant, size, faded = p.deceased)
}

fun lineLabel(t: LinkType) = when (t) { LinkType.BIOLOGICAL -> "Biological"; LinkType.ADOPTIVE -> "Adoptive"; LinkType.STEP -> "Step" }
fun statusLabel(s: MarriageStatus) = when (s) {
    MarriageStatus.CURRENT -> "Current marriage"; MarriageStatus.PREVIOUS -> "Previous marriage"
    MarriageStatus.DIVORCED -> "Divorced"; MarriageStatus.WIDOWED -> "Widowed"
}

/** Searchable list of existing people. Used whenever an existing profile must be chosen (no duplicates). */
@Composable
fun PersonPicker(
    vm: FamilyViewModel, store: PhotoStore, onPick: (Person) -> Unit,
    exclude: Set<String> = emptySet(), filter: (Person) -> Boolean = { true }, modifier: Modifier = Modifier,
) {
    val data by vm.data.collectAsState()
    val engine by vm.engine.collectAsState()
    val gens = rememberGenerations(data)
    val today = remember { LocalDate.now() }
    var q by remember { mutableStateOf("") }
    val matches = remember(q, data, exclude) {
        val t = q.trim().lowercase()
        data.persons.values.filter { it.id !in exclude && filter(it) && (t.isEmpty() || it.name.lowercase().contains(t) || it.location.lowercase().contains(t)) }
            .sortedBy { it.name.lowercase() }.take(80)
    }
    Column(modifier) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search by name or location") })
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            items(matches, key = { it.id }) { p ->
                val rel = data.rootId?.let { engine.relation(it, p.id).primary?.summary }
                Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(p, store, StickerCategory.choose(p, today, gens[p.id] ?: 0), 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(p.location.takeIf { it.isNotBlank() }, rel).joinToString(" · ")
                        if (sub.isNotBlank()) Muted(sub)
                    }
                }
                HorizontalDivider(color = Pencil.Faint)
            }
            if (matches.isEmpty()) item { Muted("No matching people.", Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun TermLine(label: String, terms: List<Term>, approx: Boolean) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, Modifier.width(86.dp), style = MaterialTheme.typography.bodyMedium, color = Pencil.Grey)
        Column {
            if (terms.isEmpty()) Text("Not configured – add it in Settings › Terminology", style = MaterialTheme.typography.bodySmall, color = Pencil.Grey)
            terms.forEach { t ->
                Text(buildString { append(t.text); if (t.roman.isNotBlank()) append("  (${t.roman})") }, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                if (t.note != null) Muted(t.note)
            }
            if (approx && terms.isNotEmpty()) Muted("Based on the equivalent plain relationship; usage varies by family.")
        }
    }
}

/** Shows "[to] is [from]'s ..." with how [from] addresses them in English / Tamil / Malayalam, the path and alternatives. */
@Composable
fun RelationBlock(r: RelationResult, fromName: String, toName: String, heading: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        if (heading != null) Text(heading, style = MaterialTheme.typography.titleSmall)
        when {
            r.isSelf -> Text("This is the same person.", style = MaterialTheme.typography.bodyMedium)
            !r.connected -> Text("No family connection between $fromName and $toName is recorded yet.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                val p = r.primary!!
                Text("$toName is $fromName's ${p.summary.replaceFirstChar { it.lowercase() }}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (p.ambiguous) Text("Age order is not recorded, so both possibilities are shown:", style = MaterialTheme.typography.bodySmall, color = Pencil.Red)
                p.readings.forEach { rd ->
                    Column(Modifier.padding(top = 8.dp).fillMaxWidth().background(Pencil.Faint.copy(alpha = 0.45f)).padding(10.dp)) {
                        if (rd.condition != null) Text("If ${rd.condition.replaceFirstChar { it.lowercase() }}:", style = MaterialTheme.typography.labelLarge, color = Pencil.Red)
                        Text(rd.terms.relationEn, style = MaterialTheme.typography.titleSmall)
                        Muted("$fromName addresses $toName as:")
                        TermLine("English", listOf(Term(rd.terms.addressEn)), false)
                        TermLine("Tamil", rd.terms.ta, rd.terms.taApproximate)
                        TermLine("Malayalam", rd.terms.ml, rd.terms.mlApproximate)
                        rd.terms.note?.let { Muted(it) }
                    }
                }
                Text("Path", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge)
                p.stepTexts.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
                if (r.alternatives.isNotEmpty()) {
                    Text("Other relationships between them", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge)
                    r.alternatives.forEach { alt ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text("• ${alt.summary}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Muted(alt.stepTexts.joinToString("  →  "))
                        }
                    }
                }
            }
        }
    }
}
