package com.infinitefamily.ui

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** White paper, graphite, pencil grey. One red pencil colour is kept for "current / selected". */
object Pencil {
    val Paper = Color(0xFFFFFFFF)
    val Sticker = Color(0xFFF6F5F1)
    val Graphite = Color(0xFF2F2F2F)
    val Grey = Color(0xFF6A6A6A)
    val LightGrey = Color(0xFFB5B5B3)
    val Faint = Color(0xFFE7E7E4)
    val Red = Color(0xFFA23B2C)       // current marriage, selection
    val Slate = Color(0xFF3F6585)     // adoptive
    val Olive = Color(0xFF7A6A3A)     // step
}

/**
 * Android's built-in "casual" family (Coming Soon): a simple, non-cursive handwriting face that ships with
 * the system, so no font file has to be bundled. Falls back to the default sans-serif.
 */
val HandFamily: FontFamily by lazy {
    runCatching { FontFamily(Typeface.create("casual", Typeface.NORMAL)) }.getOrDefault(FontFamily.SansSerif)
}

@Composable
fun FamilyTreeTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = Pencil.Graphite, onPrimary = Pencil.Paper,
        secondary = Pencil.Red, onSecondary = Pencil.Paper,
        background = Pencil.Paper, onBackground = Pencil.Graphite,
        surface = Pencil.Paper, onSurface = Pencil.Graphite,
        surfaceVariant = Color(0xFFF1F1EE), onSurfaceVariant = Pencil.Grey,
        outline = Pencil.Grey, outlineVariant = Pencil.LightGrey,
        primaryContainer = Pencil.Faint, onPrimaryContainer = Pencil.Graphite,
        secondaryContainer = Color(0xFFF3E4E0), onSecondaryContainer = Pencil.Red,
        error = Pencil.Red,
    )
    val typo = Typography(
        titleLarge = TextStyle(fontFamily = HandFamily, fontSize = 24.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontFamily = HandFamily, fontSize = 19.sp, fontWeight = FontWeight.Bold),
        titleSmall = TextStyle(fontFamily = HandFamily, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        labelLarge = TextStyle(fontFamily = HandFamily, fontSize = 15.sp, fontWeight = FontWeight.Bold),
    )
    MaterialTheme(colorScheme = scheme, typography = typo, content = content)
}
