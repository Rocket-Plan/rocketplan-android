package com.example.rocketplan_android.thermal

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.Log
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
    @Volatile private var lastSurfaceWidth = 0
    @Volatile private var lastSurfaceHeight = 0

    private var currentPalette: Palette = PaletteManager.getDefaultPalettes().first()
    private var currentFusionMode: FusionMode = FusionMode.THERMAL_ONLY
    private var enableMeasurements: Boolean = false
    private val snapshotRequested = AtomicBoolean(false)

    data class StreamSelection(val index: Int, val count: Int, val isThermal: Boolean)

    private val imagesRoot: String by lazy {
        val dir = File(appContext.filesDir, "flir_snapshots")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir.absolutePath
    }

    private fun resolveGlVersion(): Int = 2 // Force ES2 for FLIR sample parity; ES3 caused blank preview on some devices.

    enum class SurfaceOrder { DEFAULT, MEDIA_OVERLAY, ON_TOP }

    fun attachSurface(
        glSurfaceView: GLSurfaceView,
        surfaceOrder: SurfaceOrder = SurfaceOrder.ON_TOP
    ) {
        this.glSurfaceView = glSurfaceView
        val glVersion = resolveGlVersion()
        ThermalLog.d(TAG, "Using GLES $glVersion for FLIR surface")
        glSurfaceView.setEGLContextClientVersion(glVersion)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView.setPreserveEGLContextOnPause(false)
        glSurfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        when (surfaceOrder) {
            SurfaceOrder.ON_TOP -> glSurfaceView.setZOrderOnTop(true)
            SurfaceOrder.MEDIA_OVERLAY -> glSurfaceView.setZOrderMediaOverlay(true)
            SurfaceOrder.DEFAULT -> {
                glSurfaceView.setZOrderOnTop(false)
                glSurfaceView.setZOrderMediaOverlay(false)
            }
        }
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        glSurfaceView.post {
            val holderValid = runCatching { glSurfaceView.holder.surface.isValid }.getOrDefault(false)
            logDebug(
                "🖼️ GLSurface attached: size=${glSurfaceView.width}x${glSurfaceView.height}, shown=${glSurfaceView.isShown}, attached=${glSurfaceView.isAttachedToWindow}, holderValid=$holderValid, order=$surfaceOrder"
            )
        }
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
        logDebug("🎬 Selected stream: isThermal=${stream.isThermal}")
        if (!stream.isThermal) {
            logWarn("🎬 Selected stream is not thermal; falling back to first available")
        }
        logDebug("🎬 Selected stream details: type=${stream.javaClass.simpleName}, isStreaming=${stream.isStreaming}")

        // Reference app uses false, but we must pass true for visible preview on device
        // (false = headless / no preview; true = GL output to surface)
        logDebug("🎬 Setting up GL pipeline...")
        val setupLatch = CountDownLatch(1)
        var setupError: Throwable? = null
        runOnGlThread {
            try {
                logDebug("🎬 [GL Thread] glSetupPipeline(stream, true)")
                cam.glSetupPipeline(stream, true)
                logDebug("🎬 [GL Thread] glSetupPipeline complete, glIsGlContextReady=${cam.glIsGlContextReady()}")
                if (delayedSetSurface) {
                    logDebug("🎬 [GL Thread] Applying delayed surface change: ${delayedSurfaceWidth}x${delayedSurfaceHeight}")
                    applySurfaceAndViewport(cam, delayedSurfaceWidth, delayedSurfaceHeight, "setupPipeline(delayed)")
                    delayedSetSurface = false
                } else if (lastSurfaceWidth > 0 && lastSurfaceHeight > 0) {
                    logDebug("🎬 [GL Thread] Applying stored surface after setup: ${lastSurfaceWidth}x${lastSurfaceHeight}")
                    applySurfaceAndViewport(cam, lastSurfaceWidth, lastSurfaceHeight, "setupPipeline(stored)")
                }
            } catch (t: Throwable) {
                setupError = t
                logError("🎬 [GL Thread] glSetupPipeline failed: ${t.message}")
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

        logDebug("🎬 Starting stream...")
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surface.post {
            val holderValid = runCatching { surface.holder.surface.isValid }.getOrDefault(false)
            logDebug("🎬 Surface ready for streaming: size=${surface.width}x${surface.height}, shown=${surface.isShown}, attached=${surface.isAttachedToWindow}, holderValid=$holderValid")
        }
        stream.start(
            {
                surface.requestRender()
            },
            { error ->
                val message = "🎬 Stream error: $error"
                logWarn(message)
                _errors.tryEmit(message)
            }
        )
        logDebug("🎬 Stream started, isStreaming=${stream.isStreaming}")
        surface.requestRender()
    }

    fun currentStreamSelection(): StreamSelection? {
        val cam = camera ?: return null
        val streams = cam.streams
        if (streams.isEmpty()) return null
        val currentIndex = activeStream?.let { streams.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val stream = streams[currentIndex]
        return StreamSelection(currentIndex, streams.size, stream.isThermal)
    }

    fun cycleStream(offset: Int): StreamSelection? {
        val cam = camera ?: return null
        val surface = glSurfaceView ?: return null
        val streams = cam.streams
        if (streams.isEmpty()) return null

        val currentIndex = activeStream?.let { streams.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val newIndex = (currentIndex + offset + streams.size) % streams.size
        val newStream = streams[newIndex]

        // Run the actual switch work off the caller thread to avoid UI stalls.
        scope.launch {
            performStreamSwitch(cam, surface, newStream, newIndex)
        }

        // Return the intended selection immediately for UI label updates.
        return StreamSelection(newIndex, streams.size, newStream.isThermal)
    }

    private fun performStreamSwitch(cam: Camera, surface: GLSurfaceView, newStream: Stream, newIndex: Int) {
        val previousStream = activeStream
        if (newStream != previousStream) {
            logDebug("🔀 Switching stream to index=$newIndex isThermal=${newStream.isThermal}")
            previousStream?.stop()
        } else {
            logDebug("🔀 Reapplying current stream index=$newIndex isThermal=${newStream.isThermal}")
        }

        val setupResult = setupPipeline(cam, newStream, "cycleStream")
        if (!setupResult.success) {
            val message = if (setupResult.timedOut) {
                "🔀 cycleStream(): GL pipeline setup timed out"
            } else {
                "🔀 cycleStream(): setup error ${setupResult.error?.message}"
            }
            logWarn(message)
            _errors.tryEmit(message)
            _state.value = FlirState.Error(message)

            // Attempt to recover the previous stream if we switched away and setup failed.
            if (previousStream != null && previousStream != newStream) {
                logWarn("🔀 cycleStream(): restoring previous stream after failure")
                setupPipeline(cam, previousStream, "cycleStream(recover)")
                activeStream = previousStream
                previousStream.start({ surface.requestRender() }, { error ->
                    val recoverMessage = "🔀 Stream error after recovery: $error"
                    logWarn(recoverMessage)
                    _errors.tryEmit(recoverMessage)
                })
                surface.requestRender()
            }
            return
        }

        activeStream = newStream
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        newStream.start({ surface.requestRender() }, { error ->
            val message = "🔀 Stream error after switch: $error"
            logWarn(message)
            _errors.tryEmit(message)
        })
        surface.requestRender()
    }

    private data class PipelineResult(val success: Boolean, val timedOut: Boolean = false, val error: Throwable? = null)

    private fun setupPipeline(cam: Camera, stream: Stream, reason: String): PipelineResult {
        val latch = CountDownLatch(1)
        var setupError: Throwable? = null
        runOnGlThread {
            try {
                logDebug("🔀 [$reason][GL Thread] glSetupPipeline(stream, true)")
                cam.glSetupPipeline(stream, true)
                if (lastSurfaceWidth > 0 && lastSurfaceHeight > 0 && cam.glIsGlContextReady()) {
                    applySurfaceAndViewport(cam, lastSurfaceWidth, lastSurfaceHeight, reason)
                }
            } catch (t: Throwable) {
                setupError = t
                logError("🔀 [$reason][GL Thread] glSetupPipeline failed: ${t.message}")
            } finally {
                latch.countDown()
            }
        }

        val timedOut = !latch.await(1, TimeUnit.SECONDS)
        if (timedOut) {
            logWarn("🔀 [$reason] GL pipeline setup timed out")
        }
        return PipelineResult(success = !timedOut && setupError == null, timedOut = timedOut, error = setupError)
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
            logDebug("onSurfaceCreated()")
            // When GL context is recreated (e.g., after permission dialog),
            // we need to re-setup the pipeline if we have an active stream
            val cam = camera
            val stream = activeStream
            if (cam != null && stream != null) {
                logDebug("onSurfaceCreated(): Re-setting up GL pipeline for existing stream")
                cam.glSetupPipeline(stream, true)
                logDebug("onSurfaceCreated(): GL pipeline re-setup complete, glIsGlContextReady=${cam.glIsGlContextReady()}")
                if (lastSurfaceWidth > 0 && lastSurfaceHeight > 0 && cam.glIsGlContextReady()) {
                    logDebug("onSurfaceCreated(): Re-applying last surface: ${lastSurfaceWidth}x${lastSurfaceHeight}")
                    applySurfaceAndViewport(cam, lastSurfaceWidth, lastSurfaceHeight, "surfaceCreated(reapply)")
                    delayedSetSurface = false
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            logDebug("onSurfaceChanged(), width=$width, height=$height")
            lastSurfaceWidth = width
            lastSurfaceHeight = height
            val cam = camera
            if (cam != null && cam.glIsGlContextReady()) {
                logDebug("onSurfaceChanged(): GL context ready, applying immediately")
                applySurfaceAndViewport(cam, width, height, "surfaceChanged")
                delayedSetSurface = false
            } else {
                logDebug("onSurfaceChanged(): GL context not ready, deferring surface change (will apply ${width}x${height})")
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
                    logWarn("🖼️ onDrawFrame: camera is null, snapshot pending!")
                }
                return
            }

            if (!cam.glIsGlContextReady()) {
                logWarn("🖼️ onDrawFrame: GL context not ready, skipping frame${if (snapshotRequested.get()) " (snapshot pending!)" else ""}")
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
                logDebug("🖼️ Applying delayed surface: ${delayedSurfaceWidth}x${delayedSurfaceHeight}")
                applySurfaceAndViewport(cam, delayedSurfaceWidth, delayedSurfaceHeight, "drawFrame(delayed)")
                delayedSetSurface = false
            }

            var thermalImageProcessed = false
            cam.glWithThermalImage { thermalImage ->
                thermalImageProcessed = true
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
                    logDebug("📸 [onDrawFrame] Saving to: $snapshotPath")
                    try {
                        thermalImage.saveAs(snapshotPath)
                        logDebug("📸 [onDrawFrame] ✅ Snapshot saved: $snapshotPath")
                        val emitted = _snapshots.tryEmit(File(snapshotPath))
                        logDebug("📸 [onDrawFrame] Emitted to flow: $emitted")
                    } catch (e: IOException) {
                        val message = "📸 [onDrawFrame] ❌ Unable to take snapshot: ${e.message}"
                        logError(message)
                        _errors.tryEmit(message)
                    }
                }
            }

            val rendered = cam.glOnDrawFrame()
            if (!rendered || !thermalImageProcessed) {
                if (frameCount % 30 == 0L) {
                    logWarn("🖼️ Frame issue: rendered=$rendered, thermalImageProcessed=$thermalImageProcessed")
                }
            }
        }
    }

    private fun runOnGlThread(block: () -> Unit) {
        val surface = glSurfaceView
        if (surface == null) {
            logWarn("GLSurfaceView not attached; dropping GL action")
            return
        }
        surface.queueEvent(block)
    }

    // Applies surface size and viewport together; mirrors the reference app's use of glSetViewport after pipeline setup.
    private fun applySurfaceAndViewport(cam: Camera, width: Int, height: Int, reason: String) {
        if (width <= 0 || height <= 0) {
            logWarn("$reason: invalid surface size ${width}x$height")
            return
        }
        if (!cam.glIsGlContextReady()) {
            logWarn("$reason: GL context not ready; skipping surface apply")
            return
        }
        val holderValid = runCatching { glSurfaceView?.holder?.surface?.isValid }.getOrDefault(false)
        logDebug("$reason: applying surface ${width}x$height, glReady=${cam.glIsGlContextReady()}, holderValid=$holderValid")
        cam.glOnSurfaceChanged(width, height)
        try {
            cam.glSetViewport(0, 0, width, height)
        } catch (t: Throwable) {
            logWarn("$reason: glSetViewport failed: ${t.message}")
        }
    }

    private fun logDebug(message: String) {
        ThermalLog.d(TAG, message)
        Log.d(TAG, message)
    }

    private fun logWarn(message: String) {
        ThermalLog.w(TAG, message)
        Log.w(TAG, message)
    }

    private fun logError(message: String) {
        ThermalLog.e(TAG, message)
        Log.e(TAG, message)
    }
}
