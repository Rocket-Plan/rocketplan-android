package com.example.rocketplan_android.thermal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
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
import com.flir.thermalsdk.androidsdk.image.BitmapAndroid
import com.flir.thermalsdk.utils.FileUtils
import com.flir.thermalsdk.utils.Pair
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig as GLConfigLegacy
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

    private val _snapshotsWithVisual = MutableSharedFlow<FlirSnapshotResult>(extraBufferCapacity = 1)
    val snapshotsWithVisual: SharedFlow<FlirSnapshotResult> = _snapshotsWithVisual.asSharedFlow()

    private val extractVisualOnSnapshot = AtomicBoolean(false)

    private var camera: Camera? = null
    private var activeStream: Stream? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var renderTarget: GlRenderTarget? = null
    private var overlayFriendlyMode: Boolean = false
    @Volatile private var delayedSetSurface = false
    @Volatile private var delayedSurfaceWidth = 0
    @Volatile private var delayedSurfaceHeight = 0
    @Volatile private var lastSurfaceWidth = 0
    @Volatile private var lastSurfaceHeight = 0
    private var frameCount = 0L
    private var lastFrameLogTime = 0L

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
    private enum class RenderMode { WHEN_DIRTY, CONTINUOUS }

    fun attachSurface(
        glSurfaceView: GLSurfaceView,
        surfaceOrder: SurfaceOrder = SurfaceOrder.ON_TOP
    ) {
        renderTarget?.release()
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
        renderTarget = GlSurfaceViewRenderTarget(glSurfaceView)
        if (overlayFriendlyMode) {
            setOverlayFriendlyMode(true)
        }
        glSurfaceView.post {
            val holderValid = runCatching { glSurfaceView.holder.surface.isValid }.getOrDefault(false)
            logDebug(
                "🖼️ GLSurface attached: size=${glSurfaceView.width}x${glSurfaceView.height}, shown=${glSurfaceView.isShown}, attached=${glSurfaceView.isAttachedToWindow}, holderValid=$holderValid, order=$surfaceOrder"
            )
        }
    }

    fun attachTextureSurface(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        logDebug("🖼️ Texture surface attached: size=${width}x$height")
        renderTarget?.release()
        glSurfaceView = null
        renderTarget = TextureViewRenderTarget(surfaceTexture, width, height)
    }

    fun updateTextureSurfaceSize(width: Int, height: Int) {
        (renderTarget as? TextureViewRenderTarget)?.onSurfaceSizeChanged(width, height)
    }

    fun detachTextureSurface() {
        if (renderTarget is TextureViewRenderTarget) {
            renderTarget?.release()
            renderTarget = null
        }
    }

    fun setOverlayFriendlyMode(enabled: Boolean) {
        overlayFriendlyMode = enabled
        glSurfaceView?.let { surface ->
            if (enabled) {
                surface.setZOrderOnTop(false)
                surface.setZOrderMediaOverlay(true)
            } else {
                surface.setZOrderMediaOverlay(false)
                surface.setZOrderOnTop(true)
            }
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
        ThermalLog.d(TAG, "📸 Render target: ${renderTarget?.description() ?: "null"}")
        snapshotRequested.set(true)
        // Request a render to trigger onDrawFrame where snapshot is processed
        renderTarget?.requestRender()
        ThermalLog.d(TAG, "📸 snapshotRequested=true, requestRender() called")
    }

    fun setExtractVisualEnabled(enabled: Boolean) {
        extractVisualOnSnapshot.set(enabled)
        ThermalLog.d(TAG, "📸 setExtractVisualEnabled: $enabled")
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
        val target = renderTarget
        if (target == null) {
            val message = "No GL surface attached for FLIR stream"
            ThermalLog.e(TAG, message)
            _state.value = FlirState.Error(message)
            _errors.tryEmit(message)
            return
        }
        if (!target.isValid()) {
            val message = "Render target not ready for FLIR stream"
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
        logDebug("🎬 Setting up GL pipeline (async)...")
        runOnGlThread {
            val setupError: Throwable? = try {
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
                null // Success - no error
            } catch (t: Throwable) {
                logError("🎬 [GL Thread] glSetupPipeline failed: ${t.message}")
                t // Capture the error
            }

            // Continue on IO scope once GL work is done
            scope.launch {
                // Check if disconnected while GL setup was in progress
                if (camera == null) {
                    logDebug("🎬 Camera disconnected during GL setup, aborting stream start")
                    return@launch
                }

                if (setupError != null) {
                    val message = "🎬 Failed to set up GL pipeline: ${setupError.message}"
                    ThermalLog.e(TAG, message)
                    _state.value = FlirState.Error(message)
                    _errors.tryEmit(message)
                    return@launch
                }

                logDebug("🎬 GL setup complete, starting stream...")
                _state.value = FlirState.Streaming(identity, info)

                target.setRenderMode(RenderMode.CONTINUOUS)
                logDebug("🎬 Surface ready for streaming: target=${target.description()}")
                stream.start(
                    {
                        target.requestRender()
                    },
                    { error ->
                        val message = "🎬 Stream error: $error"
                        logWarn(message)
                        _errors.tryEmit(message)
                    }
                )
                logDebug("🎬 Stream started, isStreaming=${stream.isStreaming}")
                target.requestRender()
            }
        }
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
        val target = renderTarget ?: return null
        val streams = cam.streams
        if (streams.isEmpty()) return null

        val currentIndex = activeStream?.let { streams.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val newIndex = (currentIndex + offset + streams.size) % streams.size
        val newStream = streams[newIndex]

        // Run the actual switch work off the caller thread to avoid UI stalls.
        scope.launch {
            performStreamSwitch(cam, target, newStream, newIndex)
        }

        // Return the intended selection immediately for UI label updates.
        return StreamSelection(newIndex, streams.size, newStream.isThermal)
    }

    private fun performStreamSwitch(cam: Camera, target: GlRenderTarget, newStream: Stream, newIndex: Int) {
        val previousStream = activeStream
        if (newStream != previousStream) {
            logDebug("🔀 Switching stream to index=$newIndex isThermal=${newStream.isThermal}")
            previousStream?.stop()
        } else {
            logDebug("🔀 Reapplying current stream index=$newIndex isThermal=${newStream.isThermal}")
        }

        setupPipelineAsync(cam, newStream, "cycleStream") { setupError ->
            if (setupError != null) {
                val message = "🔀 cycleStream(): setup error ${setupError.message}"
                logWarn(message)
                _errors.tryEmit(message)
                _state.value = FlirState.Error(message)

                // Attempt to recover the previous stream if we switched away and setup failed.
                if (previousStream != null && previousStream != newStream) {
                    logWarn("🔀 cycleStream(): restoring previous stream after failure")
                    setupPipelineAsync(cam, previousStream, "cycleStream(recover)") { recoverError ->
                        if (recoverError == null) {
                            activeStream = previousStream
                            previousStream.start({ target.requestRender() }, { error ->
                                val recoverMessage = "🔀 Stream error after recovery: $error"
                                logWarn(recoverMessage)
                                _errors.tryEmit(recoverMessage)
                            })
                            target.requestRender()
                        }
                    }
                }
                return@setupPipelineAsync
            }

            activeStream = newStream
            target.setRenderMode(RenderMode.CONTINUOUS)
            newStream.start({ target.requestRender() }, { error ->
                val message = "🔀 Stream error after switch: $error"
                logWarn(message)
                _errors.tryEmit(message)
            })
            target.requestRender()
        }
    }

    private fun setupPipelineAsync(cam: Camera, stream: Stream, reason: String, onComplete: (Throwable?) -> Unit) {
        runOnGlThread {
            val setupError: Throwable? = try {
                logDebug("🔀 [$reason][GL Thread] glSetupPipeline(stream, true)")
                cam.glSetupPipeline(stream, true)
                if (lastSurfaceWidth > 0 && lastSurfaceHeight > 0 && cam.glIsGlContextReady()) {
                    applySurfaceAndViewport(cam, lastSurfaceWidth, lastSurfaceHeight, reason)
                }
                null
            } catch (t: Throwable) {
                logError("🔀 [$reason][GL Thread] glSetupPipeline failed: ${t.message}")
                t
            }

            scope.launch {
                onComplete(setupError)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            ThermalLog.d(TAG, "disconnect()")
            val currentCamera = camera
            try {
                activeStream?.stop()
            } catch (t: Throwable) {
                logWarn("disconnect(): Stream stop error: ${t.message}")
            }
            renderTarget?.setRenderMode(RenderMode.WHEN_DIRTY)

            // Clear state immediately so new connections don't race
            camera = null
            activeStream = null
            _state.value = FlirState.Idle
            DiscoveryFactory.getInstance().stop(communicationInterface)

            // Teardown GL resources asynchronously
            runOnGlThread {
                try {
                    // FLIR's Camera.glTeardownPipeline() has a 500ms sleep before teardown
                    Thread.sleep(500)
                    currentCamera?.glTeardownPipeline()
                    logDebug("disconnect(): GL teardown complete")
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (t: Throwable) {
                    logWarn("disconnect(): GL teardown error: ${t.message}")
                }

                // Disconnect camera after GL teardown
                scope.launch {
                    try {
                        currentCamera?.disconnect()
                        logDebug("disconnect(): Camera disconnected")
                    } catch (t: Throwable) {
                        logWarn("disconnect(): Camera disconnect error: ${t.message}")
                    }
                }
            }
        }
    }

    fun onResume() {
        renderTarget?.onResume()
    }

    fun onPause() {
        renderTarget?.onPause()
    }

    private val renderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: GLConfigLegacy?) {
            onRendererSurfaceCreated()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            onRendererSurfaceChanged(width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            onRendererDrawFrame()
        }
    }

    private fun onRendererSurfaceCreated() {
        logDebug("onSurfaceCreated()")
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

    private fun onRendererSurfaceChanged(width: Int, height: Int) {
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

    private fun onRendererDrawFrame() {
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
            val surfaceValid = renderTarget?.isValid() ?: false
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
            if (frameCount % 60 == 0L) {
                logDebug("🔀 Fusion: fusion=${fusion != null}, mode=$currentFusionMode")
            }
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
                    val thermalFile = File(snapshotPath)

                    // Extract visual image if enabled
                    var visualFile: File? = null
                    if (extractVisualOnSnapshot.get()) {
                        try {
                            val visualBuffer = thermalImage.fusion?.getPhoto()
                            if (visualBuffer != null) {
                                val bitmap = BitmapAndroid.createBitmap(visualBuffer).bitMap
                                val visualPath = FileUtils.prepareUniqueFileName(imagesRoot, "ACE_VIS_", "jpg")
                                FileOutputStream(visualPath).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                }
                                visualFile = File(visualPath)
                                logDebug("📸 [onDrawFrame] ✅ Visual image extracted: $visualPath")
                            } else {
                                logWarn("📸 [onDrawFrame] Visual extraction enabled but fusion.getPhoto() returned null")
                            }
                        } catch (e: Exception) {
                            logError("📸 [onDrawFrame] Failed to extract visual: ${e.message}")
                        }
                    }

                    // Emit to both flows
                    val emitted = _snapshots.tryEmit(thermalFile)
                    logDebug("📸 [onDrawFrame] Emitted to snapshots flow: $emitted")
                    val emittedWithVisual = _snapshotsWithVisual.tryEmit(FlirSnapshotResult(thermalFile, visualFile))
                    logDebug("📸 [onDrawFrame] Emitted to snapshotsWithVisual flow: $emittedWithVisual, visualFile=${visualFile?.name}")
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

    private fun runOnGlThread(block: () -> Unit) {
        val target = renderTarget
        if (target == null) {
            logWarn("Render target not attached; dropping GL action")
            return
        }
        target.queueEvent(block)
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
        val holderValid = renderTarget?.isValid() ?: false
        logDebug("$reason: applying surface ${width}x$height, glReady=${cam.glIsGlContextReady()}, holderValid=$holderValid")
        logDebug("$reason: calling glOnSurfaceChanged...")
        cam.glOnSurfaceChanged(width, height)
        logDebug("$reason: glOnSurfaceChanged complete")
        try {
            logDebug("$reason: calling glSetViewport...")
            cam.glSetViewport(0, 0, width, height)
            logDebug("$reason: glSetViewport complete")
        } catch (t: Throwable) {
            logWarn("$reason: glSetViewport failed: ${t.message}")
        }
        logDebug("$reason: applySurfaceAndViewport complete")
    }

    private interface GlRenderTarget {
        val width: Int
        val height: Int
        fun queueEvent(block: () -> Unit)
        fun requestRender()
        fun setRenderMode(mode: RenderMode)
        fun onPause()
        fun onResume()
        fun release()
        fun isValid(): Boolean
        fun description(): String
    }

    private inner class GlSurfaceViewRenderTarget(
        private val surfaceView: GLSurfaceView
    ) : GlRenderTarget {
        override val width: Int
            get() = surfaceView.width
        override val height: Int
            get() = surfaceView.height

        override fun queueEvent(block: () -> Unit) {
            surfaceView.queueEvent(block)
        }

        override fun requestRender() {
            surfaceView.requestRender()
        }

        override fun setRenderMode(mode: RenderMode) {
            surfaceView.renderMode = if (mode == RenderMode.CONTINUOUS) {
                GLSurfaceView.RENDERMODE_CONTINUOUSLY
            } else {
                GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
        }

        override fun onPause() {
            surfaceView.onPause()
        }

        override fun onResume() {
            surfaceView.onResume()
        }

        override fun release() {
            // No-op for GLSurfaceView path
        }

        override fun isValid(): Boolean = runCatching { surfaceView.holder.surface.isValid }.getOrDefault(false)

        override fun description(): String =
            "GLSurfaceView(${surfaceView.width}x${surfaceView.height}, shown=${surfaceView.isShown}, attached=${surfaceView.isAttachedToWindow})"
    }

    private inner class TextureViewRenderTarget(
        private val surfaceTexture: SurfaceTexture,
        @Volatile private var surfaceWidth: Int,
        @Volatile private var surfaceHeight: Int
    ) : GlRenderTarget {

        private val handlerThread = HandlerThread("FlirTextureRenderer").apply { start() }
        private val handler = Handler(handlerThread.looper)
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var currentMode: RenderMode = RenderMode.WHEN_DIRTY
        private val destroyed = AtomicBoolean(false)
        private var windowSurface: Surface? = null

        private val continuousRender = object : Runnable {
            override fun run() {
                if (destroyed.get()) return
                drawFrameInternal()
                if (currentMode == RenderMode.CONTINUOUS) {
                    handler.postDelayed(this, 16L)
                }
            }
        }

        init {
            handler.post {
                if (setupEgl()) {
                    onRendererSurfaceCreated()
                    onRendererSurfaceChanged(surfaceWidth, surfaceHeight)
                }
            }
        }

        override val width: Int
            get() = surfaceWidth
        override val height: Int
            get() = surfaceHeight

        override fun queueEvent(block: () -> Unit) {
            handler.post {
                if (!destroyed.get() && ensureCurrent()) {
                    block()
                }
            }
        }

        override fun requestRender() {
            handler.post {
                if (!destroyed.get() && ensureCurrent()) {
                    drawFrameInternal()
                }
            }
        }

        override fun setRenderMode(mode: RenderMode) {
            handler.post {
                currentMode = mode
                if (mode == RenderMode.CONTINUOUS) {
                    handler.removeCallbacks(continuousRender)
                    handler.post(continuousRender)
                } else {
                    handler.removeCallbacks(continuousRender)
                }
            }
        }

        override fun onPause() {
            handler.post { handler.removeCallbacks(continuousRender) }
        }

        override fun onResume() {
            handler.post {
                if (currentMode == RenderMode.CONTINUOUS) {
                    handler.removeCallbacks(continuousRender)
                    handler.post(continuousRender)
                }
            }
        }

        fun onSurfaceSizeChanged(width: Int, height: Int) {
            handler.post {
                surfaceWidth = width
                surfaceHeight = height
                if (!destroyed.get() && ensureCurrent()) {
                    onRendererSurfaceChanged(width, height)
                }
            }
        }

        override fun release() {
            if (!destroyed.compareAndSet(false, true)) {
                return
            }
            handler.removeCallbacksAndMessages(null)
            val teardown: () -> Unit = {
                tearDownEgl()
                handlerThread.quitSafely()
            }
            if (Thread.currentThread() == handlerThread) {
                teardown()
            } else {
                handler.post(teardown)
                try {
                    handlerThread.join()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        override fun isValid(): Boolean = !destroyed.get() && eglSurface != EGL14.EGL_NO_SURFACE

        override fun description(): String = "TextureView(${surfaceWidth}x${surfaceHeight})"

        private fun drawFrameInternal() {
            if (!ensureCurrent()) return
            onRendererDrawFrame()
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }
        }

        private fun ensureCurrent(): Boolean {
            if (destroyed.get()) return false
            if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE || eglContext == EGL14.EGL_NO_CONTEXT) {
                return setupEgl()
            }
            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        private fun setupEgl(): Boolean {
            fun fail(message: String): Boolean {
                logWarn(message)
                tearDownEgl()
                return false
            }
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                return fail("TextureViewRenderTarget: Unable to get EGL display")
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                return fail("TextureViewRenderTarget: Unable to initialize EGL")
            }
            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0) ||
                numConfigs[0] <= 0
            ) {
                return fail("TextureViewRenderTarget: Unable to choose EGL config")
            }
            val config: EGLConfig = configs[0] ?: return fail("TextureViewRenderTarget: No EGL config resolved")
            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, resolveGlVersion(),
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                return fail("TextureViewRenderTarget: Unable to create EGL context")
            }
            windowSurface = Surface(surfaceTexture)
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, windowSurface, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                return fail("TextureViewRenderTarget: Unable to create EGL window surface")
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                return fail("TextureViewRenderTarget: eglMakeCurrent failed")
            }
            return true
        }

        private fun tearDownEgl() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            windowSurface?.release()
            windowSurface = null
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
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

/**
 * Result of a FLIR snapshot capture, containing the thermal file
 * and optionally the extracted visual image file.
 */
data class FlirSnapshotResult(
    val thermalFile: File,
    val visualFile: File?
)
