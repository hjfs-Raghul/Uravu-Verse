package com.infinitefamily.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinitefamily.ui.HandFamily
import com.infinitefamily.ui.Pencil

private data class Sample(val label: String, val color: Color, val width: Float, val effect: PathEffect?, val wavy: Boolean = false, val fade: Boolean = false)

/** Small, collapsible key to the line styles. Collapsed by default so the sheet stays clean. */
@Composable
fun Legend(open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val dashed = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
    val dotted = PathEffect.dashPathEffect(floatArrayOf(2f, 7f))
    val items = listOf(
        Sample("Parent – child", Pencil.Graphite, 4f, null),
        Sample("Adoptive", Pencil.Slate, 4f, dashed),
        Sample("Step-parent", Pencil.Olive, 4f, dotted),
        Sample("Current marriage", Pencil.Red, 6f, null),
        Sample("Divorced / previous", Pencil.LightGrey, 4f, null),
        Sample("Half-siblings", Pencil.Grey, 3f, null, wavy = true),
        Sample("Deceased (faded sticker)", Pencil.LightGrey, 0f, null, fade = true),
    )
    Column(
        modifier
            .background(Pencil.Paper.copy(alpha = 0.94f))
            .border(1.dp, Pencil.LightGrey)
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(if (open) "Legend  –" else "Legend  +", style = TextStyle(fontFamily = HandFamily, fontSize = 14.sp, color = Pencil.Grey))
        if (open) items.forEach { s ->
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 44.dp, height = 12.dp)) {
                    val y = size.height / 2
                    if (s.fade) {
                        drawRect(Pencil.LightGrey, Offset(4f, 0f), androidx.compose.ui.geometry.Size(size.width - 8f, size.height), style = Stroke(2f))
                    } else if (s.wavy) {
                        val p = Path().apply { moveTo(0f, y); for (i in 0 until 4) quadraticBezierTo(i * size.width / 4 + size.width / 8, if (i % 2 == 0) 0f else size.height, (i + 1) * size.width / 4, y) }
                        drawPath(p, s.color, style = Stroke(s.width, cap = StrokeCap.Round))
                    } else drawLine(s.color, Offset(0f, y), Offset(size.width, y), s.width, StrokeCap.Round, s.effect)
                }
                Spacer(Modifier.width(8.dp))
                Text(s.label, style = TextStyle(fontFamily = HandFamily, fontSize = 13.sp, color = Pencil.Graphite))
            }
        }
    }
}
