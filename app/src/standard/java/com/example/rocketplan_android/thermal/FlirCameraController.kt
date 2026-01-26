package com.example.rocketplan_android.thermal

import android.content.Context
import android.opengl.GLSurfaceView
import android.graphics.SurfaceTexture
import com.example.rocketplan_android.logging.RemoteLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * Stub FlirCameraController for devices without FLIR SDK support.
 * All methods are no-ops or return empty/default values.
 */
@Suppress("UNUSED_PARAMETER")
class FlirCameraController(context: Context, remoteLogger: RemoteLogger) {

    data class StreamSelection(val index: Int, val count: Int, val isThermal: Boolean)

    enum class SurfaceOrder { DEFAULT, MEDIA_OVERLAY, ON_TOP }

    private val _state = MutableStateFlow<FlirState>(FlirState.Error("FLIR not supported on this device"))
    val state: StateFlow<FlirState> = _state

    val snapshots: Flow<File> = emptyFlow()
    val snapshotsWithVisual: Flow<FlirSnapshotResult> = emptyFlow()
    val errors: Flow<String> = emptyFlow()

    fun attachSurface(glSurfaceView: GLSurfaceView) {
        // No-op: FLIR not supported
    }

    fun attachSurface(glSurfaceView: GLSurfaceView, surfaceOrder: SurfaceOrder) {
        // No-op: FLIR not supported
    }

    fun attachTextureSurface(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        // No-op: FLIR not supported
    }

    fun updateTextureSurfaceSize(width: Int, height: Int) {
        // No-op: FLIR not supported
    }

    fun detachTextureSurface() {
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

    fun setExtractVisualEnabled(enabled: Boolean) {
        // No-op: FLIR not supported
    }

    fun disconnect(onComplete: (() -> Unit)? = null) {
        // No-op: FLIR not supported - complete immediately
        onComplete?.invoke()
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

    fun setOverlayFriendlyMode(enabled: Boolean) {
        // No-op: FLIR not supported
    }
}
