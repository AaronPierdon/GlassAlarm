package com.aaron.glassalarm.data

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class HourForecast(val label: String, val temp: Int, val code: Int)

data class WeatherNow(
    val temp: Int,
    val feelsLike: Int,
    val code: Int,
    val isDay: Boolean,
    val high: Int,
    val low: Int,
    val precipChance: Int,
    val sunrise: String,
    val sunset: String,
    val place: String,
    val unit: String,
    val hourly: List<HourForecast>,
    val fetchedAt: Long,
) {
    val emoji: String get() = Weather.emoji(code, isDay)
    val description: String get() = Weather.describe(code)
}

/** Free, key-less weather from Open-Meteo. Everything is cached so the alarm screen renders instantly offline. */
object Weather {

    fun cached(ctx: Context): WeatherNow? = Prefs(ctx).weatherCache?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    fun refreshIfStale(ctx: Context, maxAgeMinutes: Int): WeatherNow? {
        val prefs = Prefs(ctx)
        val c = cached(ctx)
        val unit = if (prefs.fahrenheit) "F" else "C"
        if (c != null && c.unit == unit && System.currentTimeMillis() - c.fetchedAt < maxAgeMinutes * 60_000L) return c
        return refresh(ctx) ?: c
    }

    /** Blocking network call — call from a background thread. */
    fun refresh(ctx: Context): WeatherNow? {
        val prefs = Prefs(ctx)
        val (lat, lon, place) = resolveLocation(ctx, prefs) ?: return null.also { EventLog.add(ctx, "weather: no location (grant location or set a city)") }
        val unit = if (prefs.fahrenheit) "fahrenheit" else "celsius"
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max" +
            "&temperature_unit=$unit&timezone=auto&forecast_days=2"
        return runCatching {
            val o = JSONObject(httpGet(url))
            o.put("_place", place)
            o.put("_unit", if (prefs.fahrenheit) "F" else "C")
            o.put("_fetchedAt", System.currentTimeMillis())
            prefs.weatherCache = o.toString()
            parse(o)
        }.onFailure { EventLog.add(ctx, "weather fetch failed: ${it.message}") }.getOrNull()
    }

    private fun resolveLocation(ctx: Context, prefs: Prefs): Triple<Double, Double, String>? {
        val city = prefs.weatherCity.trim()
        if (city.isNotEmpty()) {
            val lat = prefs.cityLat
            val lon = prefs.cityLon
            if (lat != null && lon != null) return Triple(lat, lon, city)
            return runCatching {
                val q = URLEncoder.encode(city, "UTF-8")
                val r = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json"))
                val first = r.getJSONArray("results").getJSONObject(0)
                val la = first.getDouble("latitude")
                val lo = first.getDouble("longitude")
                prefs.cityLat = la
                prefs.cityLon = lo
                Triple(la, lo, first.optString("name", city))
            }.getOrNull()
        }
        if (!CalendarRepoPerms.has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val loc = lastKnown(ctx) ?: return null
        return Triple(loc.latitude, loc.longitude, placeName(ctx, loc))
    }

    @Suppress("MissingPermission")
    private fun lastKnown(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER, "fused")
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    private fun placeName(ctx: Context, loc: Location): String = runCatching {
        val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
        a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: ""
    }.getOrDefault("")

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(o: JSONObject): WeatherNow {
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        val hourly = o.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val nowHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        var start = 0
        for (i in 0 until times.length()) {
            val t = runCatching { LocalDateTime.parse(times.getString(i)) }.getOrNull() ?: continue
            if (!t.isBefore(nowHour)) { start = i; break }
        }
        val labelFmt = DateTimeFormatter.ofPattern("h a", Locale.US)
        val hours = (0 until 4).mapNotNull { k ->
            val i = start + k * 3
            if (i >= times.length()) null
            else HourForecast(
                label = runCatching { LocalDateTime.parse(times.getString(i)).format(labelFmt) }.getOrDefault(""),
                temp = temps.optDouble(i).toInt(),
                code = codes.optInt(i),
            )
        }
        val hhmm = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        fun dailyTime(key: String) = runCatching { LocalDateTime.parse(daily.getJSONArray(key).getString(0)).format(hhmm) }.getOrDefault("")
        return WeatherNow(
            temp = cur.optDouble("temperature_2m").toInt(),
            feelsLike = cur.optDouble("apparent_temperature").toInt(),
            code = cur.optInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            high = daily.getJSONArray("temperature_2m_max").optDouble(0).toInt(),
            low = daily.getJSONArray("temperature_2m_min").optDouble(0).toInt(),
            precipChance = daily.optJSONArray("precipitation_probability_max")?.optInt(0) ?: 0,
            sunrise = dailyTime("sunrise"),
            sunset = dailyTime("sunset"),
            place = o.optString("_place"),
            unit = o.optString("_unit", "F"),
            hourly = hours,
            fetchedAt = o.optLong("_fetchedAt"),
        )
    }

    fun emoji(code: Int, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1, 2 -> if (isDay) "🌤️" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..65 -> "Rain"
        66, 67 -> "Freezing rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorms"
        else -> "—"
    }
}

internal object CalendarRepoPerms {
    fun has(ctx: Context, perm: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
