package app.olauncher.helper

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import app.olauncher.data.AppModel
import java.util.concurrent.Executors

/** Loads launcher icons off the main thread and caches them. */
object AppIconLoader {
    private val cache = LruCache<String, Drawable>(400)
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(view: ImageView, app: AppModel) {
        val key = keyOf(app)
        view.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageDrawable(cached)
            return
        }
        view.setImageDrawable(null)
        val context = view.context.applicationContext
        executor.execute {
            val icon = try {
                loadIcon(context, app)
            } catch (e: Exception) {
                null
            }
            if (icon != null) {
                cache.put(key, icon)
                mainHandler.post { if (view.tag == key) view.setImageDrawable(icon) }
            }
        }
    }

    fun clear() = cache.evictAll()

    private fun keyOf(app: AppModel): String {
        val activity = (app as? AppModel.App)?.activityClassName.orEmpty()
        return app.appPackage + "|" + activity + "|" + app.user.toString()
    }

    private fun loadIcon(context: Context, app: AppModel): Drawable? {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activities = launcherApps.getActivityList(app.appPackage, app.user)
        val className = (app as? AppModel.App)?.activityClassName
        val info = activities.firstOrNull { it.componentName.className == className }
            ?: activities.firstOrNull()
        return info?.getBadgedIcon(0)
            ?: context.packageManager.getApplicationIcon(app.appPackage)
    }
}
