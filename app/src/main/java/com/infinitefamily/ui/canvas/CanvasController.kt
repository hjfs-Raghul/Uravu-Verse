package com.infinitefamily.ui.canvas

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import com.infinitefamily.data.PhotoStore
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** Pan / zoom state of the infinite sheet. World units are dp; screen = world * scale * density + offset. */
@Stable
class CanvasController {
    var scale by mutableFloatStateOf(0.8f)
    var offset by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    var density = 1f
    var initialised = false

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val ns = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val f = ns / scale
        offset = centroid - (centroid - offset) * f + pan
        scale = ns
    }

    fun screenToWorld(p: Offset): Offset = (p - offset) / (scale * density)

    private fun offsetFor(world: Offset, s: Float) =
        Offset(size.width / 2f, size.height / 2f) - world * (s * density)

    suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
        val s0 = scale; val o0 = offset
        animate(0f, 1f, animationSpec = tween(420)) { t, _ ->
            scale = lerp(s0, targetScale, t)
            offset = Offset(lerp(o0.x, targetOffset.x, t), lerp(o0.y, targetOffset.y, t))
        }
    }

    suspend fun focus(world: Offset, targetScale: Float? = null) {
        if (size.width == 0) return
        val s = (targetScale ?: max(scale, 0.7f)).coerceIn(MIN_SCALE, MAX_SCALE)
        animateTo(s, offsetFor(world, s))
    }

    /** Fit a world rectangle [left, top, right, bottom] on screen. */
    suspend fun fit(bounds: FloatArray) {
        if (size.width == 0) return
        val bw = max(bounds[2] - bounds[0], 1f); val bh = max(bounds[3] - bounds[1], 1f)
        val s = (min(size.width / (bw * density), size.height / (bh * density)) * 0.88f).coerceIn(MIN_SCALE, 1.2f)
        animateTo(s, offsetFor(Offset((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2), s))
    }

    /** Immediate version used for the first frame. */
    fun jumpTo(world: Offset, s: Float) { scale = s; offset = offsetFor(world, s) }

    companion object { const val MIN_SCALE = 0.06f; const val MAX_SCALE = 3.5f }
}

/** Loads small photo bitmaps off the main thread and keeps a bounded cache. */
class PhotoCache(private val store: PhotoStore, private val scope: CoroutineScope) {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(80) {}
    private val loading: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val version = mutableIntStateOf(0)

    fun get(name: String): ImageBitmap? {
        version.intValue // subscribe the drawing code to updates
        val hit = cache.get(name)
        if (hit != null) return hit
        if (loading.add(name)) scope.launch(Dispatchers.IO) {
            val bmp = store.decode(name, 320)?.asImageBitmap()
            if (bmp != null) cache.put(name, bmp)
            loading.remove(name)
            if (bmp != null) version.intValue++
        }
        return null
    }
}
