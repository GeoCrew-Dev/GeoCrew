package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PhotoInfoTest {
    private val info = PhotoInfo(235.04, LngLat(116.391, 39.907, 52.3), Instant.parse("2026-11-24T10:40:00Z"))

    @Test fun roundTripsThroughProperties() {
        val props = buildProperties {
            photoInfo("a", info)
            photoInfo("b", PhotoInfo(azimuth = 10.0))
        }
        val f = GeoFeature("f", "p", FeatureKind.POINT, null, props, Instant.EPOCH, SyncInfo(updatedAt = Instant.EPOCH))
        assertEquals(info.copy(azimuth = 235.0), f.photoInfo["a"])
        assertEquals(10.0, f.photoInfo["b"]!!.azimuth!!, 0.0)
        // Removing the last entries drops the key altogether.
        val cleared = props.edit { photoInfo("a", null); photoInfo("b", PhotoInfo()) }
        assertNull(cleared[Props.PHOTO_INFO])
    }

    @Test fun ignoresBadValues() {
        val o = kotlinx.serialization.json.buildJsonObject {
            put("lon", kotlinx.serialization.json.JsonPrimitive(200.0))
            put("lat", kotlinx.serialization.json.JsonPrimitive(30.0))
            put("azimuth", kotlinx.serialization.json.JsonPrimitive(-90.0))
            put("taken_at", kotlinx.serialization.json.JsonPrimitive("yesterday"))
        }
        val p = PhotoInfo.fromJson(o)
        assertNull(p.position)
        assertNull(p.takenAt)
        assertEquals(270.0, p.azimuth!!, 0.0)
    }

    @Test fun mergesAndStamps() {
        val merged = PhotoInfo(azimuth = 90.0).orElse(info)
        assertEquals(90.0, merged.azimuth!!, 0.0)
        assertEquals(info.position, merged.position)
        val lines = info.stamp("D012", "秦岭填图", ZoneId.of("Asia/Shanghai"))
        assertEquals("D012 · 秦岭填图", lines[0])
        assertTrue(lines[1], lines[1].endsWith("海拔 52 m"))
        assertEquals("拍摄方向 235°（西南）", lines[2])
        assertEquals("2026-11-24 18:40", lines[3])
        assertEquals(listOf("拍摄方向 10°（北）"), PhotoInfo(azimuth = 10.0).stamp(null, "", ZoneId.of("UTC")))
    }
}
