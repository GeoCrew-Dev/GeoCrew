package org.geocrew.core.formats

import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Legend
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/** Settings shared by every import. */
data class ImportContext(
    val projectId: String,
    val now: Instant = Instant.now(),
    val newId: () -> String = { UUID.randomUUID().toString() },
    /** The project's form, so columns named by a field's label land in that field. */
    val fields: List<FieldTemplate> = emptyList(),
    /** The project's local map grid: plane coordinates on its ellipsoid and zone are read through its datum shift. */
    val localGrid: org.geocrew.core.model.LocalGrid? = null,
)

/** Extra data an export may use (e.g. per-vertex times for GPX tracks). */
data class ExportContext(
    val title: String = "GeoCrew",
    val trackTimes: (featureId: String) -> List<Instant>? = { null },
    /** The project's form: tabular formats put these columns first, headed by their labels. */
    val fields: List<FieldTemplate> = emptyList(),
    /** The project's map legend: formats with styles (KML) colour records the same way. */
    val legend: Legend = Legend(),
    /** The project's local map grid: plane formats (DXF) write its coordinates instead of CGCS2000. */
    val localGrid: org.geocrew.core.model.LocalGrid? = null,
)

data class ImportResult(
    val features: List<GeoFeature>,
    /** Human-readable notes about skipped or converted content. */
    val warnings: List<String> = emptyList(),
)

data class ExportResult(val written: Int, val skipped: Int = 0)

/**
 * A file format the app can import and/or export. New formats (Shapefile,
 * GeoPackage, CSV...) plug in by implementing this and registering in
 * [FeatureFormats].
 */
interface FeatureFormat {
    val id: String
    val displayName: String
    val extension: String
    val mimeType: String
    val canRead: Boolean
    val canWrite: Boolean

    fun read(input: InputStream, ctx: ImportContext): ImportResult =
        throw UnsupportedOperationException("$displayName cannot be imported")

    fun write(features: List<GeoFeature>, output: OutputStream, ctx: ExportContext = ExportContext()): ExportResult =
        throw UnsupportedOperationException("$displayName cannot be exported")

    /**
     * Report formats that write something other than one row per record
     * (samples, section layers) say what they count and what to call the file.
     */
    val unit: String get() = "个要素"

    /** Part of the suggested file name, e.g. "samples" in geocrew-samples-20261124.csv; null for plain exports. */
    val fileTag: String? get() = null

    /** Why there is nothing to write, shown instead of exporting an empty file; null when there is something. */
    fun nothingToWrite(features: List<GeoFeature>): String? = null
}

object FeatureFormats {
    private val builtIn: List<FeatureFormat> =
        listOf(GeoJsonFormat, KmlFormat, GpxFormat, ShapefileFormat, DxfFormat, CsvFormat, SampleListFormat, SectionTableFormat, RouteTableFormat)

    @Volatile private var extra: List<FeatureFormat> = emptyList()

    /** Built-in formats, then those [register]ed by the app (e.g. ones that need the platform's SQLite). */
    val all: List<FeatureFormat> get() = builtIn + extra

    /**
     * Adds a format that can't be built here, such as GeoPackage, which needs
     * a SQLite engine. A later format with the same id replaces the earlier.
     */
    @Synchronized
    fun register(format: FeatureFormat) {
        extra = extra.filterNot { it.id == format.id } + format
    }

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** Picks a reader from a file name or MIME type. */
    fun detect(fileName: String?, mimeType: String?): FeatureFormat? {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        return all.firstOrNull { it.extension == ext }
            ?: all.firstOrNull { it.mimeType == mimeType }
            ?: if (ext == "json") GeoJsonFormat else null
    }
}

class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
