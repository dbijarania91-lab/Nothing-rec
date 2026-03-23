package com.nothingrecorder.utils

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ThermalMonitor(
    private val context: Context,
    private val onTemperature: (Float) -> Unit
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var future: ScheduledFuture<*>? = null

    // Read thermal status via PowerManager thermal headroom API (Android 11+)
    fun start() {
        future = scheduler.scheduleAtFixedRate({
            try {
                // getThermalHeadroom returns 0.0-1.0 where 1.0 = throttling
                val headroom = powerManager.getThermalHeadroom(30)
                // Convert headroom to approximate Celsius (Nothing Phone 1 typical range)
                val estimatedTemp = 28f + (headroom * 20f)
                onTemperature(estimatedTemp)
            } catch (_: Exception) {
                // Thermal API not available — ignore
            }
        }, 0, 5, TimeUnit.SECONDS)
    }

    fun stop() {
        future?.cancel(false)
    }
}
