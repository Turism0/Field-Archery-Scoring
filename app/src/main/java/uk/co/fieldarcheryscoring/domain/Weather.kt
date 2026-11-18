package uk.co.fieldarcheryscoring.domain

import java.util.Locale
import kotlin.math.roundToInt

/*
 * Weather for a round (3.2.17, Pete). The server looks it up from Open-Meteo using the shoot location's map pin and the
 * round's date and times (the phone's position is never sent), and phones download it. Same as the web app's
 * domain/weather.ts. Tested in AnalysisTest.kt.
 */

/** Averages over the round: temperature °C, wind km/h, the strongest gust km/h, rain mm in total, WMO weather code. */
data class Weather(
    val tempC: Double? = null,
    val windKph: Double? = null,
    val gustKph: Double? = null,
    val rainMm: Double? = null,
    val code: Int? = null,
) {
    val isEmpty: Boolean get() = tempC == null && windKph == null && gustKph == null && rainMm == null && code == null
}

object WeatherRules {
    /** Windy: an average wind of 15 mph (24 km/h) or more. */
    const val WINDY_KPH = 24.0
    /** Cold: under 8°C on average. */
    const val COLD_C = 8.0

    fun kphToMph(kph: Double) = kph / 1.609344

    /** A simple description of a WMO weather code (the worst hour of the round). */
    fun condition(code: Int?): String? = when {
        code == null -> null
        code == 0 -> "Sunny"
        code <= 2 -> "Partly cloudy"
        code == 3 -> "Cloudy"
        code == 45 || code == 48 -> "Fog"
        code in 51..57 -> "Drizzle"
        code in 61..67 -> "Rain"
        code in 71..77 -> "Snow"
        code in 80..82 -> "Showers"
        code == 85 || code == 86 -> "Snow showers"
        code >= 95 -> "Thunder"
        else -> null
    }

    /** A small symbol for history lists. */
    fun icon(code: Int?): String = when (condition(code)) {
        "Sunny" -> "☀️"
        "Partly cloudy" -> "⛅"
        "Cloudy" -> "☁️"
        "Fog" -> "🌫️"
        "Drizzle", "Rain", "Showers" -> "🌧️"
        "Snow", "Snow showers" -> "❄️"
        "Thunder" -> "⛈️"
        else -> ""
    }

    /** "12°C · Rain, 3.2 mm · wind 14 mph, gusts 25 mph" (parts that are missing are left out). */
    fun line(w: Weather): String {
        val parts = mutableListOf<String>()
        w.tempC?.let { parts += "${it.roundToInt()}°C" }
        val c = condition(w.code)
        val rain = w.rainMm?.takeIf { it >= 0.1 }?.let { String.format(Locale.UK, "%.1f mm", it) }
        when {
            c != null -> parts += if (rain != null) "$c, $rain" else c
            rain != null -> parts += "$rain rain"
        }
        w.windKph?.let { wind ->
            val gust = w.gustKph?.let { ", gusts ${kphToMph(it).roundToInt()} mph" } ?: ""
            parts += "wind ${kphToMph(wind).roundToInt()} mph$gust"
        }
        return parts.joinToString(" · ")
    }

    /** Wet: at least 0.5 mm of rain over the round, or drizzle, rain, showers or thunder. */
    fun isWet(w: Weather): Boolean =
        (w.rainMm ?: 0.0) >= 0.5 || condition(w.code) in setOf("Drizzle", "Rain", "Showers", "Thunder")

    fun isWindy(w: Weather): Boolean = (w.windKph ?: 0.0) >= WINDY_KPH
    fun isCold(w: Weather): Boolean = w.tempC != null && w.tempC < COLD_C
}
