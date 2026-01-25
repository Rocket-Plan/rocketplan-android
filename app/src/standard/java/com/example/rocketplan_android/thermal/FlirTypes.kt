package com.example.rocketplan_android.thermal

import java.io.File

data class FlirIdentity(val deviceId: String = "FLIR unsupported")
data class FlirCameraInformation(val model: String? = null)

enum class FusionMode {
    VISUAL_ONLY,
    THERMAL_ONLY,
    MSX,
    THERMAL_FUSION,
    PICTURE_IN_PICTURE,
    COLOR_NIGHT_VISION,
    BLENDING
}

sealed class FlirState {
    object Idle : FlirState()
    object Discovering : FlirState()
    data class Connecting(val identity: FlirIdentity) : FlirState()
    data class Streaming(val identity: FlirIdentity, val info: FlirCameraInformation?) : FlirState()
    data class Error(val message: String) : FlirState()
}

/**
 * Result of a FLIR snapshot capture, containing the thermal file
 * and optionally the extracted visual image file.
 */
data class FlirSnapshotResult(
    val thermalFile: File,
    val visualFile: File?
)
