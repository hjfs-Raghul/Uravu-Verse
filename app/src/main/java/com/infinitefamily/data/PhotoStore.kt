package com.infinitefamily.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

/** Profile photos live in app-private storage. Only a small, downsized JPEG is ever kept. */
class PhotoStore(private val context: Context) {
    private val dir get() = File(context.filesDir, "photos").also { it.mkdirs() }

    fun file(name: String) = File(dir, name)
    fun read(name: String): ByteArray? = file(name).takeIf { it.isFile }?.readBytes()
    fun delete(name: String) { file(name).delete() }

    /** Decodes, rotates (EXIF), downsizes to 768px and stores as JPEG. Returns the new file name or null. */
    fun importFromUri(uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1536 || bounds.outHeight / sample > 1536) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = 768f / maxOf(bmp.width, bmp.height)
        val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rotation != 0f) postRotate(rotation) }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val name = UUID.randomUUID().toString() + ".jpg"
        file(name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        name
    }.getOrNull()

    /** Decodes a photo near [maxPx] for display. */
    fun decode(name: String, maxPx: Int): Bitmap? = runCatching {
        val f = file(name)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Removes photo files that no profile references (e.g. after a delete was not undone). */
    fun cleanupOrphans(referenced: Set<String>) {
        dir.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
    }

    /** Replaces the whole photo directory (restore). New files are written first and swapped in. */
    fun replaceAll(photos: Map<String, ByteArray>) {
        val staging = File(context.filesDir, "photos_staging").also { it.deleteRecursively(); it.mkdirs() }
        photos.forEach { (n, b) -> File(staging, n).writeBytes(b) }
        File(context.filesDir, "photos").deleteRecursively()
        if (!staging.renameTo(File(context.filesDir, "photos"))) {
            staging.copyRecursively(File(context.filesDir, "photos"), overwrite = true); staging.deleteRecursively()
        }
    }
}
