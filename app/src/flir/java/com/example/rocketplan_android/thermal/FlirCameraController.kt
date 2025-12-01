package com.example.rocketplan_android.thermal

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import com.flir.thermalsdk.ErrorCode
import com.flir.thermalsdk.image.Palette
import com.flir.thermalsdk.image.PaletteManager
import com.flir.thermalsdk.image.TemperatureUnit
import com.flir.thermalsdk.image.ThermalValue
import com.flir.thermalsdk.image.fusion.Fusion
import com.flir.thermalsdk.image.measurements.MeasurementShapeCollection
import com.flir.thermalsdk.image.measurements.MeasurementSpot
import com.flir.thermalsdk.live.Camera
import com.flir.thermalsdk.live.CameraType
import com.flir.thermalsdk.live.CommunicationInterface
import com.flir.thermalsdk.live.ConnectParameters
import com.flir.thermalsdk.live.discovery.DiscoveredCamera
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener
import com.flir.thermalsdk.live.discovery.DiscoveryFactory
import com.flir.thermalsdk.live.streaming.Stream
import com.flir.thermalsdk.log.ThermalLog
import com.flir.thermalsdk.utils.FileUtils
import com.flir.thermalsdk.utils.Pair
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    private fun resolveGlVersion(): Int {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val versionString = activityManager?.deviceConfigurationInfo?.glEsVersion ?: "2.0"
        val major = versionString.substringBefore('.').toIntOrNull() ?: 2
        val minor = versionString.substringAfter('.', "0").take(1).toIntOrNull() ?: 0
        return if (major > 3 || (major == 3 && minor >= 0)) 3 else 2
    }

    fun attachSurface(glSurfaceView: GLSurfaceView) {
        this.glSurfaceView = glSurfaceView
        val glVersion = resolveGlVersion()
        ThermalLog.d(TAG, "Using GLES $glVersion for FLIR surface")
        glSurfaceView.setEGLContextClientVersion(glVersion)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
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
        ThermalLog.d(TAG, "📸 requestSnapshot() called")
        ThermalLog.d(TAG, "📸 Current state: ${_state.value}")
        ThermalLog.d(TAG, "📸 Camera: ${if (camera != null) "connected" else "null"}")
        ThermalLog.d(TAG, "📸 Stream: ${if (activeStream != null) "active, isStreaming=${activeStream?.isStreaming}" else "null"}")
        ThermalLog.d(TAG, "📸 GL context ready: ${camera?.glIsGlContextReady()}")
        ThermalLog.d(TAG, "📸 GLSurfaceView: ${if (glSurfaceView != null) "attached" else "null"}")
        snapshotRequested.set(true)
        // Request a render to trigger onDrawFrame where snapshot is processed
        glSurfaceView?.requestRender()
        ThermalLog.d(TAG, "📸 snapshotRequested=true, requestRender() called")
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

    private fun connect(identity: FlirIdentity) {
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

                val info: FlirCameraInformation? = camera?.remoteControl?.cameraInformation()?.sync
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

    private fun startStream(identity: FlirIdentity, info: FlirCameraInformation?) {
        val cam = camera ?: return
        val surface = glSurfaceView
        if (surface == null) {
            val message = "No GL surface attached for FLIR stream"
            ThermalLog.e(TAG, message)
            _state.value = FlirState.Error(message)
            _errors.tryEmit(message)
            return
        }

        ThermalLog.d(TAG, "🎬 Preparing stream...")
        val allStreams = cam.streams
        ThermalLog.d(TAG, "🎬 Available streams: ${allStreams.size}")
        allStreams.forEachIndexed { index, s ->
            ThermalLog.d(TAG, "🎬   Stream[$index]: isThermal=${s.isThermal}, isStreaming=${s.isStreaming}")
        }

        activeStream = allStreams.firstOrNull { it.isThermal } ?: allStreams.firstOrNull()
        val stream = activeStream
        if (stream == null) {
            val message = "No FLIR stream available from camera"
            ThermalLog.e(TAG, message)
            _state.value = FlirState.Error(message)
            _errors.tryEmit(message)
            return
        }
        ThermalLog.d(TAG, "🎬 Selected stream: isThermal=${stream.isThermal}")
        if (!stream.isThermal) {
            ThermalLog.w(TAG, "🎬 Selected stream is not thermal; falling back to first available")
        }

        // Note: FLIR's camera app uses false, but we need true for visible preview
        // false = snapshot works but no visible preview
        // true = visible preview works
        ThermalLog.d(TAG, "🎬 Setting up GL pipeline...")
        val setupLatch = CountDownLatch(1)
        var setupError: Throwable? = null
        runOnGlThread {
            try {
                ThermalLog.d(TAG, "🎬 [GL Thread] glSetupPipeline(stream, true)")
                cam.glSetupPipeline(stream, true)
                ThermalLog.d(TAG, "🎬 [GL Thread] glSetupPipeline complete, glIsGlContextReady=${cam.glIsGlContextReady()}")
                if (delayedSetSurface) {
                    ThermalLog.d(TAG, "🎬 [GL Thread] Applying delayed surface change: ${delayedSurfaceWidth}x${delayedSurfaceHeight}")
                    cam.glOnSurfaceChanged(delayedSurfaceWidth, delayedSurfaceHeight)
                    delayedSetSurface = false
                }
            } catch (t: Throwable) {
                setupError = t
                ThermalLog.e(TAG, "🎬 [GL Thread] glSetupPipeline failed: ${t.message}")
            } finally {
                setupLatch.countDown()
            }
        }

        if (!setupLatch.await(1, TimeUnit.SECONDS)) {
            val message = "🎬 GL pipeline setup timed out"
            ThermalLog.e(TAG, message)
            _state.value = FlirState.Error(message)
            _errors.tryEmit(message)
            return
        }

        setupError?.let {
            val message = "🎬 Failed to set up GL pipeline: ${it.message}"
            ThermalLog.e(TAG, message)
            _state.value = FlirState.Error(message)
            _errors.tryEmit(message)
            return
        }

        _state.value = FlirState.Streaming(identity, info)

        ThermalLog.d(TAG, "🎬 Starting stream...")
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        stream.start(
            {
                surface.requestRender()
            },
            { error ->
                val message = "🎬 Stream error: $error"
                ThermalLog.w(TAG, message)
                _errors.tryEmit(message)
            }
        )
        ThermalLog.d(TAG, "🎬 Stream started, isStreaming=${stream.isStreaming}")
        surface.requestRender()
    }

    fun disconnect() {
        scope.launch {
            ThermalLog.d(TAG, "disconnect()")
            val currentCamera = camera
            activeStream?.stop()
            glSurfaceView?.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            // FLIR's Camera.glTeardownPipeline() has a 500ms sleep before teardown
            // We replicate that here to ensure GL resources are properly released
            val latch = CountDownLatch(1)
            runOnGlThread {
                try {
                    Thread.sleep(500)
                    currentCamera?.glTeardownPipeline()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    latch.countDown()
                }
            }
            // Best-effort wait so teardown completes before disconnect/cleanup
            if (!latch.await(1, TimeUnit.SECONDS)) {
                ThermalLog.w(TAG, "disconnect(): GL teardown did not finish in time")
            }
            currentCamera?.disconnect()
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

        private var frameCount = 0L
        private var lastFrameLogTime = 0L

        override fun onDrawFrame(gl: GL10?) {
            val cam = camera
            if (cam == null) {
                // Only log occasionally to avoid spam
                if (snapshotRequested.get()) {
                    ThermalLog.w(TAG, "🖼️ onDrawFrame: camera is null, snapshot pending!")
                }
                return
            }

            if (!cam.glIsGlContextReady()) {
                ThermalLog.w(TAG, "🖼️ onDrawFrame: GL context not ready, skipping frame")
                return
            }

            frameCount++
            val now = System.currentTimeMillis()
            // Log frame rate every 5 seconds
            if (now - lastFrameLogTime > 5000) {
                val surfaceValid = glSurfaceView?.holder?.surface?.isValid ?: false
                ThermalLog.d(
                    TAG,
                    "🖼️ onDrawFrame: frame #$frameCount, stream isStreaming=${activeStream?.isStreaming}, glReady=${cam.glIsGlContextReady()}, surfaceValid=$surfaceValid"
                )
                lastFrameLogTime = now
            }

            if (delayedSetSurface) {
                ThermalLog.d(TAG, "🖼️ Applying delayed surface: ${delayedSurfaceWidth}x${delayedSurfaceHeight}")
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
                    ThermalLog.d(TAG, "📸 [onDrawFrame] Processing snapshot request")
                    ThermalLog.d(TAG, "📸 [onDrawFrame] ThermalImage: ${thermalImage.width}x${thermalImage.height}")
                    thermalImage.temperatureUnit = TemperatureUnit.CELSIUS
                    val range: Pair<ThermalValue, ThermalValue> = cam.glGetScaleRange()
                    ThermalLog.d(TAG, "📸 [onDrawFrame] glGetScaleRange: ${range.first} - ${range.second}")
                    thermalImage.scale.setRange(range.first, range.second)

                    val snapshotPath = FileUtils.prepareUniqueFileName(imagesRoot, "ACE_", "jpg")
                    ThermalLog.d(TAG, "📸 [onDrawFrame] Saving to: $snapshotPath")
                    try {
                        thermalImage.saveAs(snapshotPath)
                        ThermalLog.d(TAG, "📸 [onDrawFrame] ✅ Snapshot saved: $snapshotPath")
                        val emitted = _snapshots.tryEmit(File(snapshotPath))
                        ThermalLog.d(TAG, "📸 [onDrawFrame] Emitted to flow: $emitted")
                    } catch (e: IOException) {
                        val message = "📸 [onDrawFrame] ❌ Unable to take snapshot: ${e.message}"
                        ThermalLog.e(TAG, message)
                        _errors.tryEmit(message)
                    }
                }
            }

            val rendered = cam.glOnDrawFrame()
            if (!rendered && frameCount % 30 == 0L) {
                ThermalLog.w(TAG, "🖼️ glOnDrawFrame returned false - frame not rendered")
            }
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
