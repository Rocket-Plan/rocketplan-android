package com.example.rocketplan_android.thermal

data class FlirIdentity(val deviceId: String = "FLIR unsupported")
data class FlirCameraInformation(val model: String? = null)

enum class FusionMode {
    VISUAL_ONLY,
    THERMAL_ONLY,
    MSX,
    THERMAL_FUSION,
    PICTURE_IN_PICTURE,
    COLOR_NIGHT_VISION
}

sealed class FlirState {
    object Idle : FlirState()
    object Discovering : FlirState()
    data class Connecting(val identity: FlirIdentity) : FlirState()
    data class Streaming(val identity: FlirIdentity, val info: FlirCameraInformation?) : FlirState()
    data class Error(val message: String) : FlirState()
}
