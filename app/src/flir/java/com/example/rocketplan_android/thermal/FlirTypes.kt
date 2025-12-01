package com.example.rocketplan_android.thermal

import com.flir.thermalsdk.live.CameraInformation
import com.flir.thermalsdk.live.Identity

// Re-export FLIR SDK types for use in main source set
typealias FusionMode = com.flir.thermalsdk.image.fusion.FusionMode
typealias FlirIdentity = Identity
typealias FlirCameraInformation = CameraInformation

sealed class FlirState {
    object Idle : FlirState()
    object Discovering : FlirState()
    data class Connecting(val identity: FlirIdentity) : FlirState()
    data class Streaming(val identity: FlirIdentity, val info: FlirCameraInformation?) : FlirState()
    data class Error(val message: String) : FlirState()
}
