package org.geocrew.core.model

import kotlin.math.abs

/**
 * Reads a position typed or pasted by a person: decimal degrees or
 * degrees-minutes-seconds, with hemisphere letters or Chinese labels, in
 * either order. Examples that all work:
 *
 *     116.391, 39.907
 *     39.907 116.391          (|value| > 90 decides which is longitude)
 *     N39°54'25" E116°23'28"
 *     39°54′25.2″N, 116°23′27.6″E
 *     东经116度23分28秒 北纬39度54分25秒
 *     经度 116.391 纬度 39.907
 *
 * Without letters or labels, two values both within ±90 are read in the
 * app's own display order, longitude first.
 */
object CoordinateParser {
    private const val NUMBER = """([-+]?\d+(?:\.\d+)?)(?:\s*°(?:\s*(\d+(?:\.\d+)?)\s*['′](?:\s*(\d+(?:\.\d+)?)\s*(?:["″]|['′]{2}))?)?)?"""

    /** Hemisphere letters written before the numbers ("N39 E116")… */
    private val PREFIXED = Regex("""([NSEW])?\s*$NUMBER()""")

    /** …or after them ("39N 116E"); a text uses one style throughout. */
    private val SUFFIXED = Regex("""()$NUMBER\s*([NSEW])?""")

    fun parse(text: String): LngLat? {
        val s = normalize(text)
        val pattern = if (s.firstOrNull()?.let { it in "NSEW" } == true) PREFIXED else SUFFIXED
        val parts = pattern.findAll(s).toList()
        if (parts.size != 2) return null
        val values = parts.map { m ->
            val (h1, deg, min, sec, h2) = m.destructured
            if (h1.isNotEmpty() && h2.isNotEmpty()) return null
            val d = deg.toDouble()
            val mm = min.toDoubleOrNull() ?: 0.0
            val ss = sec.toDoubleOrNull() ?: 0.0
            if (mm >= 60 || ss >= 60 || (mm > 0 || ss > 0) && d != Math.floor(d)) return null
            val hemi = h1.ifEmpty { h2 }
            var v = abs(d) + mm / 60 + ss / 3600
            if (d < 0 || hemi == "S" || hemi == "W") v = -v
            if (hemi.isNotEmpty() && d < 0) return null
            Axis(v, when (hemi) { "N", "S" -> true; "E", "W" -> false; else -> null })
        }
        val (a, b) = values
        val aIsLat = when {
            a.lat != null && b.lat != null -> if (a.lat == b.lat) return null else a.lat
            a.lat != null -> a.lat
            b.lat != null -> !b.lat
            abs(a.value) > 90 -> false
            abs(b.value) > 90 -> true
            else -> false
        }
        val lat = if (aIsLat) a.value else b.value
        val lng = if (aIsLat) b.value else a.value
        if (abs(lat) > 90 || abs(lng) > 180) return null
        return LngLat(lng, lat)
    }

    private class Axis(val value: Double, val lat: Boolean?)

    private fun normalize(text: String): String {
        var s = text.trim()
        // Labels and full-width punctuation people paste from documents.
        val replacements = listOf(
            "东经" to "E", "西经" to "W", "北纬" to "N", "南纬" to "S",
            "经度" to "E", "纬度" to "N",
            "度" to "°", "分" to "′", "秒" to "″",
            "，" to ",", "；" to ";", "：" to " ", ":" to " ", "º" to "°", "˚" to "°", "’" to "′", "”" to "″",
        )
        for ((from, to) in replacements) s = s.replace(from, to)
        return s.uppercase()
    }
}
