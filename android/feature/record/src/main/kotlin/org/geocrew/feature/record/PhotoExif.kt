package org.geocrew.feature.record

import android.media.ExifInterface
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.PhotoInfo
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What a camera wrote into a JPEG's EXIF: position, direction, time. */
internal object PhotoExif {
    private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun read(file: File): PhotoInfo? = runCatching {
        val exif = ExifInterface(file)
        val position = exif.latLong?.let { (lat, lng) ->
            if (lat == 0.0 && lng == 0.0) null
            else LngLat(lng, lat, exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE)?.let { exif.getAltitude(0.0) })
        }
        // Only a true-north direction is usable without knowing the declination there.
        val ref = exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF)
        val azimuth = if (ref == null || ref.equals("T", ignoreCase = true)) {
            exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION)?.let(::rational)?.takeIf { it.isFinite() }?.let(Geodesy::normalizeDegrees)
        } else null
        val taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { t ->
            runCatching {
                val local = LocalDateTime.parse(t.trim(), EXIF_TIME)
                val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                    ?.let { runCatching { ZoneOffset.of(it.trim()) }.getOrNull() }
                (offset?.let { OffsetDateTime.of(local, it).toInstant() } ?: local.atZone(ZoneId.systemDefault()).toInstant())
            }.getOrNull()
        }
        PhotoInfo(azimuth, position, taken).takeIf { !it.isEmpty }
    }.getOrNull()

    /** Degrees the image must be turned clockwise to show upright. */
    fun rotation(file: File): Int = runCatching {
        when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun rational(s: String): Double? {
        val parts = s.split('/')
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val n = parts[0].toDoubleOrNull()
                val d = parts[1].toDoubleOrNull()
                if (n == null || d == null || d == 0.0) null else n / d
            }
            else -> null
        }
    }
}
