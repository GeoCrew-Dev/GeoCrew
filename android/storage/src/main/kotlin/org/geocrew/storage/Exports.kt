package org.geocrew.storage

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.geocrew.nativelib.GeoCrewNative
import org.geocrew.nativelib.VectorDataset

/**
 * Copies of a project for other people and other programs (ADR 0004). Only
 * saved edits are exported. Each export is a new file in [dir], named after the
 * project and the time.
 */
internal class Exports(private val dir: File) {
    /** The project's GeoPackage as it is saved, in rollback-journal mode, which every reader handles. */
    fun gpkg(project: Project): File {
        project.checkpoint()
        val out = next(project, "gpkg")
        // Copied under another name first: a copy cut short never looks like a finished export.
        val part = File(out.path + ".part")
        project.file.copyTo(part, overwrite = true)
        VectorDataset.open(part.path, update = true).use { it.sql("PRAGMA journal_mode=DELETE") }
        check(part.renameTo(out)) { "cannot name the export $out" }
        return out
    }

    /**
     * Every layer of the project as one KML file; GDAL puts the coordinates in
     * WGS84, as KML wants. GeoCrew's own tables stay out: the layers are named,
     * as GDAL would list the attachment table and the unregistered tables too.
     */
    fun kml(project: Project): File = convert(project, project.layers.map { it.name }, "KML", next(project, "kml"))

    /**
     * One layer of the project on its own (ADR 0017: a layer's ⋮ menu): as a
     * GeoPackage, its features, fields and CRS as they are saved (GeoCrew's
     * styles and attachments stay behind), or as KML in WGS84.
     */
    fun layer(project: Project, layer: String, format: String): File {
        val extension = if (format == "gpkg") "gpkg" else "kml"
        val out = next(project, extension, layer)
        val part = File(out.parentFile, ".${out.name}.part.$extension")
        part.delete()
        try {
            convert(project, listOf(layer), if (format == "gpkg") "GPKG" else "KML", part)
            check(part.renameTo(out)) { "cannot name the export $out" }
        } finally {
            part.delete()
        }
        return out
    }

    private fun convert(project: Project, layers: List<String>, driver: String, out: File): File {
        project.checkpoint()
        val inputs = layers.flatMap { listOf("--input-layer", it) }
        GeoCrewNative.run(arrayOf("vector", "convert", "--oo", "LIST_ALL_TABLES=NO") + inputs + arrayOf("--output-format", driver, project.file.path, out.path))
        return out
    }

    // A new file named after the project (and the layer), and the time; characters no file name takes become "_".
    private fun next(project: Project, extension: String, layer: String? = null): File {
        dir.mkdirs()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val base = listOfNotNull(project.name, layer?.replace(Regex("""[\\/:*?"<>|]"""), "_")).joinToString("-")
        var out = File(dir, "$base-$stamp.$extension")
        var n = 2
        while (out.exists()) out = File(dir, "$base-$stamp-${n++}.$extension")
        return out
    }
}
