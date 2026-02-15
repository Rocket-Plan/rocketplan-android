package com.example.rocketplan_android.thermal

import android.content.Context
import com.flir.thermalsdk.androidsdk.ThermalSdkAndroid
import com.flir.thermalsdk.log.ThermalLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralizes FLIR SDK initialization so it happens once at app start.
 */
object FlirSdkManager {
    private val initialized = AtomicBoolean(false)

    val isAvailable: Boolean get() = initialized.get()

    fun init(context: Context) {
        if (initialized.get()) return
        try {
            ThermalSdkAndroid.init(
                context.applicationContext,
                ThermalLog.LogLevel.DEBUG,
                /* logToFile = */ null,
                /* useOpenCL = */ true
            )
            initialized.set(true)
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("FlirSdkManager", "FLIR native libraries not available on this device architecture", e)
        } catch (e: Throwable) {
            android.util.Log.e("FlirSdkManager", "Failed to initialize FLIR SDK", e)
        }
    }
}
