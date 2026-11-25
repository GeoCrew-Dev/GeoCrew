package org.geocrew.core.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The arithmetic of printing the map view as a sheet: a round scale the
 * printed picture follows exactly, a scale bar, and graticule ticks in
 * degrees, minutes and seconds along the frame, as on a printed
 * topographic sheet. The drawing itself is platform code.
 */
object MapPrint {
    /** Scales a printed map may take, the usual ones for field and survey maps. */
    val SCALES: List<Long> = listOf(
        500, 1000, 2000, 2500, 5000, 10_000, 20_000, 25_000, 50_000,
        100_000, 200_000, 250_000, 500_000, 1_000_000, 2_000_000, 2_500_000, 5_000_000,
    )

    /**
     * The largest round scale (smallest denominator) at which [groundMeters]
     * of ground fit in [paperMm] of paper; beyond the list, whole millions.
     */
    fun scaleToFit(groundMeters: Double, paperMm: Double): Long {
        require(groundMeters > 0 && paperMm > 0) { "范围和纸面宽度都要大于零" }
        val needed = groundMeters * 1000 / paperMm
        return SCALES.firstOrNull { it >= needed * (1 - 1e-9) } ?: (ceil(needed / 1_000_000) * 1_000_000).roundToLong()
    }

    /** Paper millimetres that [groundMeters] take at 1:[scale]. */
    fun paperMm(groundMeters: Double, scale: Long): Double = groundMeters * 1000 / scale

    /** A scale bar at 1:[scale]: the longest round length that fits in [maxMm], in metres, and its length on paper. */
    fun bar(scale: Long, maxMm: Double): MapScale.Bar = MapScale.bar(scale / 1000.0, maxMm)

    data class Tick(val value: Double, val label: String)

    /** Graticule steps, in degrees: from 1″ to 10°. */
    private val STEPS = listOf(
        1.0, 2.0, 5.0, 10.0, 15.0, 30.0, // seconds
        60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, // minutes
        3600.0, 7200.0, 18_000.0, 36_000.0, // degrees
    ).map { it / 3600.0 }

    /** The finest step that leaves at most [most] ticks between [min] and [max], in degrees. */
    fun step(min: Double, max: Double, most: Int = 5): Double {
        val span = abs(max - min)
        return STEPS.firstOrNull { span / it <= most } ?: STEPS.last()
    }

    /**
     * Graticule ticks strictly inside [min]..[max] (degrees of longitude,
     * or of latitude with [lat]), labelled to the precision of the step:
     * "116°20′" or "40°05′30″", with E/W or N/S.
     */
    fun ticks(min: Double, max: Double, lat: Boolean, most: Int = 5): List<Tick> {
        if (!(max > min)) return emptyList()
        val step = step(min, max, most)
        val out = mutableListOf<Tick>()
        var k = floor(min / step).toLong() + 1
        while (k * step < max - 1e-12) {
            val v = k * step
            if (v > min + 1e-12) out += Tick(v, label(v, step, lat))
            k++
        }
        return out
    }

    /** [v] in degrees written to the precision of [step]: seconds only when the step needs them. */
    fun label(v: Double, step: Double, lat: Boolean): String {
        val hemi = if (lat) (if (v < 0) "S" else "N") else (if (v < 0) "W" else "E")
        val totalSeconds = (abs(v) * 3600).roundToLong()
        val d = totalSeconds / 3600
        val m = totalSeconds % 3600 / 60
        val s = totalSeconds % 60
        val stepSeconds = (step * 3600).roundToLong()
        return when {
            stepSeconds % 3600 == 0L -> "$d°$hemi"
            stepSeconds % 60 == 0L -> "%d°%02d′%s".fmt(d, m, hemi)
            else -> "%d°%02d′%02d″%s".fmt(d, m, s, hemi)
        }
    }
}
