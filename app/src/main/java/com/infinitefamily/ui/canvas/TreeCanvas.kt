package com.infinitefamily.ui.canvas

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import com.infinitefamily.domain.*
import com.infinitefamily.ui.Pencil
import com.infinitefamily.ui.canvas.EdgeDraw.edges
import java.time.LocalDate
import kotlin.math.max

/**
 * The white sheet. Everything is drawn in one Canvas (no per-person composables) and only what is
 * inside the viewport is drawn, so very large trees stay smooth. Positions come from [TreeLayout];
 * nothing drawn here is stored.
 */
@Composable
fun TreeCanvas(
    controller: CanvasController,
    data: FamilyData,
    layout: TreeLayoutResult,
    scene: TreeScene,
    photos: PhotoCache,
    selectedId: String?,
    pulseId: String?,
    pathIds: List<String>,
    onTap: (String?) -> Unit,
    onMove: (String, Float, Float) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    controller.density = density
    val measurer = rememberTextMeasurer(cacheSize = 400)
    val renderer = remember(measurer, photos) { StickerRenderer(measurer, photos) }
    val today = remember { LocalDate.now() }
    val generations = remember(data) { Generations.compute(data) }
    val currentLayout by rememberUpdatedState(layout)
    val currentData by rememberUpdatedState(data)
    val currentOnMove by rememberUpdatedState(onMove)
    val haptics = LocalHapticFeedback.current
    // A person being dragged is drawn at the finger; the new position is saved when the finger lifts.
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragWorld by remember { mutableStateOf(Offset.Zero) }
    // After the finger lifts, the dropped position is kept until the saved layout arrives (no flicker back).
    var dropped by remember(layout) { mutableStateOf<Pair<String, Offset>?>(null) }
    val positions = remember(layout, dragId, dragWorld, dropped) {
        val base = layout.positions
        val id = dragId
        val held = if (id != null) id to dragWorld else dropped
        if (held == null || held.first !in base) base else base + (held.first to base.getValue(held.first).copy(x = held.second.x, y = held.second.y))
    }
    fun hitAt(w: Offset): NodePos? = currentLayout.positions.values.minByOrNull { n ->
        val dx = kotlin.math.abs(n.x - w.x); val dy = kotlin.math.abs(n.y - w.y)
        if (dx <= StickerGeometry.W / 2 + 4 && dy <= StickerGeometry.H / 2 + 4) dx + dy else Float.MAX_VALUE
    }?.takeIf { kotlin.math.abs(it.x - w.x) <= StickerGeometry.W / 2 + 4 && kotlin.math.abs(it.y - w.y) <= StickerGeometry.H / 2 + 4 }

    // The pulse only animates while a search result is being highlighted (no idle redraws).
    val pulse = if (pulseId != null) {
        rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
    } else 0f
    val onPathSet = remember(pathIds) { pathIds.toSet() }

    Canvas(
        modifier
            .fillMaxSize()
            .background(Pencil.Paper)
            .onSizeChanged { controller.size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ -> controller.transform(centroid, pan, zoom) }
            }
            .pointerInput(Unit) {
                // A tap only fires when the finger did not move, so panning never selects by accident.
                detectTapGestures { p -> onTap(hitAt(controller.screenToWorld(p))?.id) }
            }
            .pointerInput(Unit) {
                // Press and hold a sticker, then drag it anywhere. Declared last so it takes the gesture
                // before the pan / zoom handler once the long press has started.
                detectDragGesturesAfterLongPress(
                    onDragStart = { p ->
                        val hit = hitAt(controller.screenToWorld(p))
                        if (hit != null) {
                            dragId = hit.id; dragWorld = Offset(hit.x, hit.y)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { change, amount ->
                        if (dragId != null) {
                            change.consume()
                            dragWorld += amount / (controller.scale * controller.density)
                        }
                    },
                    onDragEnd = { dragId?.let { dropped = it to dragWorld; currentOnMove(it, dragWorld.x, dragWorld.y) }; dragId = null },
                    onDragCancel = { dragId = null },
                )
            },
    ) {
        val s = controller.scale
        val k = s * density
        val off = controller.offset
        // visible world rectangle with a margin
        val vl = -off.x / k - 120f; val vt = -off.y / k - 120f
        val vr = (size.width - off.x) / k + 120f; val vb = (size.height - off.y) / k + 120f
        val visible = { l: Float, t: Float, r: Float, b: Float -> !(r < vl || l > vr || b < vt || t > vb) }
        val boost = (Math.round((0.45f / s).coerceIn(1f, 2.4f) * 5f) / 5f)

        withTransform({ translate(off.x, off.y); scale(k, k, Offset.Zero) }) {
            edges(scene, positions, minWidth = 1.6f / k, visible = visible)

            if (pathIds.size > 1) {
                val path = Path()
                pathIds.mapNotNull { positions[it] }.forEachIndexed { i, n -> if (i == 0) path.moveTo(n.x, n.y) else path.lineTo(n.x, n.y) }
                drawPath(path, Pencil.Red.copy(alpha = 0.28f), style = Stroke(max(16f, 3f / k), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }

            for (n in positions.values) {
                if (!visible(n.x - 90f, n.y - 110f, n.x + 90f, n.y + 110f)) continue
                val p = data.persons[n.id] ?: continue
                val cat = StickerCategory.choose(p, today, generations[p.id] ?: 0)
                with(renderer) {
                    sticker(p, cat, n.x, n.y, boost, n.id == selectedId, if (n.id == pulseId) pulse else 0f, n.id in onPathSet, showLocation = s > 0.3f)
                }
            }
        }
    }
}
