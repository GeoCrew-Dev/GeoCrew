package org.geocrew.core.formats

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.ProjectConfig
import org.geocrew.core.model.TrackPoint
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** `project.json` of a project package. */
@Serializable
data class PackageManifest(
    val format: String = ProjectPackage.FORMAT,
    val version: Int = ProjectPackage.VERSION,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("exported_at") val exportedAt: String,
    val config: ProjectConfig = ProjectConfig(),
    /** App that wrote the package, for support questions only. */
    val generator: String = "",
)

/** What [ProjectPackage.read] found. Feature ids are already the new, local ones. */
data class PackageContents(
    val manifest: PackageManifest,
    val features: List<GeoFeature>,
    val tracks: Map<String, List<TrackPoint>>,
    val warnings: List<String>,
)

/**
 * A whole project in one zip file, for backups and for handing a project
 * to someone else:
 *
 * ```
 * project.json          name, dates, project settings
 * features.geojson      every live record (points, lines, areas, tracks)
 * tracks/<id>.csv       recorded fixes of each track: time,lng,lat,alt,accuracy,speed,bearing
 * photos/<sha256>.jpg   photos, named by content hash as the records refer to them
 * ```
 *
 * Workspace links and credentials are never included. Reading gives every
 * record a new id, so a package can be imported any number of times.
 */
object ProjectPackage {
    const val FORMAT = "geocrew-project"
    const val VERSION = 1
    const val EXTENSION = "geocrew.zip"
    const val MIME = "application/zip"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val PHOTO = Regex("^photos/([0-9a-f]{64})\\.jpg$")
    private val TRACK = Regex("^tracks/([A-Za-z0-9._-]{1,64})\\.csv$")
    private const val MAX_TEXT = 256L shl 20

    /** Writes the package and returns how many photos went in. Missing photo files are skipped. */
    fun write(
        output: OutputStream,
        manifest: PackageManifest,
        features: List<GeoFeature>,
        trackPoints: (featureId: String) -> List<TrackPoint>,
        photoFile: (sha256: String) -> File?,
    ): Int {
        val live = features.filterNot { it.deleted }
        var photos = 0
        ZipOutputStream(output).use { zip ->
            zip.text("project.json", json.encodeToString(PackageManifest.serializer(), manifest))
            zip.text("features.geojson", GeoJson.encodeCollection(live).toString())
            for (f in live) {
                val pts = trackPoints(f.id)
                if (pts.isEmpty()) continue
                zip.text("tracks/${f.id}.csv", buildString {
                    appendLine("time,lng,lat,alt,accuracy,speed,bearing")
                    pts.forEach { p ->
                        appendLine(listOf(p.time, p.position.lng, p.position.lat, p.position.alt, p.accuracy, p.speed, p.bearing)
                            .joinToString(",") { it?.toString() ?: "" })
                    }
                })
            }
            for (sha in live.flatMap { it.photos }.distinct()) {
                val file = photoFile(sha)?.takeIf { it.isFile } ?: continue
                // Photos are already compressed; storing them saves time on the phone.
                zip.putNextEntry(ZipEntry("photos/$sha.jpg"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                photos++
            }
        }
        return photos
    }

    /**
     * Reads a package. Photos are handed to [photo] as they are met, by the
     * hash they are filed under; records are returned with new ids.
     */
    fun read(input: InputStream, ctx: ImportContext, photo: (sha256: String, data: InputStream) -> Unit): PackageContents {
        var manifest: PackageManifest? = null
        var features: List<GeoFeature> = emptyList()
        val rawTracks = mutableMapOf<String, List<TrackPoint>>()
        val warnings = mutableListOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                when {
                    name == "project.json" -> manifest = runCatching {
                        json.decodeFromString(PackageManifest.serializer(), zip.textOf())
                    }.getOrElse { throw FormatException("project.json 无法读取", it) }
                    name == "features.geojson" -> {
                        val r = GeoJson.decode(zip.textOf(), ctx)
                        features = r.features
                        warnings += r.warnings
                    }
                    TRACK.matches(name) -> {
                        val id = TRACK.find(name)!!.groupValues[1]
                        rawTracks[id] = parseTrack(zip.textOf(), warnings, id)
                    }
                    PHOTO.matches(name) -> photo(PHOTO.find(name)!!.groupValues[1], NonClosing(zip))
                    else -> warnings += "忽略了不认识的文件 $name"
                }
            }
        }
        val m = manifest ?: throw FormatException("不是同勘工程包：缺少 project.json")
        if (m.format != FORMAT) throw FormatException("不是同勘工程包（format=${m.format}）")
        if (m.version > VERSION) warnings += "工程包由较新版本生成（版本 ${m.version}），部分内容可能未导入"

        // Fresh ids so the same package can be imported again next to the original.
        val ids = features.associate { it.id to ctx.newId() }
        val renamed = features.filterNot { it.deleted }.map { f ->
            f.copy(id = ids.getValue(f.id), sync = f.sync.copy(version = 1, seq = null, updatedBy = null, dirty = true))
        }
        val tracks = rawTracks.mapNotNull { (old, pts) -> ids[old]?.let { it to pts } }.toMap()
        return PackageContents(m, renamed, tracks, warnings)
    }

    private fun parseTrack(text: String, warnings: MutableList<String>, id: String): List<TrackPoint> {
        val out = mutableListOf<TrackPoint>()
        text.lineSequence().drop(1).filter { it.isNotBlank() }.forEach { line ->
            val c = line.split(',')
            val p = runCatching {
                TrackPoint(
                    position = LngLat(c[1].toDouble(), c[2].toDouble(), c.getOrNull(3)?.toDoubleOrNull()),
                    time = Instant.parse(c[0]),
                    accuracy = c.getOrNull(4)?.toFloatOrNull(),
                    speed = c.getOrNull(5)?.toFloatOrNull(),
                    bearing = c.getOrNull(6)?.toFloatOrNull(),
                )
            }.getOrNull()
            if (p != null) out += p
        }
        if (out.isEmpty()) warnings += "轨迹 $id 没有可用的点"
        return out
    }

    private fun ZipOutputStream.text(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipInputStream.textOf(): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_TEXT) throw FormatException("工程包中的文本文件过大")
            bytes.write(buf, 0, n)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    /** Lets a consumer read one entry without closing the whole zip. */
    private class NonClosing(private val inner: InputStream) : java.io.FilterInputStream(inner) {
        override fun close() = Unit
    }
}

