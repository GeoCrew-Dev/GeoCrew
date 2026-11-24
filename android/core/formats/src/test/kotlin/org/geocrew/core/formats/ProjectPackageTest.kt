package org.geocrew.core.formats

import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.MapViewport
import org.geocrew.core.model.ProjectConfig
import org.geocrew.core.model.Props
import org.geocrew.core.model.SyncInfo
import org.geocrew.core.model.TrackPoint
import org.geocrew.core.model.buildProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectPackageTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = Instant.parse("2026-11-24T08:00:00Z")
    private val sha = "a".repeat(64)

    private fun feature(id: String, kind: FeatureKind, g: Geometry, photos: List<String> = emptyList(), deleted: Boolean = false) = GeoFeature(
        id = id, projectId = "old", kind = kind, geometry = g,
        properties = buildProperties {
            this[Props.TITLE] = id
            if (photos.isNotEmpty()) strings(Props.PHOTOS, photos)
        },
        createdAt = t0, sync = SyncInfo(version = 7, updatedAt = t0, updatedBy = "m1", deleted = deleted, seq = 42),
    )

    @Test
    fun roundTripsRecordsTracksPhotosAndSettings() {
        val photo = tmp.newFile("p.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val track = feature("t1", FeatureKind.TRACK, Geometry.LineString(listOf(LngLat(110.0, 30.0), LngLat(110.001, 30.001))))
        val point = feature("p1", FeatureKind.POINT, Geometry.Point(LngLat(110.0, 30.0, 512.0)), photos = listOf(sha))
        val gone = feature("x", FeatureKind.POINT, Geometry.Point(LngLat(1.0, 1.0)), deleted = true)
        val pts = listOf(
            TrackPoint(LngLat(110.0, 30.0, 500.0), t0, 4f, 1.2f, 90f),
            TrackPoint(LngLat(110.001, 30.001), t0.plusSeconds(5)),
        )
        val manifest = PackageManifest(
            name = "北山填图", createdAt = t0.toString(), exportedAt = t0.toString(),
            config = ProjectConfig(baseSourceId = "osm", view = MapViewport(110.0, 30.0, 14.0)),
        )
        val out = ByteArrayOutputStream()
        val n = ProjectPackage.write(out, manifest, listOf(track, point, gone), { if (it == "t1") pts else emptyList() }) {
            if (it == sha) photo else null
        }
        assertEquals(1, n)

        val received = mutableMapOf<String, ByteArray>()
        var next = 0
        val c = ProjectPackage.read(ByteArrayInputStream(out.toByteArray()), ImportContext("new", newId = { "n${next++}" })) { s, data ->
            received[s] = data.readBytes()
        }
        assertEquals(manifest.copy(), c.manifest)
        assertEquals(2, c.features.size)
        c.features.forEach {
            assertEquals("new", it.projectId)
            assertTrue(it.id.startsWith("n"))
            assertEquals(1L, it.sync.version)
            assertTrue(it.sync.dirty)
        }
        val newTrack = c.features.single { it.kind == FeatureKind.TRACK }
        assertNotEquals("t1", newTrack.id)
        assertEquals(pts, c.tracks[newTrack.id])
        assertEquals(listOf(sha), c.features.single { it.kind == FeatureKind.POINT }.photos)
        assertEquals(listOf<Byte>(1, 2, 3), received[sha]!!.toList())
    }

    @Test
    fun rejectsZipsThatAreNotPackages() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("features.geojson")); z.write("{\"type\":\"FeatureCollection\",\"features\":[]}".toByteArray()); z.closeEntry()
        }
        try {
            ProjectPackage.read(ByteArrayInputStream(out.toByteArray()), ImportContext("p")) { _, _ -> }
            fail("expected FormatException")
        } catch (e: FormatException) {
            assertTrue(e.message!!.contains("project.json"))
        }
    }
}
