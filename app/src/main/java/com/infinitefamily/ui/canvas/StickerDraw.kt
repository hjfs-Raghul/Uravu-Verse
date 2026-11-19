package com.infinitefamily.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.infinitefamily.domain.*
import com.infinitefamily.ui.HandFamily
import com.infinitefamily.ui.Pencil

private val unit1 = Density(1f, 1f)
private val greyFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private val softFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.3f) })

class StickerRenderer(val measurer: TextMeasurer, val photos: PhotoCache) {
    private fun text(s: String, size: Float, color: Color, maxLines: Int, width: Float): TextLayoutResult =
        measurer.measure(
            text = s,
            style = TextStyle(fontFamily = HandFamily, fontSize = size.sp, color = color, textAlign = TextAlign.Center, lineHeight = (size * 1.08f).sp),
            overflow = TextOverflow.Ellipsis, maxLines = maxLines,
            constraints = Constraints(maxWidth = width.toInt()),
            density = unit1,
        )

    /** Draws only Name and Location on the sticker; everything else is revealed when it is tapped. */
    fun DrawScope.sticker(
        p: Person, category: StickerCategory, x: Float, y: Float, boost: Float,
        selected: Boolean, pulse: Float, onPath: Boolean, showLocation: Boolean,
    ) {
        val g = StickerGeometry.of(p.id.hashCode())
        val style = StickerStyles.forPerson(p)
        val a = style.alpha
        withTransform({ translate(x, y); rotate(g.angle, Offset.Zero) }) {
            // very light offset shadow, no gradients
            withTransform({ translate(1.8f, 2.4f) }) { drawPath(g.outline, Color.Black.copy(alpha = 0.07f * a)) }
            drawPath(g.outline, Pencil.Sticker.copy(alpha = a))
            for ((dx, dy, r) in g.dots) drawCircle(Pencil.Grey.copy(alpha = 0.18f * a), r, Offset(dx, dy))
            for (f in g.fibres) drawLine(Pencil.LightGrey.copy(alpha = 0.35f * a), Offset(f[0], f[1]), Offset(f[0] + f[2], f[1] + f[3]), 0.7f)
            drawPath(g.outline, Pencil.Grey.copy(alpha = 0.75f * a), style = Stroke(1.3f, join = StrokeJoin.Round))

            // picture area
            val photo = p.photoFile?.let { photos.get(it) }
            val fx = -60f; val fy = -82f; val fw = 120f; val fh = 110f
            if (photo != null) {
                // crop to the frame aspect ratio
                val cw = if (photo.width.toFloat() / photo.height > fw / fh) (photo.height * fw / fh).toInt() else photo.width
                val ch = if (photo.width.toFloat() / photo.height > fw / fh) photo.height else (photo.width * fh / fw).toInt()
                drawImage(
                    photo, srcOffset = IntOffset((photo.width - cw) / 2, (photo.height - ch) / 2), srcSize = IntSize(cw, ch),
                    dstOffset = IntOffset(fx.toInt(), fy.toInt()), dstSize = IntSize(fw.toInt(), fh.toInt()),
                    alpha = a, colorFilter = if (style.desaturate) greyFilter else softFilter,
                )
            } else {
                clipRect(fx, fy, fx + fw, fy + fh) {
                    withTransform({ translate(0f, -27f) }) {
                        for (pen in Sketches.pens(category, p.stickerVariant)) {
                            val c = Pencil.Graphite.copy(alpha = pen.alpha * a)
                            if (pen.fill) drawPath(pen.path, c) else drawPath(pen.path, c, style = Stroke(pen.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
            drawPath(g.frame, Pencil.Graphite.copy(alpha = 0.8f * a), style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // name + location (simple handwriting, not cursive); text grows when zoomed far out
            val nameSize = 17f * boost
            val name = text(p.name, nameSize, Pencil.Graphite.copy(alpha = a), 2, 140f)
            val nameTop = 36f
            drawText(name, topLeft = Offset(-name.size.width / 2f, nameTop))
            if (showLocation && p.location.isNotBlank()) {
                val loc = text(p.location, 13f * boost.coerceAtMost(1.6f), Pencil.Grey.copy(alpha = a), 1, 140f)
                drawText(loc, topLeft = Offset(-loc.size.width / 2f, minOf(nameTop + name.size.height + 1f, 84f)))
            }
            if (onPath) drawPath(g.outline, Pencil.Red.copy(alpha = 0.5f), style = Stroke(5f, join = StrokeJoin.Round))
            if (selected) withTransform({ scale(1.05f, 1.05f, Offset.Zero) }) { drawPath(g.outline, Pencil.Red, style = Stroke(4f, join = StrokeJoin.Round)) }
            if (pulse > 0f) withTransform({ scale(1f + 0.08f * pulse, 1f + 0.06f * pulse, Offset.Zero) }) {
                drawPath(g.outline, Pencil.Red.copy(alpha = 1f - 0.6f * pulse), style = Stroke(6f, join = StrokeJoin.Round))
            }
        }
    }
}
