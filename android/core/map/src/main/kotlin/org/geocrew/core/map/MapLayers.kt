package org.geocrew.core.map

import org.geocrew.core.ui.MapColors
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.geometryType
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The runtime overlay layers drawn above any base map. Keeping their ids here
 * lets click handling query exactly the layers that carry features.
 */
internal object MapLayers {
    const val SRC_FEATURES = "gc-features"
    const val SRC_ME = "gc-me"
    const val SRC_TEAM = "gc-team"
    const val SRC_SKETCH = "gc-sketch"

    const val L_FILL = "gc-fill"
    const val L_LINE = "gc-line"
    const val L_POINT = "gc-point"
    val clickable = arrayOf(L_POINT, L_LINE, L_FILL)

    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    private val isPolygon = eq(geometryType(), literal("Polygon"))
    private val isLine = eq(geometryType(), literal("LineString"))
    private val isPoint = eq(geometryType(), literal("Point"))
    private val selected = eq(get("selected"), literal(true))

    private fun kindColor(default: String): Expression = switchCase(
        selected, literal(MapColors.SELECTED),
        match(
            get("kind"), literal(default),
            stop("point", MapColors.POINT),
            stop("geo_point", MapColors.GEO_POINT),
            stop("photo", MapColors.POINT),
            stop("line", MapColors.LINE),
            stop("polygon", MapColors.POLYGON),
            stop("track", MapColors.TRACK),
        ),
    )

    fun install(style: Style) {
        listOf(SRC_FEATURES, SRC_ME, SRC_TEAM, SRC_SKETCH).forEach { style.addSource(GeoJsonSource(it, EMPTY)) }

        style.addLayer(
            FillLayer(L_FILL, SRC_FEATURES).withFilter(isPolygon).withProperties(
                fillColor(kindColor(MapColors.POLYGON)),
                fillOpacity(switchCase(selected, literal(0.35f), literal(0.2f))),
            ),
        )
        style.addLayer(
            LineLayer(L_LINE, SRC_FEATURES).withFilter(Expression.any(isLine, isPolygon)).withProperties(
                lineColor(kindColor(MapColors.LINE)),
                lineWidth(switchCase(selected, literal(5f), literal(3f))),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineCap(Property.LINE_CAP_ROUND),
            ),
        )
        style.addLayer(
            CircleLayer(L_POINT, SRC_FEATURES).withFilter(isPoint).withProperties(
                circleRadius(switchCase(selected, literal(9f), literal(6f))),
                circleColor(kindColor(MapColors.POINT)),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ),
        )

        // Team members: green dots, greyed out when their last fix is old.
        style.addLayer(
            CircleLayer("gc-team", SRC_TEAM).withProperties(
                circleRadius(7f),
                circleColor(switchCase(eq(get("stale"), literal(true)), literal("#9E9E9E"), literal(MapColors.TEAM))),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ),
        )

        // Sketch of a line or polygon being drawn.
        style.addLayer(
            LineLayer("gc-sketch-line", SRC_SKETCH).withFilter(isLine).withProperties(
                lineColor(MapColors.SKETCH),
                lineWidth(3f),
                lineDasharray(arrayOf(2f, 1.5f)),
            ),
        )
        style.addLayer(
            CircleLayer("gc-sketch-vertex", SRC_SKETCH).withFilter(isPoint).withProperties(
                circleRadius(5f),
                circleColor("#FFFFFF"),
                circleStrokeColor(MapColors.SKETCH),
                circleStrokeWidth(2f),
            ),
        )

        // My location: translucent accuracy circle and a blue dot on top of everything.
        style.addLayer(
            FillLayer("gc-me-accuracy", SRC_ME).withFilter(isPolygon).withProperties(
                fillColor(MapColors.ME),
                fillOpacity(0.15f),
            ),
        )
        style.addLayer(
            CircleLayer("gc-me-dot", SRC_ME).withFilter(isPoint).withProperties(
                circleRadius(7f),
                circleColor(MapColors.ME),
                circleOpacity(1f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
            ),
        )
    }

    fun update(style: Style, o: MapOverlays, previous: MapOverlays?) {
        fun set(id: String, json: String) = style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(json)
        if (previous == null || previous.features !== o.features || previous.selectedId != o.selectedId) {
            set(SRC_FEATURES, OverlayGeoJson.features(o.features, o.selectedId))
        }
        if (previous == null || previous.me != o.me) set(SRC_ME, OverlayGeoJson.me(o.me))
        if (previous == null || previous.team != o.team) set(SRC_TEAM, OverlayGeoJson.team(o.team))
        if (previous == null || previous.sketch != o.sketch || previous.sketchClosed != o.sketchClosed) {
            set(SRC_SKETCH, OverlayGeoJson.sketch(o.sketch, o.sketchClosed))
        }
    }
}
