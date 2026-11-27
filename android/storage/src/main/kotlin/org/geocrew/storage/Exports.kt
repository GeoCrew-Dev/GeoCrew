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
     * Every layer of the project as KML, or KMZ with the photos (see [Kml]):
     * GeoCrew's own tables stay out, only the layers are written.
     */
    fun kml(project: Project, zipped: Boolean = false): File = keyhole(project, project.layers, zipped, next(project, if (zipped) "kmz" else "kml"))

    /**
     * One layer of the project on its own (ADR 0017: a layer's ⋮ menu): as a
     * GeoPackage, its features, fields and CRS as they are saved (GeoCrew's
     * styles and attachments stay behind), or as KML, or KMZ with its photos.
     */
    fun layer(project: Project, layer: String, format: String): File {
        val found = requireNotNull(project.layers.firstOrNull { it.name == layer }) { "the project has no layer $layer" }
        if (format != "gpkg") return keyhole(project, listOf(found), format == "kmz", next(project, format, layer))
        val out = next(project, "gpkg", layer)
        val part = File(out.parentFile, ".${out.name}.part.gpkg")
        part.delete()
        try {
            convert(project, listOf(layer), "GPKG", part)
            check(part.renameTo(out)) { "cannot name the export $out" }
        } finally {
            part.delete()
        }
        return out
    }

    // [layers] as KML at [out], or as KMZ: doc.kml first, as readers look for it, then the photos it shows under files/.
    // Written under another name first, so a file cut short never looks finished.
    private fun keyhole(project: Project, layers: List<Project.Layer>, zipped: Boolean, out: File): File {
        val part = File(out.parentFile, ".${out.name}.part")
        part.delete()
        try {
            val kml = Kml(project, photos = zipped)
            if (zipped) {
                java.util.zip.ZipOutputStream(part.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("doc.kml"))
                    val writer = zip.writer(Charsets.UTF_8)
                    kml.write(writer, layers)
                    writer.flush()
                    zip.closeEntry()
                    for ((name, file) in kml.images) {
                        zip.putNextEntry(java.util.zip.ZipEntry("files/$name"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } else {
                part.bufferedWriter(Charsets.UTF_8).use { kml.write(it, layers) }
            }
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
