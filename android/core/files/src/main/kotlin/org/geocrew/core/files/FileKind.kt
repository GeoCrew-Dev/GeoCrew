package org.geocrew.core.files

import java.io.File

/** What a file holds, as far as its name tells: what the file manager shows it as, and offers to do with it. */
enum class FileKind {
    FOLDER, VECTOR, RASTER, IMAGE, ARCHIVE, DOCUMENT, OTHER;

    companion object {
        /** The kind of [file]: a folder (a FileGDB folder is vector data), else by its extension. */
        fun of(file: File): FileKind = when {
            file.isDirectory -> if (file.extension.equals("gdb", ignoreCase = true)) VECTOR else FOLDER
            else -> BY_EXTENSION[file.extension.lowercase()] ?: OTHER
        }

        private val BY_EXTENSION: Map<String, FileKind> = buildMap {
            // A Shapefile's other parts (.shx, .dbf, .prj) are not layers of their own.
            for (e in listOf("gpkg", "shp", "geojson", "geojsonl", "kml", "kmz", "gpx", "dxf", "gml", "tab", "mif", "fgb", "sqlite", "osm")) put(e, VECTOR)
            for (e in listOf("tif", "tiff", "img", "jp2", "j2k", "asc", "hgt", "dem", "vrt", "mbtiles", "pmtiles", "nc", "ecw", "sid", "bil", "grd")) put(e, RASTER)
            for (e in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")) put(e, IMAGE)
            for (e in listOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")) put(e, ARCHIVE)
            for (e in listOf("pdf", "txt", "md", "csv", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "rtf", "html", "xml", "json")) put(e, DOCUMENT)
        }
    }
}

/** Names in the order people expect: digits by their value (D2 before D10), letters without regard to case. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val si = i
                while (i < a.length && a[i].isDigit()) i++
                val sj = j
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}
