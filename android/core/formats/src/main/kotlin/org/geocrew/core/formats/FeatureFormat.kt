package org.geocrew.core.formats

import org.geocrew.core.model.GeoFeature
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/** Settings shared by every import. */
data class ImportContext(
    val projectId: String,
    val now: Instant = Instant.now(),
    val newId: () -> String = { UUID.randomUUID().toString() },
)

/** Extra data an export may use (e.g. per-vertex times for GPX tracks). */
data class ExportContext(
    val title: String = "GeoCrew",
    val trackTimes: (featureId: String) -> List<Instant>? = { null },
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
}

object FeatureFormats {
    val all: List<FeatureFormat> = listOf(GeoJsonFormat, KmlFormat, GpxFormat, ShapefileFormat)

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
