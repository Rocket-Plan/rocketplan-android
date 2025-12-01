package com.example.rocketplan_android.thermal

import android.content.Context

/**
 * Stub FlirSdkManager for devices without FLIR SDK support.
 */
object FlirSdkManager {
    fun init(context: Context) {
        // No-op: FLIR not supported on this device
    }
}
