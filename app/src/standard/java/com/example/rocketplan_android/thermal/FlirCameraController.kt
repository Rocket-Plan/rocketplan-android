package com.example.rocketplan_android.thermal

import android.content.Context
import android.opengl.GLSurfaceView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * Stub FlirCameraController for devices without FLIR SDK support.
 * All methods are no-ops or return empty/default values.
 */
class FlirCameraController(context: Context) {

    data class StreamSelection(val index: Int, val count: Int, val isThermal: Boolean)

    private val _state = MutableStateFlow<FlirState>(FlirState.Error("FLIR not supported on this device"))
    val state: StateFlow<FlirState> = _state

    val snapshots: Flow<File> = emptyFlow()
    val errors: Flow<String> = emptyFlow()

    fun attachSurface(glSurfaceView: GLSurfaceView) {
        // No-op: FLIR not supported
    }

    fun setPalette(index: Int) {
        // No-op: FLIR not supported
    }

    fun setFusionMode(mode: FusionMode) {
        // No-op: FLIR not supported
    }

    fun setMeasurementsEnabled(enabled: Boolean) {
        // No-op: FLIR not supported
    }

    fun startDiscovery() {
        // No-op: FLIR not supported
    }

    fun requestSnapshot() {
        // No-op: FLIR not supported
    }

    fun disconnect() {
        // No-op: FLIR not supported
    }

    fun onResume() {
        // No-op: FLIR not supported
    }

    fun onPause() {
        // No-op: FLIR not supported
    }

    fun currentStreamSelection(): StreamSelection? {
        // No-op: FLIR not supported
        return null
    }

    fun cycleStream(offset: Int): StreamSelection? {
        // No-op: FLIR not supported
        return null
    }
}
