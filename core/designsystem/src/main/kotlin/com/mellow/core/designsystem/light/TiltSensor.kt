package com.mellow.core.designsystem.light

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Which way is up, in the phone's own axes.
 *
 * [x] and [y] are the horizontal and vertical components of the "up" direction, each in
 * -1..1: held upright, (0, 1); laid flat on a table, (0, 0); rolled onto its right edge,
 * (-1, 0). The lighting engine uses this to keep its key light fixed in the room.
 *
 * Listens only between [start] and [stop]; the caller ties those to the resumed state so
 * the sensor is never left running behind a backgrounded app.
 */
internal class TiltSensor(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var x = 0f
        private set
    var y = RestingY
        private set

    fun start() {
        val sensor = sensor ?: return
        manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val targetX = (event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        val targetY = (event.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        x += (targetX - x) * Smoothing
        y += (targetY - y) * Smoothing
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** A phone held in the hand leans back a little. Used until the first reading. */
        const val RestingY = 0.7f
        private const val Smoothing = 0.14f
    }
}
