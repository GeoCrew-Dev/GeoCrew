package org.geocrew.storage

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.geocrew.nativelib.GeoCrewNative
import org.geocrew.nativelib.VectorDataset

/**
 * The formats an export can be in (ADR 0004): [extension] of its file, and
 * whether one file holds several layers ([layers]); a project's layers in a
 * format that holds one go in a zip, a file each. GDAL writes most; KML, KMZ
 * and GPX are written by the app, with names and photos as GeoCrew keeps them.
 */
enum class ExportFormat(val id: String, val extension: String, val layers: Boolean) {
    GPKG("gpkg", "gpkg", true),
    SHP("shp", "zip", true),
    KML("kml", "kml", true),
    KMZ("kmz", "kmz", true),
    GEOJSON("geojson", "geojson", false),
    CSV("csv", "csv", false),
    XLSX("xlsx", "xlsx", true),
    GPX("gpx", "gpx", true),
    DXF("dxf", "dxf", false),
    ;

    companion object {
        fun of(id: String): ExportFormat = entries.first { it.id == id }
    }
}

/**
 * Copies of a project for other people and other programs (ADR 0004). Only
 * saved edits are exported. Each export is a new file in [dir], named after the
 * project (and the layer) and the time.
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
     * The project's layers, or [layer] alone (ADR 0017: a layer's ⋮ menu), in
     * [format]; [language] (zh or en) names the coordinate columns of CSV and
     * Excel. GeoPackage keeps fields and CRS as they are (a whole project, its
     * styles too); Shapefile is zipped, in UTF-8; GeoJSON is in WGS84, as RFC
     * 7946 has it; CSV and Excel give a point's coordinates in two columns, a
     * line's or area's as WKT in CSV; GPX holds points and lines.
     */
    fun export(project: Project, format: ExportFormat, layer: String? = null, language: String = "en"): File {
        val layers = if (layer == null) {
            project.layers
        } else {
            listOf(requireNotNull(project.layers.firstOrNull { it.name == layer }) { "the project has no layer $layer" })
        }
        when (format) {
            ExportFormat.GPKG -> if (layer == null) return gpkg(project)
            ExportFormat.KML, ExportFormat.KMZ -> return keyhole(project, layers, format == ExportFormat.KMZ, next(project, format.extension, layer))
            ExportFormat.GPX -> return gpx(project, layers, next(project, format.extension, layer))
            else -> {}
        }
        project.checkpoint()
        val zipped = layer == null && !format.layers
        val out = next(project, if (zipped) "${format.extension}.zip" else format.extension, layer)
        // Written in a folder of its own first, so a file cut short never looks finished; its name has no extension in it,
        // as GDAL takes the first .xlsx or .zip in a path for the archive it writes into.
        val work = File(dir, ".export-${System.nanoTime()}")
        work.deleteRecursively()
        work.mkdirs()
        try {
            val names = layers.map { it.name }
            val written: File = when (format) {
                ExportFormat.GPKG -> File(work, "out.gpkg").also { convert(project, names, "GPKG", it) }
                ExportFormat.SHP -> File(work, "shp").also { convert(project, names, "ESRI Shapefile", it, "ENCODING=UTF-8") }
                ExportFormat.XLSX -> File(work, "out.xlsx").also { excel(project, layers, it, language, work) }
                else -> {
                    val taken = mutableSetOf<String>()
                    val files = layers.map { l ->
                        // A file a layer, named after it; names that come out alike get a number.
                        var base = safe(l.name)
                        var n = 2
                        while (!taken.add(base.lowercase())) base = "${safe(l.name)} ${n++}"
                        val file = File(work, base + "." + format.extension)
                        when (format) {
                            ExportFormat.GEOJSON -> convert(project, listOf(l.name), "GeoJSON", file, "RFC7946=YES")
                            ExportFormat.DXF -> convert(project, listOf(l.name), "DXF", file)
                            else -> csv(project, l, file, language, forExcel = false)
                        }
                        file
                    }
                    if (zipped) work else files.single()
                }
            }
            val part = File(dir, ".${out.name}.part")
            part.delete()
            if (written.isDirectory) zip(written, part) else check(written.renameTo(part)) { "cannot move the export into place" }
            check(part.renameTo(out)) { "cannot name the export $out" }
        } finally {
            work.deleteRecursively()
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
                ZipOutputStream(part.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("doc.kml"))
                    val writer = zip.writer(Charsets.UTF_8)
                    kml.write(writer, layers)
                    writer.flush()
                    zip.closeEntry()
                    for ((name, file) in kml.images) {
                        zip.putNextEntry(ZipEntry("files/$name"))
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

    // The points and lines of [layers] as GPX at [out]; areas GPX does not hold.
    private fun gpx(project: Project, layers: List<Project.Layer>, out: File): File {
        val gpx = Gpx(project)
        require(layers.any(gpx::holds)) { "GPX holds points and lines only" }
        val part = File(out.parentFile, ".${out.name}.part")
        part.delete()
        try {
            part.bufferedWriter(Charsets.UTF_8).use { gpx.write(it, layers) }
            check(part.renameTo(out)) { "cannot name the export $out" }
        } finally {
            part.delete()
        }
        return out
    }

    // [layer] as CSV at [file]: a point's coordinates in the first two columns, named for what they are, a line's or area's as
    // WKT (left out for Excel, whose cells are too small for long lines); with a byte order mark, so that Excel reads
    // Chinese right, or for Excel with the types of the columns beside it (.csvt).
    private fun csv(project: Project, layer: Project.Layer, file: File, language: String, forExcel: Boolean) {
        val kind = layer.geometry?.uppercase().orEmpty()
        // GDAL gives X and Y of single points only: a multipoint goes as WKT.
        val point = kind.startsWith("POINT")
        val options = buildList {
            if (point) add("GEOMETRY=AS_XY") else if (!forExcel && kind.isNotEmpty() && kind != "NONE") add("GEOMETRY=AS_WKT")
            if (forExcel) add("CREATE_CSVT=YES") else add("WRITE_BOM=YES")
        }
        convert(project, listOf(layer.name), "CSV", file, *options.toTypedArray())
        if (!point) return
        // GDAL names them X and Y; in China X is the north of a projected coordinate, so they say which is which.
        val projected = layer.srs?.let { runCatching { (Crs.describe(it)["projected"] as? JsonPrimitive)?.booleanOrNull }.getOrNull() } == true
        val (east, north) = when {
            projected && language == "zh" -> "Y（东）" to "X（北）"
            projected -> "Easting" to "Northing"
            language == "zh" -> "经度" to "纬度"
            else -> "Longitude" to "Latitude"
        }
        val text = file.readText(Charsets.UTF_8)
        val bom = if (text.startsWith(BOM)) BOM else ""
        val body = text.removePrefix(bom)
        val header = body.substringBefore('\n')
        require(header.startsWith("X,Y")) { "unexpected CSV header: $header" }
        file.writeText(bom + "$east,$north" + body.removePrefix("X,Y"), Charsets.UTF_8)
    }

    // [layers] as an Excel workbook at [out], a sheet each, made from CSV that keeps the columns' types.
    private fun excel(project: Project, layers: List<Project.Layer>, out: File, language: String, work: File) {
        val taken = mutableSetOf<String>()
        for ((i, layer) in layers.withIndex()) {
            val csv = File(work, "sheet$i.csv")
            csv(project, layer, csv, language, forExcel = true)
            // A sheet's name: at most 31 characters, none of : \ / ? * [ ], one of its own.
            val base = layer.name.replace(Regex("""[:\\/?*\[\]]"""), "_").take(31).ifBlank { "Sheet" }
            var sheet = base
            var n = 2
            while (!taken.add(sheet.lowercase())) sheet = base.take(31 - " $n".length) + " ${n++}"
            val update = if (i > 0) arrayOf("--update") else emptyArray()
            GeoCrewNative.run(arrayOf("vector", "convert", "--output-format", "XLSX", "--output-layer", sheet) + update + arrayOf(csv.path, out.path))
        }
    }

    private fun convert(project: Project, layers: List<String>, driver: String, out: File, vararg options: String) {
        val inputs = layers.flatMap { listOf("--input-layer", it) }
        val creation = options.flatMap { listOf("--lco", it) }
        GeoCrewNative.run(arrayOf("vector", "convert", "--oo", "LIST_ALL_TABLES=NO") + inputs + creation + arrayOf("--output-format", driver, project.file.path, out.path))
    }

    // The files in [folder] as a zip at [out], by their paths in it.
    private fun zip(folder: File, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (file in folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
                zip.putNextEntry(ZipEntry(file.relativeTo(folder).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    // A new file named after the project (and the layer), and the time.
    private fun next(project: Project, extension: String, layer: String? = null): File {
        dir.mkdirs()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val base = listOfNotNull(project.name, layer?.let(::safe)).joinToString("-")
        var out = File(dir, "$base-$stamp.$extension")
        var n = 2
        while (out.exists()) out = File(dir, "$base-$stamp-${n++}.$extension")
        return out
    }

    private companion object {
        // The byte order mark CSV starts with, for Excel.
        const val BOM = "\uFEFF"
    }

    // [name] with the characters no file name takes as "_".
    private fun safe(name: String): String = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
}
