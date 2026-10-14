package app.olauncher.helper

import android.app.WallpaperManager
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
    /** Colors shown in the "Color" sheet (white first, like the tile next to the color picker). */
    val PALETTE = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFF1EFEB.toInt(), 0xFF99EEFF.toInt(), 0xFF00CCFF.toInt(),
        0xFFD3E82E.toInt(), 0xFF92D92F.toInt(), 0xFF8F9663.toInt(), 0xFF0EA68D.toInt(),
        0xFF2F4F4A.toInt(), 0xFFFFEE00.toInt(), 0xFF838C70.toInt(), 0xFFF9B228.toInt(),
        0xFFFF6150.toInt(), 0xFFC772E8.toInt(), 0xFF9910FF.toInt(), 0xFFEEE3DE.toInt(),
        0xFF5B2A86.toInt(), 0xFF3E2723.toInt(), 0xFF1A237E.toInt(), 0xFFB71C1C.toInt(),
        0xFFF06292.toInt(), 0xFF4DB6AC.toInt(), 0xFF212121.toInt(), 0xFF000000.toInt(),
    )

    /** Small swatches inside the color picker. */
    val PRESETS = intArrayOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF6A4C9C.toInt(), 0xFF4B5FAB.toInt(),
        0xFF3B93C6.toInt(), 0xFF17DCD1.toInt(), 0xFF1DB345.toInt(), 0xFFADC94B.toInt(),
        0xFFFFC70F.toInt(), 0xFFFF9A0D.toInt(), 0xFFFF6F43.toInt(), 0xFFF63B63.toInt(),
        0xFFEF2E45.toInt(), 0xFFC91F33.toInt(),
    )

    const val RESULT_FAILED = 0
    const val RESULT_OK = 1
    const val RESULT_OK_PHONE_NOT_CHANGED = 2

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

    /** Sets the phone's home screen and lock screen wallpaper. Heavy: call off the main thread. */
    fun setSystemBitmap(context: Context, bitmap: Bitmap): Boolean {
        return try {
            val manager = WallpaperManager.getInstance(context)
            manager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_SYSTEM)
            manager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_LOCK)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Plain color as the phone's home screen and lock screen wallpaper. */
    fun setSystemColor(context: Context, color: Int): Boolean {
        val manager = WallpaperManager.getInstance(context)
        val metrics = context.resources.displayMetrics
        val width = manager.desiredMinimumWidth.takeIf { it > 0 } ?: metrics.widthPixels
        val height = manager.desiredMinimumHeight.takeIf { it > 0 } ?: metrics.heightPixels
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return setSystemBitmap(context, bitmap)
    }

    /**
     * Copies a picked image into the app (cropped to the screen shape), switches to it and also sets
     * it as the phone wallpaper. Heavy: call it off the main thread. Returns one of the RESULT_ values.
     */
    fun saveImage(context: Context, uri: Uri, prefs: Prefs): Int {
        val resolver = context.contentResolver
        val metrics = context.resources.displayMetrics
        val targetW = metrics.widthPixels
        val targetH = metrics.heightPixels

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return RESULT_FAILED

        // Load a version that is not much bigger than the screen
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return RESULT_FAILED

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
        return if (setSystemBitmap(context, result)) RESULT_OK else RESULT_OK_PHONE_NOT_CHANGED
    }
}
