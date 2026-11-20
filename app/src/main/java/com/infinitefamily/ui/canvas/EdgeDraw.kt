package com.infinitefamily.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.infinitefamily.domain.*
import com.infinitefamily.ui.Pencil
import kotlin.math.abs
import kotlin.math.max

/** Draws marriages, parent-child "buses", half-sibling squiggles and step links from [TreeScene]. */
object EdgeDraw {
    private const val HW = StickerGeometry.W / 2
    private const val HH = StickerGeometry.H / 2
    private val dashed = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))
    private val dotted = PathEffect.dashPathEffect(floatArrayOf(2f, 10f))
    private val round = StrokeCap.Round

    private fun Path.pencil(a: Offset, b: Offset, bow: Float = 0f) {
        moveTo(a.x, a.y)
        val c = Offset((a.x + b.x) / 2, (a.y + b.y) / 2 + bow)
        quadraticBezierTo(c.x, c.y, b.x, b.y)
    }

    fun DrawScope.edges(
        scene: TreeScene, pos: Map<String, NodePos>, minWidth: Float,
        visible: (Float, Float, Float, Float) -> Boolean,
    ) {
        fun w(base: Float) = max(base, minWidth)

        // Marriage lines
        val anchors = HashMap<String, Offset>() // marriage id -> point children hang from
        for (m in scene.marriages) {
            val a = pos[m.aId] ?: continue; val b = pos[m.bId] ?: continue
            val (l, r) = if (a.x <= b.x) a to b else b to a
            val adjacent = abs(r.x - l.x) <= StickerGeometry.W + 60f && abs(r.y - l.y) < 1f
            val color: Color; val width: Float; val effect: PathEffect?
            when (m.status) {
                MarriageStatus.CURRENT -> { color = Pencil.Red; width = 5.5f; effect = null }
                MarriageStatus.WIDOWED -> { color = Pencil.Graphite; width = 4.2f; effect = null }
                MarriageStatus.PREVIOUS -> { color = Pencil.LightGrey; width = 4.2f; effect = dashed }
                MarriageStatus.DIVORCED -> { color = Pencil.LightGrey; width = 4.2f; effect = null }
            }
            val path = Path()
            val mid: Offset
            if (adjacent) {
                val y = l.y + 12f
                val s = Offset(l.x + HW - 2f, y); val e = Offset(r.x - HW + 2f, y)
                path.pencil(s, e, 1.5f); mid = Offset((s.x + e.x) / 2, y + 0.8f)
            } else {
                val s = Offset(l.x, l.y + HH - 4f); val e = Offset(r.x, r.y + HH - 4f)
                val depth = 40f + 0.05f * abs(r.x - l.x)
                path.moveTo(s.x, s.y); path.cubicTo(s.x, s.y + depth, e.x, e.y + depth, e.x, e.y)
                mid = Offset((s.x + e.x) / 2, s.y + depth * 0.75f)
            }
            if (visible(l.x - HW, l.y - HH, r.x + HW, r.y + HH + 80f)) {
                drawPath(path, color, style = Stroke(w(width), cap = round, pathEffect = effect))
                if (m.status == MarriageStatus.DIVORCED) { // a small cross-bar on the grey line only
                    drawLine(Pencil.Grey, Offset(mid.x - 7f, mid.y - 9f), Offset(mid.x + 7f, mid.y + 9f), w(2.4f), round)
                }
                if (m.status == MarriageStatus.CURRENT) { // two linked rings
                    drawCircle(Pencil.Red, 6f, Offset(mid.x - 4f, mid.y), style = Stroke(2f))
                    drawCircle(Pencil.Red, 6f, Offset(mid.x + 4f, mid.y), style = Stroke(2f))
                }
            }
            anchors[m.id] = mid
        }

        // Parent -> children buses
        // Families whose horizontal bars would touch or overlap get their own height ("lane"), so each
        // family keeps a clearly separate bar instead of merging into one.
        val extents = HashMap<ChildGroup, FloatArray>()
        for (g in scene.groups) {
            val parents = g.parentIds.mapNotNull { pos[it] }
            val kids = g.childIds.mapNotNull { pos[it] }
            if (parents.isEmpty() || kids.isEmpty()) continue
            val sx = g.marriageId?.let { anchors[it] }?.x ?: parents.map { it.x }.average().toFloat()
            val xs = kids.map { it.x } + sx
            extents[g] = floatArrayOf(xs.min(), xs.max(), kids.minOf { it.y })
        }
        val laneOf = HashMap<ChildGroup, Int>()
        val laneEnds = HashMap<Float, MutableList<Float>>() // child row y -> right end of each lane
        for (g in extents.keys.sortedBy { extents.getValue(it)[0] }) {
            val e = extents.getValue(g)
            val ends = laneEnds.getOrPut(e[2]) { mutableListOf() }
            var lane = ends.indexOfFirst { it + 40f < e[0] }
            if (lane < 0) { ends += e[1]; lane = ends.size - 1 } else ends[lane] = e[1]
            laneOf[g] = lane
        }
        for (g in scene.groups) {
            val parents = g.parentIds.mapNotNull { pos[it] }
            val kids = g.childIds.mapNotNull { pos[it] }
            if (parents.isEmpty() || kids.isEmpty()) continue
            val anchor = g.marriageId?.let { anchors[it] }
            val lane = laneOf[g] ?: 0
            val start = anchor ?: Offset(parents.map { it.x }.average().toFloat(), parents.maxOf { it.y } + HH)
            val childTop = kids.minOf { it.y } - HH
            val busY = (start.y + (childTop - start.y) * 0.5f + lane * 18f).coerceAtMost(childTop - 14f)
            val minX = (kids.map { it.x } + start.x).min(); val maxX = (kids.map { it.x } + start.x).max()
            if (!visible(minX - HW, start.y - 20f, maxX + HW, kids.maxOf { it.y } + HH)) continue
            val color: Color; val width = 3.4f; val effect: PathEffect?
            when (g.type) {
                LinkType.ADOPTIVE -> { color = Pencil.Slate; effect = dashed }
                LinkType.STEP -> { color = Pencil.Olive; effect = dotted }
                LinkType.BIOLOGICAL -> { color = Pencil.Graphite; effect = null }
            }
            val st = Stroke(w(width), cap = round, join = StrokeJoin.Round, pathEffect = effect)
            val p = Path()
            if (anchor == null && parents.size > 1) { // unmarried co-parents: each drops to the bus
                parents.forEach { pp -> p.moveTo(pp.x, pp.y + HH); p.lineTo(pp.x, busY) }
            }
            p.moveTo(start.x, start.y); p.lineTo(start.x, busY)
            p.moveTo(minX, busY); p.lineTo(maxX, busY)
            kids.forEach { k -> p.moveTo(k.x, busY); p.lineTo(k.x, k.y - HH + 2f) }
            drawPath(p, color, style = st)
        }

        // Half siblings: wavy link above the stickers
        for (h in scene.halfLinks) {
            val a = pos[h.aId] ?: continue; val b = pos[h.bId] ?: continue
            val (l, r) = if (a.x <= b.x) a to b else b to a
            if (!visible(l.x - HW, l.y - HH - 40f, r.x + HW, l.y)) continue
            val path = Path()
            val y0 = l.y - HH - 6f
            path.moveTo(l.x, y0 + 6f)
            val span = r.x - l.x; val n = max(4, (abs(span) / 40f).toInt())
            val seg = span / n
            for (i in 0 until n) {
                val dir = if (i % 2 == 0) -1f else 1f
                path.quadraticBezierTo(l.x + seg * (i + 0.5f), y0 - 16f * dir - 8f, l.x + seg * (i + 1), y0 + 6f)
            }
            drawPath(path, Pencil.Grey, style = Stroke(w(2.8f), cap = round))
        }

        // Step-parent links: dotted curve from the step-parent to the child
        for (s in scene.stepLinks) {
            val p = pos[s.parentId] ?: continue; val c = pos[s.childId] ?: continue
            if (!visible(minOf(p.x, c.x) - HW, p.y, maxOf(p.x, c.x) + HW, c.y)) continue
            val path = Path().apply { pencil(Offset(p.x, p.y + HH), Offset(c.x, c.y - HH), 0f) }
            drawPath(path, Pencil.Olive, style = Stroke(w(3f), cap = round, pathEffect = dotted))
        }
    }
}
