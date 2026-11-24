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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
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
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource

/**
 * The runtime overlay layers drawn above any base map. Keeping their ids here
 * lets click handling query exactly the layers that carry features.
 */
internal object MapLayers {
    const val SRC_FEATURES = "gc-features"
    const val SRC_ME = "gc-me"
    const val SRC_TEAM = "gc-team"
    const val SRC_SKETCH = "gc-sketch"
    const val SRC_GRID = "gc-grid"

    const val L_FILL = "gc-fill"
    const val L_LINE = "gc-line"
    const val L_POINT = "gc-point"
    private const val L_ATTITUDE = "gc-attitude"
    private const val L_DIP = "gc-dip"
    val clickable = arrayOf(L_ATTITUDE, L_POINT, L_LINE, L_FILL)

    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    private val isPolygon = eq(geometryType(), literal("Polygon"))
    private val isLine = eq(geometryType(), literal("LineString"))
    private val isPoint = eq(geometryType(), literal("Point"))
    private val selected = eq(get("selected"), literal(true))
    private val hasAttitude = Expression.has("dipdir")
    private val myTask = eq(get("mytask"), literal(true))

    /** Colour for a feature: selection first, then its own simplestyle colour [own], then by kind. */
    private fun kindColor(default: String, own: String? = null): Expression = switchCase(
        selected, literal(MapColors.SELECTED),
        *(if (own != null) arrayOf(Expression.has(own), Expression.toColor(get(own))) else emptyArray()),
        match(
            get("kind"), literal(default),
            stop("point", MapColors.POINT),
            stop("geo_point", MapColors.GEO_POINT),
            stop("photo", MapColors.POINT),
            stop("line", MapColors.LINE),
            stop("polygon", MapColors.POLYGON),
            stop("track", MapColors.TRACK),
            stop("section", MapColors.SECTION),
            stop("route", MapColors.ROUTE),
        ),
    )

    fun install(style: Style) {
        listOf(SRC_GRID, SRC_FEATURES, SRC_ME, SRC_TEAM, SRC_SKETCH).forEach { style.addSource(GeoJsonSource(it, EMPTY)) }

        // Kilometre grid: thin and below everything else, like the grid on a printed topographic map.
        style.addLayer(
            LineLayer("gc-grid", SRC_GRID).withProperties(
                lineColor(MapColors.GRID),
                lineWidth(0.8f),
                lineOpacity(0.7f),
            ),
        )

        // Open tasks handed to me: a soft halo under the feature itself.
        style.addLayer(
            LineLayer("gc-mytask-line", SRC_FEATURES).withFilter(Expression.all(myTask, Expression.any(isLine, isPolygon))).withProperties(
                lineColor(MapColors.MY_TASK),
                lineWidth(10f),
                lineOpacity(0.45f),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineCap(Property.LINE_CAP_ROUND),
            ),
        )
        style.addLayer(
            CircleLayer("gc-mytask-point", SRC_FEATURES).withFilter(Expression.all(myTask, isPoint)).withProperties(
                circleRadius(14f),
                circleColor(MapColors.MY_TASK),
                circleOpacity(0.25f),
                circleStrokeColor(MapColors.MY_TASK),
                circleStrokeWidth(2f),
            ),
        )

        style.addLayer(
            FillLayer(L_FILL, SRC_FEATURES).withFilter(isPolygon).withProperties(
                fillColor(kindColor(MapColors.POLYGON, "fill")),
                fillOpacity(
                    switchCase(selected, literal(0.35f), Expression.has("fill-opacity"), get("fill-opacity"), literal(0.2f)),
                ),
            ),
        )
        style.addLayer(
            LineLayer(L_LINE, SRC_FEATURES).withFilter(Expression.any(isLine, isPolygon)).withProperties(
                lineColor(kindColor(MapColors.LINE, "stroke")),
                lineWidth(switchCase(selected, literal(5f), Expression.has("stroke-width"), get("stroke-width"), literal(3f))),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineCap(Property.LINE_CAP_ROUND),
            ),
        )
        // Points with an attitude get a small dot under a strike-and-dip symbol.
        style.addLayer(
            CircleLayer(L_POINT, SRC_FEATURES).withFilter(isPoint).withProperties(
                circleRadius(switchCase(selected, literal(9f), hasAttitude, literal(3.5f), literal(6f))),
                circleColor(kindColor(MapColors.POINT, "marker-color")),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ),
        )

        AttitudeSymbols.install(style)
        style.addLayer(
            SymbolLayer(L_ATTITUDE, SRC_FEATURES).withFilter(Expression.all(isPoint, hasAttitude)).withProperties(
                iconImage(
                    switchCase(
                        Expression.lt(get("dip"), literal(1)), literal(AttitudeSymbols.HORIZONTAL),
                        Expression.gte(get("dip"), literal(89)), literal(AttitudeSymbols.VERTICAL),
                        literal(AttitudeSymbols.INCLINED),
                    ),
                ),
                iconRotate(Expression.toNumber(get("dipdir"))),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        // Dip angle beside the symbol, kept upright; hidden when zoomed far out.
        style.addLayer(
            SymbolLayer(L_DIP, SRC_FEATURES)
                .withFilter(Expression.all(isPoint, hasAttitude, Expression.gte(get("dip"), literal(1)), Expression.lt(get("dip"), literal(89))))
                .withProperties(
                    iconImage(Expression.concat(literal(AttitudeSymbols.DIP_PREFIX), Expression.toString(get("dip")))),
                    iconAnchor(Property.ICON_ANCHOR_TOP_LEFT),
                    iconOffset(arrayOf(6f, 4f)),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                ).also { it.minZoom = 13f },
        )

        // Team members: green dots, greyed out when their last fix is old, with
        // their last half hour as a thin line behind them.
        style.addLayer(
            LineLayer("gc-team-trail", SRC_TEAM).withFilter(isLine).withProperties(
                lineColor(switchCase(eq(get("stale"), literal(true)), literal("#9E9E9E"), literal(MapColors.TEAM))),
                lineWidth(2f),
                lineOpacity(0.7f),
            ),
        )
        style.addLayer(
            CircleLayer("gc-team", SRC_TEAM).withFilter(isPoint).withProperties(
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
        style.addLayer(
            CircleLayer("gc-sketch-picked", SRC_SKETCH)
                .withFilter(Expression.all(isPoint, eq(get("picked"), literal(true))))
                .withProperties(
                    circleRadius(9f),
                    circleColor(MapColors.SKETCH),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(3f),
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
        if (previous == null || previous.features !== o.features || previous.selectedId != o.selectedId || previous.legend != o.legend) {
            AttitudeSymbols.ensureDipLabels(style, o.features.mapNotNull { f -> f.attitude?.dip?.let { Math.round(it).toInt() } }.toSet())
            set(SRC_FEATURES, OverlayGeoJson.features(o.features, o.selectedId, o.memberId, o.legend))
        }
        if (previous == null || previous.grid !== o.grid) set(SRC_GRID, OverlayGeoJson.grid(o.grid))
        if (previous == null || previous.me != o.me) set(SRC_ME, OverlayGeoJson.me(o.me))
        if (previous == null || previous.team != o.team) set(SRC_TEAM, OverlayGeoJson.team(o.team))
        if (previous == null || previous.images != o.images) images(style, o.images, previous?.images.orEmpty())
        if (previous == null || previous.sketch != o.sketch || previous.sketchClosed != o.sketchClosed || previous.sketchPicked != o.sketchPicked) {
            set(SRC_SKETCH, OverlayGeoJson.sketch(o.sketch, o.sketchClosed, o.sketchPicked))
        }
    }

    private const val IMG = "gc-img-"

    /** Adds, changes and removes the image overlays; each is a source and a raster layer just under the grid. */
    private fun images(style: Style, now: List<MapImage>, before: List<MapImage>) {
        val keep = now.map { it.id }.toSet()
        for (old in before) if (old.id !in keep) {
            style.removeLayer(IMG + old.id)
            style.removeSource(IMG + old.id)
        }
        val was = before.associateBy { it.id }
        for (img in now) {
            val quad = LatLngQuad(img.quad[0].latLng(), img.quad[1].latLng(), img.quad[2].latLng(), img.quad[3].latLng())
            val src = style.getSourceAs<ImageSource>(IMG + img.id)
            if (src == null) {
                style.addSource(ImageSource(IMG + img.id, quad, img.bitmap))
                // Added in order just under the grid, so later images lie on top of earlier ones.
                style.addLayerBelow(RasterLayer(IMG + img.id, IMG + img.id).withProperties(rasterOpacity(img.opacity), rasterFadeDuration(0f)), "gc-grid")
                continue
            }
            val prev = was[img.id]
            if (prev?.bitmap !== img.bitmap) src.setImage(img.bitmap)
            if (prev?.quad != img.quad) src.setCoordinates(quad)
            if (prev?.opacity != img.opacity) style.getLayer(IMG + img.id)?.setProperties(rasterOpacity(img.opacity))
        }
    }

    private fun org.geocrew.core.model.LngLat.latLng() = LatLng(lat, lng)
}
