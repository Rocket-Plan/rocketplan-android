package com.example.rocketplan_android.thermal

import android.content.Context
import android.opengl.GLSurfaceView
import com.flir.thermalsdk.ErrorCode
import com.flir.thermalsdk.image.Palette
import com.flir.thermalsdk.image.PaletteManager
import com.flir.thermalsdk.image.TemperatureUnit
import com.flir.thermalsdk.image.ThermalValue
import com.flir.thermalsdk.image.fusion.Fusion
import com.flir.thermalsdk.image.fusion.FusionMode
import com.flir.thermalsdk.image.measurements.MeasurementShapeCollection
import com.flir.thermalsdk.image.measurements.MeasurementSpot
import com.flir.thermalsdk.live.Camera
import com.flir.thermalsdk.live.CameraInformation
import com.flir.thermalsdk.live.CameraType
import com.flir.thermalsdk.live.CommunicationInterface
import com.flir.thermalsdk.live.ConnectParameters
import com.flir.thermalsdk.live.Identity
import com.flir.thermalsdk.live.discovery.DiscoveredCamera
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener
import com.flir.thermalsdk.live.discovery.DiscoveryFactory
import com.flir.thermalsdk.live.streaming.Stream
import com.flir.thermalsdk.log.ThermalLog
import com.flir.thermalsdk.utils.FileUtils
import com.flir.thermalsdk.utils.Pair
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class FlirState {
    object Idle : FlirState()
    object Discovering : FlirState()
    data class Connecting(val identity: Identity) : FlirState()
    data class Streaming(val identity: Identity, val info: CameraInformation?) : FlirState()
    data class Error(val message: String) : FlirState()
}

/**
 * Handles discovery, connection, streaming, and snapshot capture for FLIR ACE cameras.
 * This is a Kotlin translation of the AceCamera sample logic so UI code can stay lean.
 */
class FlirCameraController(
    context: Context,
    private val communicationInterface: CommunicationInterface = CommunicationInterface.ACE
) {
    companion object {
        private const val TAG = "FlirCameraController"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<FlirState>(FlirState.Idle)
    val state: StateFlow<FlirState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _snapshots = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val snapshots: SharedFlow<File> = _snapshots.asSharedFlow()

    private var camera: Camera? = null
    private var activeStream: Stream? = null
    private var glSurfaceView: GLSurfaceView? = null
    @Volatile private var delayedSetSurface = false
    @Volatile private var delayedSurfaceWidth = 0
    @Volatile private var delayedSurfaceHeight = 0

    private var currentPalette: Palette = PaletteManager.getDefaultPalettes().first()
    private var currentFusionMode: FusionMode = FusionMode.THERMAL_ONLY
    private var enableMeasurements: Boolean = false
    private val snapshotRequested = AtomicBoolean(false)

    private val imagesRoot: String by lazy {
        val dir = File(appContext.filesDir, "flir_snapshots")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir.absolutePath
    }

    fun attachSurface(glSurfaceView: GLSurfaceView) {
        this.glSurfaceView = glSurfaceView
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setPreserveEGLContextOnPause(false)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    fun setPalette(index: Int) {
        currentPalette = PaletteManager.getDefaultPalettes()
            .getOrElse(index) { PaletteManager.getDefaultPalettes().first() }
    }

    fun setFusionMode(mode: FusionMode) {
        currentFusionMode = mode
    }

    fun setMeasurementsEnabled(enabled: Boolean) {
        enableMeasurements = enabled
    }

    fun requestSnapshot() {
        ThermalLog.d(TAG, "requestSnapshot() called, setting snapshotRequested=true")
        snapshotRequested.set(true)
    }

    fun startDiscovery() {
        _state.value = FlirState.Discovering
        ThermalLog.d(TAG, "Starting discovery on $communicationInterface")
        DiscoveryFactory.getInstance().scan(object : DiscoveryEventListener {
            override fun onCameraFound(discoveredCamera: DiscoveredCamera) {
                val foundIdentity = discoveredCamera.identity
                ThermalLog.d(TAG, "Found camera: $foundIdentity")
                if (foundIdentity.cameraType == CameraType.ACE &&
                    foundIdentity.communicationInterface == communicationInterface
                ) {
                    DiscoveryFactory.getInstance().stop(communicationInterface)
                    connect(foundIdentity)
                }
            }

            override fun onDiscoveryError(
                communicationInterface: CommunicationInterface,
                error: ErrorCode
            ) {
                val message = "Discovery error: $error"
                ThermalLog.e(TAG, message)
                _state.value = FlirState.Error(message)
                _errors.tryEmit(message)
            }
        }, communicationInterface)
    }

    private fun connect(identity: Identity) {
        _state.value = FlirState.Connecting(identity)
        scope.launch {
            try {
                ThermalLog.d(TAG, "Connecting to ${identity.deviceId}")
                if (camera == null) {
                    camera = Camera()
                }
                camera?.connect(
                    identity,
                    { error ->
                        val message = "Connection error: $error"
                        ThermalLog.e(TAG, message)
                        _state.value = FlirState.Error(message)
                        _errors.tryEmit(message)
                    },
                    ConnectParameters()
                )

                val info = camera?.remoteControl?.cameraInformation()?.sync
                ThermalLog.d(TAG, "Camera connected: $info")
                startStream(identity, info)
            } catch (io: IOException) {
                val message = "Connection error: ${io.message}"
                ThermalLog.e(TAG, message)
                _state.value = FlirState.Error(message)
                _errors.tryEmit(message)
            }
        }
    }

    private fun startStream(identity: Identity, info: CameraInformation?) {
        val cam = camera ?: return
        ThermalLog.d(TAG, "Preparing stream...")
        activeStream = cam.streams.firstOrNull { it.isThermal } ?: cam.streams.firstOrNull()
        val stream = activeStream
        if (stream == null) {
            val message = "No FLIR stream available from camera"
            ThermalLog.e(TAG, message)
            _state.value = FlirState.Error(message)
            _errors.tryEmit(message)
            return
        }
        if (!stream.isThermal) {
            ThermalLog.w(TAG, "Selected stream is not thermal; falling back to first available")
        }

        runOnGlThread {
            cam.glSetupPipeline(stream, true)
            if (delayedSetSurface) {
                cam.glOnSurfaceChanged(delayedSurfaceWidth, delayedSurfaceHeight)
                delayedSetSurface = false
            }
        }
        _state.value = FlirState.Streaming(identity, info)

        stream.start(
            {
                glSurfaceView?.requestRender()
            },
            { error ->
                val message = "Stream error: $error"
                ThermalLog.w(TAG, message)
                _errors.tryEmit(message)
            }
        )
        glSurfaceView?.requestRender()
    }

    fun disconnect() {
        scope.launch {
            ThermalLog.d(TAG, "disconnect()")
            activeStream?.stop()
            runOnGlThread { camera?.glTeardownPipeline() }
            camera?.disconnect()
            camera = null
            activeStream = null
            _state.value = FlirState.Idle
            DiscoveryFactory.getInstance().stop(communicationInterface)
        }
    }

    fun onResume() {
        glSurfaceView?.onResume()
    }

    fun onPause() {
        glSurfaceView?.onPause()
    }

    private val renderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            ThermalLog.d(TAG, "onSurfaceCreated()")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            ThermalLog.d(TAG, "onSurfaceChanged(), width=$width, height=$height")
            val cam = camera
            if (cam != null) {
                cam.glOnSurfaceChanged(width, height)
                delayedSetSurface = false
            } else {
                delayedSetSurface = true
                delayedSurfaceWidth = width
                delayedSurfaceHeight = height
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            val cam = camera ?: return
            if (!cam.glIsGlContextReady()) {
                ThermalLog.w(TAG, "GL context not ready, skipping frame")
                return
            }

            if (delayedSetSurface) {
                cam.glOnSurfaceChanged(delayedSurfaceWidth, delayedSurfaceHeight)
                delayedSetSurface = false
            }

            cam.glWithThermalImage { thermalImage ->
                thermalImage.palette = currentPalette
                val fusion: Fusion? = thermalImage.fusion
                fusion?.setFusionMode(currentFusionMode)

                if (enableMeasurements) {
                    val measurements: MeasurementShapeCollection = thermalImage.measurements
                    var spots: MutableList<MeasurementSpot> = measurements.spots
                    if (spots.size < 3) {
                        val w = thermalImage.width
                        val h = thermalImage.height
                        measurements.addSpot(w / 3, h / 3)
                        measurements.addSpot(w / 2, h / 2)
                        measurements.addSpot(w * 2 / 3, h * 2 / 3)
                    }
                    spots = measurements.spots
                    var index = 0
                    for (spot in spots) {
                        ThermalLog.d(TAG, "Spot $index : $spot")
                        index++
                    }
                }

                if (snapshotRequested.compareAndSet(true, false)) {
                    ThermalLog.d(TAG, "Processing snapshot request in onDrawFrame")
                    thermalImage.temperatureUnit = TemperatureUnit.CELSIUS
                    val range: Pair<ThermalValue, ThermalValue> = cam.glGetScaleRange()
                    ThermalLog.d(TAG, "glGetScaleRange when storing image: ${range.first} - ${range.second}")
                    thermalImage.scale.setRange(range.first, range.second)

                    val snapshotPath = FileUtils.prepareUniqueFileName(imagesRoot, "ACE_", "jpg")
                    ThermalLog.d(TAG, "Saving snapshot to: $snapshotPath")
                    try {
                        thermalImage.saveAs(snapshotPath)
                        ThermalLog.d(TAG, "Snapshot stored under: $snapshotPath")
                        val emitted = _snapshots.tryEmit(File(snapshotPath))
                        ThermalLog.d(TAG, "Snapshot emitted to flow: $emitted")
                    } catch (e: IOException) {
                        val message = "Unable to take snapshot: ${e.message}"
                        ThermalLog.e(TAG, message)
                        _errors.tryEmit(message)
                    }
                }
            }

            cam.glOnDrawFrame()
        }
    }

    private fun runOnGlThread(block: () -> Unit) {
        val surface = glSurfaceView
        if (surface == null) {
            ThermalLog.w(TAG, "GLSurfaceView not attached; dropping GL action")
            return
        }
        surface.queueEvent(block)
    }
}
