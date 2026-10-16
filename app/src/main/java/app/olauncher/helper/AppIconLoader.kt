package app.olauncher.helper

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import app.olauncher.data.AppModel
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Loads launcher icons off the main thread, redraws every icon into the same shape
 * (circle or rounded square) and keeps the finished bitmaps in a cache.
 * Pre-rendered bitmaps are much cheaper to draw than adaptive icons, which keeps scrolling smooth.
 */
object AppIconLoader {
    const val SHAPE_CIRCLE = 0
    const val SHAPE_ROUNDED = 1

    /** Current icon shape. Part of the cache key, so changing it never shows stale icons. */
    @Volatile
    var shape: Int = SHAPE_CIRCLE

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val preloadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable).apply { priority = Thread.MIN_PRIORITY }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(view: ImageView, app: AppModel) {
        val currentShape = shape
        val key = keyOf(app, currentShape)
        view.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        val context = view.context.applicationContext
        executor.execute {
            val bitmap = createAndCache(context, app, currentShape, key)
            if (bitmap != null) {
                mainHandler.post { if (view.tag == key) view.setImageBitmap(bitmap) }
            }
        }
    }

    /** Renders every icon in the background so scrolling never has to wait for one. */
    fun preload(context: Context, apps: List<AppModel>) {
        val appContext = context.applicationContext
        val currentShape = shape
        val snapshot = apps.filter { it.appPackage.isNotEmpty() }
        preloadExecutor.execute {
            trimDiskCache(appContext)
            for (app in snapshot) {
                val key = keyOf(app, currentShape)
                if (cache.get(key) != null) continue
                createAndCache(appContext, app, currentShape, key)
            }
        }
    }

    fun clear() = cache.evictAll()

    /** Removes saved icons when there are far more files than apps (old versions of updated apps). */
    private fun trimDiskCache(context: Context) {
        val dir = File(context.cacheDir, "icons")
        val files = dir.listFiles() ?: return
        if (files.size > 1500) files.forEach { it.delete() }
    }

    private fun keyOf(app: AppModel, shape: Int): String {
        val activity = (app as? AppModel.App)?.activityClassName.orEmpty()
        return app.appPackage + "|" + activity + "|" + app.user.toString() + "|" + shape
    }

    private fun createAndCache(context: Context, app: AppModel, shape: Int, key: String): Bitmap? {
        return try {
            // 1. Finished icon saved on the phone earlier: no drawing needed
            val file = iconFile(context, app, shape)
            if (file.exists()) {
                val saved = BitmapFactory.decodeFile(file.path)
                if (saved != null) {
                    cache.put(key, saved)
                    return saved
                }
            }
            // 2. Draw it once, then keep it for next time
            val drawable = loadDrawable(context, app) ?: return null
            val sizePx = (48 * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(96)
            val bitmap = render(drawable, sizePx, shape)
            cache.put(key, bitmap)
            try {
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Exception) {
                file.delete()
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /** The file name changes when the app is updated (new apk path), so an updated icon is never stale. */
    private fun iconFile(context: Context, app: AppModel, shape: Int): File {
        val dir = File(context.cacheDir, "icons")
        if (!dir.exists()) dir.mkdirs()
        val source = try {
            context.packageManager.getApplicationInfo(app.appPackage, 0).sourceDir
        } catch (e: Exception) {
            ""
        }
        val raw = keyOf(app, shape) + "|" + source
        val hash = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.png")
    }

    private fun loadDrawable(context: Context, app: AppModel): Drawable? {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activities = launcherApps.getActivityList(app.appPackage, app.user)
        val className = (app as? AppModel.App)?.activityClassName
        val info = activities.firstOrNull { it.componentName.className == className }
            ?: activities.firstOrNull()
        val density = context.resources.displayMetrics.densityDpi
        return info?.getBadgedIcon(density)
            ?: context.packageManager.getApplicationIcon(app.appPackage)
    }

    private fun render(drawable: Drawable, size: Int, shape: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size.toFloat()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            // Only the inner 72/108 of an adaptive icon is the visible area: scale it to fill the tile
            val layerSize = (s * 108f / 72f).roundToInt()
            val offset = -((layerSize - size) / 2)
            val background = drawable.background
            if (background == null) canvas.drawColor(Color.WHITE)
            background?.let {
                it.setBounds(offset, offset, offset + layerSize, offset + layerSize)
                it.draw(canvas)
            }
            drawable.foreground?.let {
                it.setBounds(offset, offset, offset + layerSize, offset + layerSize)
                it.draw(canvas)
            }
        } else {
            // Old-style icon: put it on a plain tile so it matches the others
            canvas.drawColor(Color.WHITE)
            val inset = (s * 0.14f).roundToInt()
            drawable.setBounds(inset, inset, size - inset, size - inset)
            drawable.draw(canvas)
        }

        // Clear everything outside the chosen shape (anti-aliased edge).
        // A path drawn with CLEAR only touches its own pixels, so the path is inverted.
        val path = Path()
        if (shape == SHAPE_ROUNDED) {
            val radius = s * 0.3f
            path.addRoundRect(RectF(0f, 0f, s, s), radius, radius, Path.Direction.CW)
        } else {
            path.addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW)
        }
        path.fillType = Path.FillType.INVERSE_WINDING
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawPath(path, mask)
        return bitmap
    }
}
