package org.geocrew.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

/**
 * One stop of a geological route: a record (usually a 地质点) and what was
 * seen on the way from it to the next stop (点间描述 / 路线描述).
 */
data class RouteStop(val featureId: String, val onward: String = "")

/**
 * A geological route (地质路线, protocol v0 §6.6), the day's traverse of
 * regional mapping: the observation points in walking order with the
 * description between each pair, plus the route's date, crew, purpose and
 * summary (路线小结). The points stay ordinary records; the route only
 * refers to them by id, so editing a point never touches the route and a
 * deleted point simply drops out of it. Its title is the route number.
 */
data class GeoRoute(
    val date: LocalDate? = null,
    val members: String = "",
    val purpose: String = "",
    val stops: List<RouteStop> = emptyList(),
    val summary: String = "",
) {
    /** A stop with the record it points to; [feature] is null when that record is gone. */
    data class Resolved(val stop: RouteStop, val feature: GeoFeature?) {
        val position: LngLat? get() = (feature?.geometry as? Geometry.Point)?.position
    }

    fun resolve(byId: (String) -> GeoFeature?): List<Resolved> =
        stops.map { s -> Resolved(s, byId(s.featureId)?.takeIf { !it.deleted }) }

    /** The route's line through the stops that have a point position, in order. */
    fun path(byId: (String) -> GeoFeature?): List<LngLat> = resolve(byId).mapNotNull { it.position }

    fun contains(id: String) = stops.any { it.featureId == id }

    /** Appends [ids] not in the route yet. */
    fun plus(ids: List<String>): GeoRoute = copy(stops = stops + ids.distinct().filterNot(::contains).map { RouteStop(it) })

    fun remove(id: String) = copy(stops = stops.filterNot { it.featureId == id })

    /** Moves the stop at [from] to [to]; descriptions travel with their stop. */
    fun move(from: Int, to: Int): GeoRoute {
        if (from !in stops.indices || to !in stops.indices || from == to) return this
        return copy(stops = stops.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    fun withOnward(id: String, text: String) = copy(stops = stops.map { if (it.featureId == id) it.copy(onward = text) else it })

    fun toJson(): JsonObject = buildJsonObject {
        date?.let { put("date", it.toString()) }
        if (members.isNotBlank()) put("members", members.trim())
        if (purpose.isNotBlank()) put("purpose", purpose.trim())
        put(
            "stops",
            JsonArray(
                stops.map { s ->
                    buildJsonObject {
                        put("feature", s.featureId)
                        if (s.onward.isNotBlank()) put("onward", s.onward.trim())
                    }
                },
            ),
        )
        if (summary.isNotBlank()) put("summary", summary.trim())
    }

    companion object {
        fun fromJson(o: JsonObject?): GeoRoute {
            if (o == null) return GeoRoute()
            val seen = mutableSetOf<String>()
            val stops = (o["stops"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
                val s = e as? JsonObject ?: return@mapNotNull null
                val id = s.string("feature")?.takeIf { it.isNotBlank() && seen.add(it) } ?: return@mapNotNull null
                RouteStop(id, s.string("onward").orEmpty())
            }
            return GeoRoute(
                date = o.string("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                members = o.string("members").orEmpty(),
                purpose = o.string("purpose").orEmpty(),
                stops = stops,
                summary = o.string("summary").orEmpty(),
            )
        }

        /** Kinds that make sense as route stops: records placed at one point. */
        fun isStop(f: GeoFeature) = !f.deleted && f.geometry is Geometry.Point && f.kind != FeatureKind.ROUTE

        /** Point records created on [day] in [zone], in the order they were made: a first guess at the day's route. */
        fun dayStops(features: List<GeoFeature>, day: LocalDate, zone: ZoneId): List<GeoFeature> =
            features.filter { f -> isStop(f) && f.createdAt.atZone(zone).toLocalDate() == day }.sortedBy { it.createdAt }
    }
}

/** The route stored on a record of kind [FeatureKind.ROUTE]. */
val GeoFeature.geoRoute: GeoRoute
    get() = GeoRoute.fromJson(properties[Props.ROUTE] as? JsonObject)
