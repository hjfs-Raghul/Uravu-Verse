package com.infinitefamily.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.infinitefamily.domain.StickerCategory
import kotlin.math.hypot
import kotlin.random.Random

/** One pencil stroke (or a small filled dot) of an illustration. */
class Pen(val path: Path, val width: Float, val alpha: Float, val fill: Boolean = false)

private enum class Hair { SHORT, SPIKY, PARTED, CURLY, BUN, LONG, PONY, PIGTAILS, BRAIDS, WISPS, BALD, TUFT, CAP }
private enum class Garment { SHIRT, TEE, KURTA, SAREE, DRESS, SWADDLE, SHAWL }
private data class Look(
    val hair: Hair, val garment: Garment, val glasses: Boolean = false, val moustache: Boolean = false,
    val beard: Boolean = false, val bindi: Boolean = false, val wrinkles: Boolean = false,
)

/**
 * Procedural pencil-sketch people. Several distinct looks per category so siblings and cousins do not
 * all look alike. Coordinates: x -60..60, y -55..55 (the frame region); the head sits near the top.
 */
object Sketches {
    private val looks: Map<StickerCategory, List<Look>> = mapOf(
        StickerCategory.INFANT to listOf(Look(Hair.TUFT, Garment.SWADDLE), Look(Hair.BALD, Garment.SWADDLE), Look(Hair.CURLY, Garment.TEE)),
        StickerCategory.BOY to listOf(
            Look(Hair.SHORT, Garment.TEE), Look(Hair.SPIKY, Garment.TEE), Look(Hair.PARTED, Garment.SHIRT),
            Look(Hair.CURLY, Garment.TEE), Look(Hair.CAP, Garment.TEE, glasses = true), Look(Hair.SHORT, Garment.SHIRT, glasses = true)),
        StickerCategory.GIRL to listOf(
            Look(Hair.PIGTAILS, Garment.DRESS), Look(Hair.PONY, Garment.TEE), Look(Hair.BRAIDS, Garment.DRESS),
            Look(Hair.LONG, Garment.DRESS, bindi = true), Look(Hair.BUN, Garment.TEE), Look(Hair.CURLY, Garment.DRESS)),
        StickerCategory.YOUNG_MAN to listOf(
            Look(Hair.SHORT, Garment.SHIRT), Look(Hair.PARTED, Garment.SHIRT, glasses = true),
            Look(Hair.SPIKY, Garment.TEE, moustache = true), Look(Hair.CURLY, Garment.KURTA, beard = true)),
        StickerCategory.YOUNG_WOMAN to listOf(
            Look(Hair.LONG, Garment.KURTA, bindi = true), Look(Hair.PONY, Garment.KURTA),
            Look(Hair.BUN, Garment.SAREE, glasses = true, bindi = true), Look(Hair.BRAIDS, Garment.SAREE, bindi = true)),
        StickerCategory.MIDDLE_MAN to listOf(
            Look(Hair.PARTED, Garment.SHIRT, moustache = true), Look(Hair.SHORT, Garment.SHIRT, glasses = true),
            Look(Hair.BALD, Garment.KURTA, moustache = true)),
        StickerCategory.MIDDLE_WOMAN to listOf(
            Look(Hair.BUN, Garment.SAREE, bindi = true), Look(Hair.LONG, Garment.SAREE, bindi = true, wrinkles = true),
            Look(Hair.PONY, Garment.KURTA, glasses = true)),
        StickerCategory.ELDER_MAN to listOf(
            Look(Hair.BALD, Garment.SHAWL, moustache = true, glasses = true, wrinkles = true),
            Look(Hair.TUFT, Garment.SHAWL, beard = true, wrinkles = true),
            Look(Hair.SHORT, Garment.SHIRT, moustache = true, wrinkles = true)),
        StickerCategory.ELDER_WOMAN to listOf(
            Look(Hair.WISPS, Garment.SAREE, bindi = true, wrinkles = true),
            Look(Hair.WISPS, Garment.SAREE, glasses = true, wrinkles = true),
            Look(Hair.BUN, Garment.SHAWL, bindi = true, wrinkles = true)),
    )

    private val cache = HashMap<String, List<Pen>>()

    fun pens(category: StickerCategory, variant: Int): List<Pen> {
        val idx = Math.floorMod(variant, category.variants)
        return cache.getOrPut("${category.id}:$idx") { build(category, looks.getValue(category)[idx], category.ordinal * 97 + idx * 13 + 5) }
    }

    private class Sk(seed: Int) {
        val rnd = Random(seed)
        val pens = ArrayList<Pen>()
        fun j(a: Float = 0.8f) = (rnd.nextFloat() - 0.5f) * 2f * a
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 2f, a: Float = 0.85f) {
            val p = Path().apply {
                moveTo(x1 + j(), y1 + j())
                quadraticBezierTo((x1 + x2) / 2 + j(1.2f), (y1 + y2) / 2 + j(1.2f), x2 + j(), y2 + j())
            }
            pens += Pen(p, w, a)
        }
        fun curve(x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float, w: Float = 2f, a: Float = 0.85f) {
            val p = Path().apply { moveTo(x1 + j(), y1 + j()); quadraticBezierTo(cx + j(), cy + j(), x2 + j(), y2 + j()) }
            pens += Pen(p, w, a)
        }
        fun oval(cx: Float, cy: Float, rx: Float, ry: Float, w: Float = 2f, a: Float = 0.85f) {
            repeat(2) { // two slightly offset passes look like a sketched outline
                val p = Path().apply { addOval(Rect(cx - rx + j(), cy - ry + j(), cx + rx + j(), cy + ry + j())) }
                pens += Pen(p, w, if (it == 0) a else a * 0.5f)
            }
        }
        fun dot(x: Float, y: Float, r: Float, a: Float = 0.9f) {
            pens += Pen(Path().apply { addOval(Rect(x - r, y - r, x + r, y + r)) }, 1f, a, fill = true)
        }
        fun hatch(x1: Float, y1: Float, x2: Float, y2: Float, n: Int, dx: Float, dy: Float, a: Float = 0.3f) {
            for (i in 0 until n) line(x1 + dx * i, y1 + dy * i, x2 + dx * i, y2 + dy * i, 1.2f, a)
        }
    }

    private fun build(cat: StickerCategory, look: Look, seed: Int): List<Pen> {
        val s = Sk(seed)
        val infant = cat == StickerCategory.INFANT
        val child = cat == StickerCategory.BOY || cat == StickerCategory.GIRL
        val r = when { infant -> 24f; child -> 20f; else -> 17f }
        val hy = when { infant -> -4f; child -> -8f; else -> -13f }
        val female = cat in listOf(StickerCategory.GIRL, StickerCategory.YOUNG_WOMAN, StickerCategory.MIDDLE_WOMAN, StickerCategory.ELDER_WOMAN)
        val sh = hy + r + 9f                     // shoulder line
        val bw = when { infant -> 24f; child -> 32f; female -> 34f; else -> 42f }
        val elder = cat == StickerCategory.ELDER_MAN || cat == StickerCategory.ELDER_WOMAN

        // body -----------------------------------------------------------------
        s.line(-5f, hy + r - 1, -5.5f, sh, 1.8f); s.line(5f, hy + r - 1, 5.5f, sh, 1.8f)             // neck
        s.curve(-5.5f, sh, -bw * 0.75f, sh + 1, -bw, sh + 14, 2.2f)                                  // shoulders
        s.curve(5.5f, sh, bw * 0.75f, sh + 1, bw, sh + 14, 2.2f)
        s.line(-bw, sh + 14, -bw + 3, 60f, 2.2f); s.line(bw, sh + 14, bw - 3, 60f, 2.2f)
        s.hatch(bw * 0.25f, sh + 18, bw * 0.7f, sh + 8, 6, 0f, 7f)                                    // shading
        when (look.garment) {
            Garment.TEE, Garment.DRESS -> {
                s.curve(-8f, sh, 0f, sh + 9f, 8f, sh, 1.8f)
                if (look.garment == Garment.DRESS) { s.line(-bw + 4, sh + 30, bw - 4, sh + 30, 1.6f); s.line(-bw, sh + 14, -bw - 4, 60f, 1.4f, 0.5f) }
            }
            Garment.SHIRT -> {
                s.line(-8f, sh, 0f, sh + 11f, 1.8f); s.line(8f, sh, 0f, sh + 11f, 1.8f)
                s.line(0f, sh + 11f, 0f, 60f, 1.4f, 0.7f); s.dot(1.5f, sh + 22f, 1.1f); s.dot(1.5f, sh + 34f, 1.1f)
            }
            Garment.KURTA -> {
                s.curve(-8f, sh, 0f, sh + 10f, 8f, sh, 1.8f); s.line(0f, sh + 8f, 0f, sh + 26f, 1.5f); s.dot(1.5f, sh + 14f, 1.1f); s.dot(1.5f, sh + 21f, 1.1f)
            }
            Garment.SAREE -> {
                s.curve(-8f, sh, 0f, sh + 9f, 8f, sh, 1.8f)
                s.line(-bw + 4, sh + 10, bw - 6, 58f, 2.2f); s.line(-bw + 10, sh + 8, bw - 2, 50f, 1.4f, 0.6f)
                s.hatch(-bw + 18, 38f, -bw + 30, 58f, 4, 8f, 0f)
            }
            Garment.SWADDLE -> {
                s.curve(-bw, sh + 6, 0f, sh + 22f, bw, sh + 6, 2f)
                s.line(-bw + 4, sh + 20, bw - 6, sh + 40, 1.5f, 0.7f); s.line(bw - 4, sh + 18, -bw + 8, sh + 42, 1.5f, 0.7f)
            }
            Garment.SHAWL -> {
                s.line(-bw + 2, sh + 12, bw - 8, sh + 40, 2.4f); s.line(-bw + 10, sh + 8, bw - 2, sh + 36, 1.5f, 0.6f)
                s.line(-8f, sh, -2f, sh + 12f, 1.8f); s.line(8f, sh, 2f, sh + 12f, 1.8f)
            }
        }

        // head -----------------------------------------------------------------
        s.oval(0f, hy, r * 0.92f, r, 2.2f)
        if (!infant || true) { s.dot(-r * 0.36f, hy - r * 0.05f, 1.6f); s.dot(r * 0.36f, hy - r * 0.05f, 1.6f) }  // eyes
        s.line(0f, hy + r * 0.02f, r * 0.06f, hy + r * 0.3f, 1.3f, 0.7f)                                          // nose
        s.curve(-r * 0.3f, hy + r * 0.52f, 0f, hy + r * 0.75f, r * 0.3f, hy + r * 0.52f, 1.5f)                    // smile
        if (look.glasses) {
            s.oval(-r * 0.38f, hy - r * 0.05f, r * 0.27f, r * 0.24f, 1.5f); s.oval(r * 0.38f, hy - r * 0.05f, r * 0.27f, r * 0.24f, 1.5f)
            s.line(-r * 0.11f, hy - r * 0.07f, r * 0.11f, hy - r * 0.07f, 1.3f)
        }
        if (look.moustache) { s.curve(-r * 0.4f, hy + r * 0.42f, -r * 0.15f, hy + r * 0.28f, 0f, hy + r * 0.38f, 1.8f); s.curve(0f, hy + r * 0.38f, r * 0.15f, hy + r * 0.28f, r * 0.4f, hy + r * 0.42f, 1.8f) }
        if (look.beard) { s.curve(-r * 0.8f, hy + r * 0.2f, 0f, hy + r * 1.6f, r * 0.8f, hy + r * 0.2f, 1.6f); s.hatch(-r * 0.4f, hy + r * 0.95f, -r * 0.2f, hy + r * 1.25f, 5, r * 0.2f, 0f, 0.5f) }
        if (look.bindi) s.dot(0f, hy - r * 0.45f, 1.7f)
        if (look.wrinkles) { s.line(-r * 0.7f, hy - r * 0.5f, -r * 0.25f, hy - r * 0.55f, 1f, 0.45f); s.line(r * 0.25f, hy - r * 0.55f, r * 0.7f, hy - r * 0.5f, 1f, 0.45f); s.line(-r * 0.55f, hy + r * 0.3f, -r * 0.4f, hy + r * 0.45f, 1f, 0.4f) }
        if (elder && !look.glasses) { s.line(-r * 0.7f, hy - r * 0.18f, -r * 0.5f, hy - r * 0.12f, 1f, 0.4f) }
        s.oval(-r * 0.92f, hy + 1, 3.2f, 5f, 1.4f, 0.7f); s.oval(r * 0.92f, hy + 1, 3.2f, 5f, 1.4f, 0.7f)             // ears

        // hair -----------------------------------------------------------------
        val top = hy - r
        when (look.hair) {
            Hair.SHORT -> { s.curve(-r * 0.9f, hy - r * 0.2f, 0f, top - 9f, r * 0.9f, hy - r * 0.2f, 2.6f); s.hatch(-r * 0.6f, top + 3, -r * 0.3f, top - 1, 6, r * 0.22f, 0f, 0.55f) }
            Hair.SPIKY -> { var x = -r * 0.85f; while (x < r * 0.85f) { s.line(x, top + 6, x + 4f, top - 8f, 2f); s.line(x + 4f, top - 8f, x + 8f, top + 5, 2f); x += 8f } }
            Hair.PARTED -> { s.curve(-r * 0.95f, hy - r * 0.1f, -r * 0.2f, top - 10f, r * 0.9f, hy - r * 0.35f, 2.6f); s.line(-r * 0.25f, top - 2, -r * 0.1f, top + 12, 1.6f); s.hatch(-r * 0.7f, top + 4, -r * 0.45f, top, 5, r * 0.3f, 0f, 0.5f) }
            Hair.CURLY -> { var x = -r * 0.8f; while (x <= r * 0.81f) { s.oval(x, top + 2 - (if (x.toInt() % 2 == 0) 3f else 0f), 5f, 5f, 1.8f); x += 8f } }
            Hair.BUN -> { s.curve(-r * 0.9f, hy - r * 0.1f, 0f, top - 8f, r * 0.9f, hy - r * 0.1f, 2.4f); s.oval(0f, top - 8f, 7f, 6f, 2f); s.line(-r * 0.5f, top + 3, r * 0.5f, top + 3, 1f, 0.5f) }
            Hair.LONG -> { s.curve(-r * 0.95f, hy, 0f, top - 10f, r * 0.95f, hy, 2.6f); s.curve(-r * 0.95f, hy, -r * 1.35f, hy + r * 1.2f, -r * 1.1f, sh + 12, 2.2f); s.curve(r * 0.95f, hy, r * 1.35f, hy + r * 1.2f, r * 1.1f, sh + 12, 2.2f); s.line(0f, top - 2, -r * 0.3f, hy - r * 0.45f, 1.3f, 0.6f) }
            Hair.PONY -> { s.curve(-r * 0.9f, hy - r * 0.1f, 0f, top - 9f, r * 0.9f, hy - r * 0.1f, 2.4f); s.curve(r * 0.7f, top + 4, r * 1.9f, top - 2f, r * 1.3f, sh, 2.2f); s.dot(r * 0.8f, top + 5, 2f) }
            Hair.PIGTAILS -> { s.curve(-r * 0.9f, hy - r * 0.1f, 0f, top - 8f, r * 0.9f, hy - r * 0.1f, 2.4f); s.oval(-r * 1.25f, hy + 2, 5f, 11f, 2f); s.oval(r * 1.25f, hy + 2, 5f, 11f, 2f); s.dot(-r * 0.98f, hy - 6f, 2f); s.dot(r * 0.98f, hy - 6f, 2f) }
            Hair.BRAIDS -> { s.curve(-r * 0.9f, hy - r * 0.1f, 0f, top - 8f, r * 0.9f, hy - r * 0.1f, 2.4f); var y = hy; repeat(5) { s.oval(-r * 1.15f, y, 3.6f, 3.2f, 1.5f); s.oval(r * 1.15f, y, 3.6f, 3.2f, 1.5f); y += 6f } }
            Hair.WISPS -> { s.curve(-r * 0.8f, hy - r * 0.3f, 0f, top - 5f, r * 0.8f, hy - r * 0.3f, 1.5f, 0.6f); s.oval(r * 0.3f, top - 5f, 7f, 5f, 1.6f, 0.7f); s.line(-r * 0.9f, hy - 2, -r * 0.95f, hy + 8, 1.3f, 0.5f) }
            Hair.BALD -> { s.line(-r * 0.95f, hy - 2, -r * 0.9f, hy + 9, 1.8f, 0.7f); s.line(r * 0.95f, hy - 2, r * 0.9f, hy + 9, 1.8f, 0.7f) }
            Hair.TUFT -> { s.curve(-3f, top + 2, 0f, top - 10f, 4f, top + 1, 2f); s.curve(4f, top + 1, 8f, top - 6f, 9f, top + 3, 1.6f, 0.7f) }
            Hair.CAP -> { s.curve(-r * 1.0f, hy - r * 0.25f, 0f, top - 12f, r * 1.0f, hy - r * 0.25f, 2.8f); s.line(-r * 1.0f, hy - r * 0.25f, r * 1.5f, hy - r * 0.3f, 2.4f); s.hatch(-r * 0.6f, top - 2, -r * 0.2f, top - 6, 5, r * 0.3f, 0f, 0.4f) }
        }
        return s.pens
    }
}

/** Irregular stamp/paper edge, speckle texture and a hand-drawn frame, built once per person seed. */
class StickerGeometry(val outline: Path, val frame: Path, val dots: List<Triple<Float, Float, Float>>, val fibres: List<FloatArray>, val angle: Float) {
    companion object {
        const val W = 150f
        const val H = 190f
        private val cache = HashMap<Int, StickerGeometry>()
        fun of(seed: Int): StickerGeometry = cache.getOrPut(seed) { build(seed) }

        private fun build(seed: Int): StickerGeometry {
            val rnd = Random(seed)
            fun j(a: Float) = (rnd.nextFloat() - 0.5f) * 2f * a
            val x0 = -W / 2; val y0 = -H / 2; val x1 = W / 2; val y1 = H / 2
            val pts = ArrayList<Offset>(); val nrm = ArrayList<Offset>()
            fun side(sx: Float, sy: Float, ex: Float, ey: Float, nx: Float, ny: Float) {
                val n = (hypot(ex - sx, ey - sy) / 11f).toInt().coerceAtLeast(2)
                for (i in 0 until n) { pts += Offset(sx + (ex - sx) / n * i + j(1.3f), sy + (ey - sy) / n * i + j(1.3f)); nrm += Offset(nx, ny) }
            }
            side(x0, y0, x1, y0, 0f, 1f); side(x1, y0, x1, y1, -1f, 0f); side(x1, y1, x0, y1, 0f, -1f); side(x0, y1, x0, y0, 1f, 0f)
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in pts.indices) {
                val a = pts[i]; val b = pts[(i + 1) % pts.size]; val nv = nrm[i]
                val notch = 1.8f + rnd.nextFloat() * 2.4f
                path.quadraticBezierTo((a.x + b.x) / 2 + nv.x * notch, (a.y + b.y) / 2 + nv.y * notch, b.x, b.y)
            }
            path.close()
            val fx0 = -60f; val fy0 = -82f; val fx1 = 60f; val fy1 = 28f
            val frame = Path().apply {
                moveTo(fx0 + j(1f), fy0 + j(1f)); lineTo(fx1 + j(1f), fy0 + j(1f)); lineTo(fx1 + j(1f), fy1 + j(1f)); lineTo(fx0 + j(1f), fy1 + j(1f)); close()
            }
            val dots = List(46) { Triple(j(W / 2 - 4), j(H / 2 - 4), 0.35f + rnd.nextFloat() * 0.7f) }
            val fibres = List(7) { floatArrayOf(j(W / 2 - 15), j(H / 2 - 15), j(9f), j(5f)) }
            return StickerGeometry(path, frame, dots, fibres, j(2.2f))
        }
    }
}
