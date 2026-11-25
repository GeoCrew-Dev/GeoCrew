package org.geocrew.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise, sunset and twilight for one day at one place, from the NOAA
 * solar position equations (good to about a minute between ±72° latitude,
 * a few minutes nearer the poles). For planning a field day: when it gets
 * light, when to head back.
 *
 * A null rise or set means the sun does not cross that altitude that day;
 * [polar] says whether it stays above (midnight sun) or below (polar night).
 */
data class SunDay(
    val date: LocalDate,
    val noon: Instant,
    val sunrise: Instant?,
    val sunset: Instant?,
    /** Start and end of civil twilight (sun 6° below the horizon): usable light without a lamp. */
    val dawn: Instant?,
    val dusk: Instant?,
    val polar: Polar? = null,
) {
    enum class Polar { DAY, NIGHT }

    /** Sunrise to sunset; 24 h on a polar day, 0 on a polar night. */
    val daylightMinutes: Long
        get() = when {
            sunrise != null && sunset != null -> (sunset.epochSecond - sunrise.epochSecond) / 60
            polar == Polar.DAY -> 24 * 60
            else -> 0
        }
}

object SunTimes {
    private const val RISE_SET = -0.833 // refraction plus the sun's radius
    private const val CIVIL = -6.0

    /** The day of [date] at [at]; the date is taken at the place's own longitude. */
    fun of(date: LocalDate, at: LngLat): SunDay {
        val lat = at.lat.coerceIn(-89.99, 89.99)
        val lng = at.lng
        // Local solar noon in UTC minutes after midnight, refined once.
        var noonMin = 720.0 - 4 * lng
        repeat(2) { noonMin = 720.0 - 4 * lng - equationOfTime(century(date, noonMin)) }
        val rise = event(date, lat, lng, noonMin, RISE_SET, rising = true)
        val set = event(date, lat, lng, noonMin, RISE_SET, rising = false)
        val polar = if (rise != null) null else {
            val decl = declination(century(date, noonMin))
            // At noon the sun is at 90 - |lat - decl|; above the horizon means it never set.
            if (90 - abs(lat - decl) > RISE_SET) SunDay.Polar.DAY else SunDay.Polar.NIGHT
        }
        return SunDay(
            date = date,
            noon = instant(date, noonMin),
            sunrise = rise?.let { instant(date, it) },
            sunset = set?.let { instant(date, it) },
            dawn = event(date, lat, lng, noonMin, CIVIL, rising = true)?.let { instant(date, it) },
            dusk = event(date, lat, lng, noonMin, CIVIL, rising = false)?.let { instant(date, it) },
            polar = polar,
        )
    }

    /** UTC minutes of the moment the sun passes [altitude], or null if it does not. */
    private fun event(date: LocalDate, lat: Double, lng: Double, noonMin: Double, altitude: Double, rising: Boolean): Double? {
        var minutes = noonMin
        repeat(3) {
            val t = century(date, minutes)
            val ha = hourAngle(lat, declination(t), altitude) ?: return null
            val noon = 720.0 - 4 * lng - equationOfTime(t)
            minutes = if (rising) noon - 4 * ha else noon + 4 * ha
        }
        return minutes
    }

    private fun hourAngle(lat: Double, decl: Double, altitude: Double): Double? {
        val la = Math.toRadians(lat)
        val d = Math.toRadians(decl)
        val c = (sin(Math.toRadians(altitude)) - sin(la) * sin(d)) / (cos(la) * cos(d))
        if (c < -1 || c > 1) return null
        return Math.toDegrees(acos(c))
    }

    private fun century(date: LocalDate, utcMinutes: Double): Double {
        val jd = date.toEpochDay() + 2440587.5 + utcMinutes / 1440.0
        return (jd - 2451545.0) / 36525.0
    }

    private fun meanLongitude(t: Double) = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
    private fun meanAnomaly(t: Double) = 357.52911 + t * (35999.05029 - 0.0001537 * t)
    private fun eccentricity(t: Double) = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    private fun obliquity(t: Double): Double {
        val mean = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        return mean + 0.00256 * cos(Math.toRadians(125.04 - 1934.136 * t))
    }

    private fun declination(t: Double): Double {
        val m = Math.toRadians(meanAnomaly(t))
        val center = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val apparent = meanLongitude(t) + center - 0.00569 - 0.00478 * sin(Math.toRadians(125.04 - 1934.136 * t))
        return Math.toDegrees(asin(sin(Math.toRadians(obliquity(t))) * sin(Math.toRadians(apparent))))
    }

    /** Minutes the apparent sun is ahead of the mean sun. */
    private fun equationOfTime(t: Double): Double {
        val eps = Math.toRadians(obliquity(t))
        val l0 = Math.toRadians(meanLongitude(t))
        val e = eccentricity(t)
        val m = Math.toRadians(meanAnomaly(t))
        val y = tan(eps / 2).let { it * it }
        val v = y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) -
            0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m)
        return 4 * Math.toDegrees(v)
    }

    private fun instant(date: LocalDate, utcMinutes: Double): Instant =
        date.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(Math.round(utcMinutes * 60))
}
