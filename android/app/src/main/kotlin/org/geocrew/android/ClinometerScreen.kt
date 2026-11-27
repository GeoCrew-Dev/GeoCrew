package org.geocrew.android

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The compass-clinometer (charter: field tools): with the phone's back on a
 * plane, its dip direction from true north ([declination] added to the
 * compass) and its dip, the mean of the last moment's readings, shown in the
 * theme's colour while the hand is steady. [onRecord] takes the last steady
 * reading, so the tap on the round button does not spoil it (the current one
 * when there was none). Without [onRecord] it measures for the moment only
 * (from the measuring tools): the round button holds the reading, and lets
 * it go. The screen stays on while it is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClinometerScreen(declination: Float, onRecord: ((Attitude) -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val sensors = remember { context.getSystemService(SensorManager::class.java) }
    val rotationSensor = remember { sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    var reading by remember { mutableStateOf<Attitude?>(null) }
    var steady by remember { mutableStateOf(false) }
    var lastSteady by remember { mutableStateOf<Attitude?>(null) }
    var lowAccuracy by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<Attitude?>(null) }
    val window = remember { NormalWindow() }

    DisposableEffect(rotationSensor) {
        val magnetic = sensors?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val rotation = FloatArray(9)
        var shown = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                val now = SystemClock.elapsedRealtime()
                // The phone's z axis in east, north and up: the plane's normal while the phone's back lies on it.
                window.add(now, rotation[2].toDouble(), rotation[5].toDouble(), rotation[8].toDouble())
                // Shown ten times a second: enough to read, without redrawing for every reading.
                if (now - shown >= 100) {
                    shown = now
                    window.mean()?.let { m -> reading = attitudeOf(m[0], m[1], m[2], declination.toDouble()) }
                    steady = window.steady()
                    if (steady) lastSteady = reading
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) lowAccuracy = accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            }
        }
        rotationSensor?.let { sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetic?.let { sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        val wasOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            sensors?.unregisterListener(listener)
            view.keepScreenOn = wasOn
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.clino_title)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) } },
                )
            },
            floatingActionButton = {
                val enabled = reading != null
                // Round, at the bottom, like a shutter: records the reading, or holds it for the moment.
                LargeFloatingActionButton(
                    onClick = {
                        when {
                            !enabled -> Unit
                            onRecord != null -> (lastSteady ?: reading)?.let(onRecord)
                            held != null -> held = null
                            else -> held = lastSteady ?: reading
                        }
                    },
                    shape = CircleShape,
                    containerColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    when {
                        onRecord != null -> Icon(Icons.Rounded.Check, stringResource(R.string.clino_record), modifier = Modifier.size(36.dp))
                        held != null -> Icon(Icons.Rounded.LockOpen, stringResource(R.string.clino_release), modifier = Modifier.size(36.dp))
                        else -> Icon(Icons.Rounded.Lock, stringResource(R.string.clino_hold), modifier = Modifier.size(36.dp))
                    }
                }
            },
            floatingActionButtonPosition = FabPosition.Center,
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val shown = held ?: reading
                val color = if (held != null || steady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                // Flat, a plane dips nowhere: its dip direction is not given.
                val flat = shown == null || wholeDip(shown.dip) == 0
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp), modifier = Modifier.padding(top = 24.dp)) {
                    Readout(stringResource(R.string.clino_dip_direction), if (flat || shown == null) "—" else "${wholeDirection(shown.dipDirection)}°", color)
                    Readout(stringResource(R.string.clino_dip), shown?.let { "${wholeDip(it.dip)}°" } ?: "—", color)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.clino_strike, if (flat || shown == null) "—" else "${(wholeDirection(shown.dipDirection) + 270) % 360}°"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                when {
                    rotationSensor == null -> Text(stringResource(R.string.clino_no_sensor), color = MaterialTheme.colorScheme.error)
                    lowAccuracy -> Text(stringResource(R.string.clino_low_accuracy), color = MaterialTheme.colorScheme.error)
                }
                // Room under the readings for the round button.
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}

@Composable
private fun Readout(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.displayLarge, color = color)
    }
}
