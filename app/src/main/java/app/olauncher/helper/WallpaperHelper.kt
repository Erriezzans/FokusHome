package app.olauncher.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import androidx.core.graphics.ColorUtils
import app.olauncher.data.Prefs
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * FokusHome's own background: a plain color or a picked image.
 * It only changes the launcher, never the phone's wallpaper or lock screen.
 */
object WallpaperHelper {
    /** black, red, white, yellow, blue */
    val PALETTE = intArrayOf(
        0xFF000000.toInt(),
        0xFFD32F2F.toInt(),
        0xFFFFFFFF.toInt(),
        0xFFFFD600.toInt(),
        0xFF2962FF.toInt(),
    )

    private const val FILE_NAME = "wallpaper.jpg"
    private var cachedImage: Bitmap? = null
    private var cachedVersion = -1L

    fun isLight(color: Int): Boolean = ColorUtils.calculateLuminance(color) > 0.5

    /** The background to draw, or null to keep the default (phone wallpaper showing through). */
    fun background(context: Context, prefs: Prefs): Drawable? {
        return when (prefs.wallpaperMode) {
            1 -> ColorDrawable(prefs.wallpaperColor)
            2 -> loadImage(context, prefs.wallpaperVersion)?.let { BitmapDrawable(context.resources, it) }
            else -> null
        }
    }

    private fun loadImage(context: Context, version: Long): Bitmap? {
        if (cachedImage != null && cachedVersion == version) return cachedImage
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        cachedImage = bitmap
        cachedVersion = version
        return bitmap
    }

    /**
     * Copies a picked image into the app (cropped to the screen shape) and switches to it.
     * Heavy: call it off the main thread. Returns false when the image could not be used.
     */
    fun saveImage(context: Context, uri: Uri, prefs: Prefs): Boolean {
        val resolver = context.contentResolver
        val metrics = context.resources.displayMetrics
        val targetW = metrics.widthPixels
        val targetH = metrics.heightPixels

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        // Load a version that is not much bigger than the screen
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return false

        // Photos can be stored rotated
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val upright = if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else {
            decoded
        }

        // Fill the whole screen: scale up and crop the overflow from the centre
        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val scale = max(targetW / upright.width.toFloat(), targetH / upright.height.toFloat())
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate((targetW - upright.width * scale) / 2f, (targetH - upright.height * scale) / 2f)
        }
        Canvas(result).drawBitmap(upright, matrix, Paint(Paint.FILTER_BITMAP_FLAG))

        FileOutputStream(File(context.filesDir, FILE_NAME)).use {
            result.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }

        // Average brightness decides between light and dark text
        val tiny = Bitmap.createScaledBitmap(result, 8, 8, true)
        var total = 0.0
        for (x in 0 until 8) for (y in 0 until 8) total += ColorUtils.calculateLuminance(tiny.getPixel(x, y))
        val light = total / 64.0 > 0.5

        val version = System.currentTimeMillis()
        cachedImage = result
        cachedVersion = version
        prefs.wallpaperIsLight = light
        prefs.wallpaperVersion = version
        prefs.wallpaperMode = 2
        return true
    }
}
