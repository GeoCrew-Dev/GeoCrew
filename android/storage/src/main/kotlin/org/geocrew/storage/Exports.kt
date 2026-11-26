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
     * WGS84, as KML wants. GeoCrew's own tables stay out: GDAL would list them as
     * layers, since they are not in gpkg_contents.
     */
    fun kml(project: Project): File {
        project.checkpoint()
        val out = next(project, "kml")
        GeoCrewNative.run(arrayOf("vector", "convert", "--oo", "LIST_ALL_TABLES=NO", "--output-format", "KML", project.file.path, out.path))
        return out
    }

    private fun next(project: Project, extension: String): File {
        dir.mkdirs()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        var out = File(dir, "${project.name}-$stamp.$extension")
        var n = 2
        while (out.exists()) out = File(dir, "${project.name}-$stamp-${n++}.$extension")
        return out
    }
}
