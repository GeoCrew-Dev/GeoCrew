package org.geocrew.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What one member did on one day, from the synced records: records they
 * last edited that day (tracks apart), samples logged that day on records
 * they last edited, and the length of tracks they recorded. The protocol
 * keeps only the last editor of a record, so a record someone else touched
 * later counts for that person; for field records that is rarely the case.
 */
data class DailyActivity(val records: Int = 0, val samples: Int = 0, val trackMeters: Double = 0.0) {
    val isEmpty: Boolean get() = records == 0 && samples == 0 && trackMeters <= 0.0

    fun label(): String? = buildList {
        if (records > 0) add("$records 条记录")
        if (samples > 0) add("$samples 件样品")
        if (trackMeters > 0) add("轨迹 ${CoordinateFormatter.distance(trackMeters)}")
    }.takeIf { it.isNotEmpty() }?.joinToString("、")

    companion object {
        fun byMember(features: List<GeoFeature>, day: LocalDate, zone: ZoneId): Map<String, DailyActivity> {
            fun on(t: Instant?) = t != null && t.atZone(zone).toLocalDate() == day
            val out = HashMap<String, DailyActivity>()
            for (f in features) {
                if (f.deleted) continue
                val who = f.sync.updatedBy ?: continue
                var a = out[who] ?: DailyActivity()
                if (f.kind == FeatureKind.TRACK) {
                    val started = f.properties.string(Props.STARTED_AT)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: f.createdAt
                    if (on(started)) a = a.copy(trackMeters = a.trackMeters + (f.geometry?.lengthMeters() ?: 0.0))
                } else if (on(f.sync.updatedAt)) {
                    a = a.copy(records = a.records + 1)
                }
                val logged = f.samples.count { on(it.takenAt) }
                if (logged > 0) a = a.copy(samples = a.samples + logged)
                if (!a.isEmpty) out[who] = a
            }
            return out
        }
    }
}
