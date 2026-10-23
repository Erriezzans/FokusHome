package app.olauncher.helper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Current weather for a city name, from Open-Meteo (free, no account, no location permission). */
object WeatherHelper {
    data class Weather(val city: String, val temp: Int, val code: Int, val isDay: Boolean, val time: Long)

    const val MAX_AGE_MS = 30 * 60 * 1000L

    /** Blocking network call: run it off the main thread. Returns null when anything fails. */
    fun fetch(city: String): Weather? {
        return try {
            val geo = JSONObject(
                get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city, "UTF-8"))
            )
            val place = geo.optJSONArray("results")?.optJSONObject(0) ?: return null
            val lat = place.getDouble("latitude")
            val lon = place.getDouble("longitude")
            val forecast = JSONObject(
                get(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                            "&current=temperature_2m,weather_code,is_day"
                )
            )
            val current = forecast.getJSONObject("current")
            Weather(
                city = city,
                temp = Math.round(current.getDouble("temperature_2m")).toInt(),
                code = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) == 1,
                time = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun get(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun encode(w: Weather) = "${w.city.replace("|", " ")}|${w.temp}|${w.code}|${if (w.isDay) 1 else 0}|${w.time}"

    fun decode(text: String): Weather? {
        val parts = text.split("|")
        if (parts.size != 5) return null
        return try {
            Weather(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3] == "1", parts[4].toLong())
        } catch (e: Exception) {
            null
        }
    }

    /** WMO weather code to a small emoji. */
    fun emoji(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1, 2 -> if (isDay) "🌤️" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67 -> "🌧️"
        in 71..77 -> "🌨️"
        in 80..82 -> "🌧️"
        85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }
}
