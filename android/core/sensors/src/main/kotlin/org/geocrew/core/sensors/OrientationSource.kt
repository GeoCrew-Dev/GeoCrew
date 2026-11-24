package org.geocrew.core.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.LngLat
import javax.inject.Inject
import javax.inject.Singleton

/** Device orientation, ready for the compass and the attitude (产状) tool. */
data class Orientation(
    /** Direction the top of the phone points, degrees from magnetic north. */
    val azimuthMagnetic: Double,
    /** Plane the phone's back lies on (magnetic north reference). */
    val plane: Attitude,
    /** SensorManager accuracy constant of the magnetometer. */
    val accuracy: Int,
) {
    val isUnreliable get() = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW

    fun azimuthTrue(declination: Double) = Geodesy.normalizeDegrees(azimuthMagnetic + declination)
    fun planeTrue(declination: Double) = Attitude.of(plane.dipDirection + declination, plane.dip)
}

@Singleton
class OrientationSource @Inject constructor(@ApplicationContext context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)

    val available: Boolean
        get() = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
            (sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null && sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null)

    fun orientation(): Flow<Orientation> = callbackFlow {
        val manager = sm ?: run { close(); return@callbackFlow }
        val rotation = FloatArray(9)
        val angles = FloatArray(3)
        val gravity = FloatArray(3)
        val magnetic = FloatArray(3)
        var hasG = false
        var hasM = false
        var magAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

        fun emit() {
            SensorManager.getOrientation(rotation, angles)
            val az = Geodesy.normalizeDegrees(Math.toDegrees(angles[0].toDouble()))
            // Third column of R: device z axis in (east, north, up).
            val plane = Attitude.fromDeviceNormal(rotation[2].toDouble(), rotation[5].toDouble(), rotation[8].toDouble())
            trySend(Orientation(az, plane, magAccuracy))
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotation, e.values)
                        emit()
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        lowPass(e.values, gravity); hasG = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        lowPass(e.values, magnetic); hasM = true
                    }
                }
                if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR && hasG && hasM &&
                    SensorManager.getRotationMatrix(rotation, null, gravity, magnetic)
                ) emit()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD || sensor.type == Sensor.TYPE_ROTATION_VECTOR) magAccuracy = accuracy
            }
        }
        val accuracyOnly = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = Unit
            override fun onAccuracyChanged(s: Sensor, a: Int) {
                magAccuracy = a
            }
        }
        val rv = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rv != null) {
            manager.registerListener(listener, rv, SensorManager.SENSOR_DELAY_UI)
            // The rotation vector hides magnetometer accuracy; listen for it separately.
            manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                manager.registerListener(accuracyOnly, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } else {
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
            manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        }
        awaitClose {
            manager.unregisterListener(listener)
            manager.unregisterListener(accuracyOnly)
        }
    }

    private fun lowPass(input: FloatArray, out: FloatArray, alpha: Float = 0.15f) {
        for (i in input.indices) out[i] = out[i] + alpha * (input[i] - out[i])
    }

    /** Magnetic declination at a place, from the World Magnetic Model built into Android. */
    fun declinationAt(p: LngLat, timeMillis: Long = System.currentTimeMillis()): Double =
        GeomagneticField(p.lat.toFloat(), p.lng.toFloat(), (p.alt ?: 0.0).toFloat(), timeMillis).declination.toDouble()
}
