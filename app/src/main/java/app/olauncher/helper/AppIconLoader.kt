package app.olauncher.helper

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
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
            for (app in snapshot) {
                val key = keyOf(app, currentShape)
                if (cache.get(key) != null) continue
                createAndCache(appContext, app, currentShape, key)
            }
        }
    }

    fun clear() = cache.evictAll()

    private fun keyOf(app: AppModel, shape: Int): String {
        val activity = (app as? AppModel.App)?.activityClassName.orEmpty()
        return app.appPackage + "|" + activity + "|" + app.user.toString() + "|" + shape
    }

    private fun createAndCache(context: Context, app: AppModel, shape: Int, key: String): Bitmap? {
        return try {
            val drawable = loadDrawable(context, app) ?: return null
            val sizePx = (48 * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(96)
            val bitmap = render(drawable, sizePx, shape)
            cache.put(key, bitmap)
            bitmap
        } catch (e: Exception) {
            null
        }
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

        // Cut everything outside the chosen shape (anti-aliased edge)
        val path = Path()
        if (shape == SHAPE_ROUNDED) {
            val radius = s * 0.3f
            path.addRoundRect(RectF(0f, 0f, s, s), radius, radius, Path.Direction.CW)
        } else {
            path.addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW)
        }
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawPath(path, mask)
        return bitmap
    }
}
