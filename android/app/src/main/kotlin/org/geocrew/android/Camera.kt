package org.geocrew.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.Location
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A photo just taken: its file in the app's cache, and what [photoMeta] says of it. */
data class Shot(val file: File, val meta: JsonObject)

/** Whether the app may use the camera. */
fun mayUseCamera(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * What a photo's attachment keeps besides the file (protocol v1 9.1 "meta"):
 * where the camera looked, in degrees clockwise from true north when the
 * phone's position gives the declination, else from magnetic north; where the
 * phone was, how sure that was and when that position was found ([located]);
 * and when it was taken, in UTC.
 */
fun photoMeta(
    magneticHeading: Float?,
    declination: Float?,
    longitude: Double?,
    latitude: Double?,
    accuracy: Float?,
    taken: Instant,
    located: Instant? = null,
): JsonObject =
    buildJsonObject {
        if (magneticHeading != null) {
            // To a tenth of a degree, in 0 to 360: 359.97 is 0.0, not 360.0.
            fun degrees(d: Float) = (Math.round(((d % 360 + 360) % 360) * 10) % 3600) / 10.0
            if (declination != null) put("azimuth", degrees(magneticHeading + declination)) else put("magneticAzimuth", degrees(magneticHeading))
        }
        if (longitude != null && latitude != null) {
            put("longitude", longitude)
            put("latitude", latitude)
            accuracy?.let { put("accuracy", Math.round(it * 10) / 10.0) }
            located?.let { put("located", it.truncatedTo(ChronoUnit.SECONDS).toString()) }
        }
        put("taken", taken.toString())
    }

/**
 * The app's own camera (ADR 0011, CameraX): the back camera's picture over the
 * whole screen, a shutter and a close button. A photo is written to the app's
 * cache, with the phone's position in its EXIF too, and handed to [onTaken];
 * the heading and position are read while the camera is open, the position
 * falling back to the phone's last one of the past two minutes. The caller
 * has the camera permission.
 */
@Composable
fun CameraScreen(onTaken: (Shot) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val heading by rememberHeading(true)
    val position by rememberPhonePosition(mayLocate(context))
    var taking by remember { mutableStateOf(false) }
    // Closed while a photo was being written: that photo is not wanted.
    var closed by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    DisposableEffect(lifecycle) {
        controller.bindToLifecycle(lifecycle)
        // A phone or tablet with only a front camera uses that one.
        controller.initializationFuture.addListener({
            if (!controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) && controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                controller.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            }
        }, context.mainExecutor)
        onDispose { controller.unbind() }
    }

    fun shoot() {
        if (taking) return
        taking = true
        failed = false
        // Shot soon after the camera opened, before GNSS has given anything: the phone's last position, if recent.
        val at: Location? = position ?: lastLocation(context, maxAgeMs = 120_000)
        val file = photoFile(context)
        val metadata = ImageCapture.Metadata().apply { location = at }
        val meta = photoMeta(
            magneticHeading = heading,
            declination = at?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), it.time).declination },
            longitude = at?.longitude,
            latitude = at?.latitude,
            accuracy = at?.takeIf { it.hasAccuracy() }?.accuracy,
            taken = Instant.now().truncatedTo(ChronoUnit.SECONDS),
            located = at?.let { Instant.ofEpochMilli(it.time) },
        )
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build(),
            context.mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    taking = false
                    if (closed) file.delete() else onTaken(Shot(file, meta))
                }

                override fun onError(exception: ImageCaptureException) {
                    taking = false
                    failed = true
                    file.delete()
                }
            },
        )
    }

    fun close() {
        closed = true
        onDismiss()
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { PreviewView(it).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    this.controller = controller
                } },
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                IconButton(onClick = ::close, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = Color.White)
                }
                if (failed) {
                    Text(
                        stringResource(R.string.camera_failed),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp).background(Color.Black.copy(alpha = 0.6f)).padding(8.dp),
                    )
                }
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).size(76.dp).clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape).clickable(enabled = !taking) { shoot() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (taking) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    } else {
                        Box(Modifier.size(60.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }
        }
    }
}

// A file for a new photo in the app's cache, named as cameras name them.
private fun photoFile(context: Context): File {
    val folder = File(context.cacheDir, "incoming").apply { mkdirs() }
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    var file = File(folder, "IMG_$stamp.jpg")
    var n = 2
    while (file.exists()) file = File(folder, "IMG_${stamp}_${n++}.jpg")
    return file
}
