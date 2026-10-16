package app.olauncher.helper

import android.content.Context
import android.os.UserHandle
import android.os.UserManager
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Collator

/**
 * Remembers the last app list on the phone, so the launcher can show it at once
 * after Android closed it, while the real list is loaded again quietly in the background.
 */
object AppListCache {
    private const val FILE_NAME = "applist_cache.json"

    /** Different for normal mode and for each set of focus apps, so a stale list is never shown. */
    fun signature(prefs: Prefs): String {
        return if (prefs.focusModeEnabled) "focus:" + prefs.focusApps.sorted().joinToString(",") else "all"
    }

    fun save(context: Context, apps: List<AppModel>, signature: String) {
        try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val array = JSONArray()
            for (app in apps) {
                if (app !is AppModel.App) continue
                array.put(
                    JSONObject()
                        .put("l", app.appLabel)
                        .put("p", app.appPackage)
                        .put("c", app.activityClassName ?: "")
                        .put("u", userManager.getSerialNumberForUser(app.user))
                )
            }
            val json = JSONObject().put("sig", signature).put("apps", array)
            File(context.filesDir, FILE_NAME).writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun load(context: Context, signature: String): List<AppModel>? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            if (json.getString("sig") != signature) return null

            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val collator = Collator.getInstance()
            val users = HashMap<Long, UserHandle?>()
            val array = json.getJSONArray("apps")
            val result = ArrayList<AppModel>(array.length())
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val serial = item.getLong("u")
                if (!users.containsKey(serial)) users[serial] = userManager.getUserForSerialNumber(serial)
                val user = users[serial] ?: continue
                val label = item.getString("l")
                result.add(
                    AppModel.App(
                        appLabel = label,
                        key = collator.getCollationKey(label),
                        appPackage = item.getString("p"),
                        activityClassName = item.getString("c").ifEmpty { null },
                        isNew = false,
                        user = user
                    )
                )
            }
            result
        } catch (e: Exception) {
            null
        }
    }
}
