package com.example.rocketplan_android.ui.projects.batchcapture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.rocketplan_android.R
import com.example.rocketplan_android.thermal.FlirCameraController
import com.example.rocketplan_android.thermal.FlirState
import com.example.rocketplan_android.thermal.FusionMode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.chip.ChipGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.example.rocketplan_android.ui.projects.PhotosAddedResult

class BatchCaptureFragment : Fragment() {

    private enum class CaptureMode { REGULAR, IR }

    private val args: BatchCaptureFragmentArgs by navArgs()

    private val viewModel: BatchCaptureViewModel by viewModels {
        BatchCaptureViewModel.provideFactory(
            requireActivity().application,
            args.projectId,
            args.roomId
        )
    }

    // Views
    private lateinit var cameraPreview: PreviewView
    private lateinit var flirPreviewContainer: FrameLayout
    private lateinit var flirTextureView: TextureView
    private var flirFallbackSurface: GLSurfaceView? = null
    private lateinit var modeToggle: MaterialButtonToggleGroup
    private lateinit var regularModeButton: MaterialButton
    private lateinit var irModeButton: MaterialButton
    private lateinit var flirControls: View
    private lateinit var flirStatusText: TextView
    private lateinit var flirPaletteSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var flirFusionSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var flirMeasurementsSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var flirMenuButton: ImageButton
    private lateinit var categoryMenuButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var photoCountText: TextView
    private lateinit var flashButton: ImageButton
    private lateinit var thumbnailStrip: RecyclerView
    private lateinit var lastPhotoPreview: ImageView
    private lateinit var shutterButton: FrameLayout
    private lateinit var shutterInner: View
    private lateinit var doneButton: MaterialButton
    private lateinit var categoryChipGroup: ChipGroup
    private lateinit var loadingOverlay: View
    private lateinit var loadingText: TextView

    private lateinit var thumbnailAdapter: ThumbnailStripAdapter
    private var flirReady = false
    private val useGlSurfaceFallback = false
    private var latestUiState: BatchCaptureUiState = BatchCaptureUiState()

    // Camera
    private var captureMode: CaptureMode = CaptureMode.REGULAR
    private var imageCapture: ImageCapture? = null
    private var isCapturing = false
    private var captureTimeoutJob: kotlinx.coroutines.Job? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private lateinit var flirController: FlirCameraController

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<Array<String>>
    private val cameraPermissions = arrayOf(Manifest.permission.CAMERA)

    private val flirTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            flirController.attachTextureSurface(surface, width, height)
            if (captureMode == CaptureMode.IR && hasCameraPermission()) {
                startActiveMode()
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            flirController.updateTextureSurfaceSize(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            flirController.detachTextureSurface()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            // No-op
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        captureMode = if (args.captureMode.equals("ir", ignoreCase = true)) {
            CaptureMode.IR
        } else {
            CaptureMode.REGULAR
        }

        flirController = FlirCameraController(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val granted = permissions.entries.all { it.value }
                if (granted) {
                    startActiveMode()
                } else if (isAdded) {
                    viewModel.logCameraError("permission_denied", "Camera permission denied by user")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.camera_permission_required),
                        Toast.LENGTH_LONG
                    ).show()
                    findNavController().navigateUp()
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_batch_capture, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: projectId=${args.projectId}, roomId=${args.roomId}")

        // Remove status bar padding so preview can draw to the top edge in fullscreen
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, navInsets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        bindViews(view)
        setupFlirPreviewSurface()
        setupThumbnailStrip()
        setupListeners()
        setupHardwareButtons(view)
        observeViewModel()

        // Apply initial mode selection and start appropriate pipeline
        modeToggle.check(if (captureMode == CaptureMode.IR) R.id.irModeButton else R.id.regularModeButton)
        updateModeUi()

        if (hasCameraPermission()) {
            startActiveMode()
        } else {
            cameraPermissionLauncher.launch(cameraPermissions)
        }
    }

    private fun bindViews(view: View) {
        cameraPreview = view.findViewById(R.id.cameraPreview)
        flirPreviewContainer = view.findViewById(R.id.flirPreviewContainer)
        flirTextureView = view.findViewById(R.id.flirTextureView)
        modeToggle = view.findViewById(R.id.modeToggle)
        regularModeButton = view.findViewById(R.id.regularModeButton)
        irModeButton = view.findViewById(R.id.irModeButton)
        flirControls = view.findViewById(R.id.flirControls)
        flirStatusText = view.findViewById(R.id.flirStatusText)
        flirPaletteSwitch = view.findViewById(R.id.flirPaletteSwitch)
        flirFusionSwitch = view.findViewById(R.id.flirFusionSwitch)
        flirMeasurementsSwitch = view.findViewById(R.id.flirMeasurementsSwitch)
        flirMenuButton = view.findViewById(R.id.flirMenuButton)
        categoryMenuButton = view.findViewById(R.id.categoryMenuButton)
        closeButton = view.findViewById(R.id.closeButton)
        titleText = view.findViewById(R.id.titleText)
        photoCountText = view.findViewById(R.id.photoCountText)
        flashButton = view.findViewById(R.id.flashButton)
        thumbnailStrip = view.findViewById(R.id.thumbnailStrip)
        lastPhotoPreview = view.findViewById(R.id.lastPhotoPreview)
        shutterButton = view.findViewById(R.id.shutterButton)
        shutterInner = view.findViewById(R.id.shutterInner)
        doneButton = view.findViewById(R.id.doneButton)
        categoryChipGroup = view.findViewById(R.id.categoryChipGroup)
        loadingOverlay = view.findViewById(R.id.loadingOverlay)
        loadingText = view.findViewById(R.id.loadingText)
        flirStatusText.text = getString(R.string.flir_status_idle)
        flirStatusText.visibility = View.GONE
    }

    private fun setupFlirPreviewSurface() {
        if (useGlSurfaceFallback) {
            if (flirFallbackSurface == null) {
                val glSurface = GLSurfaceView(requireContext()).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                flirPreviewContainer.addView(glSurface)
                flirFallbackSurface = glSurface
            }
            flirFallbackSurface?.let { flirController.attachSurface(it) }
        } else {
            flirTextureView.isOpaque = false
            flirTextureView.surfaceTextureListener = flirTextureListener
            val surfaceTexture = flirTextureView.surfaceTexture
            if (flirTextureView.isAvailable && surfaceTexture != null) {
                flirTextureListener.onSurfaceTextureAvailable(
                    surfaceTexture,
                    flirTextureView.width,
                    flirTextureView.height
                )
            }
        }
    }

    private fun tearDownFlirPreviewSurface() {
        if (useGlSurfaceFallback) {
            flirFallbackSurface?.let { flirPreviewContainer.removeView(it) }
            flirFallbackSurface = null
        } else {
            flirTextureView.surfaceTextureListener = null
            flirController.detachTextureSurface()
        }
    }

    private fun setupThumbnailStrip() {
        thumbnailAdapter = ThumbnailStripAdapter { photoId ->
            viewModel.removePhoto(photoId)
        }

        thumbnailStrip.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = thumbnailAdapter
        }
    }

    private fun setupListeners() {
        closeButton.setOnClickListener {
            handleBackPress()
        }

        shutterButton.setOnClickListener {
            triggerCapture()
        }

        flashButton.setOnClickListener {
            toggleFlash()
        }

        doneButton.setOnClickListener {
            viewModel.commitPhotos()
        }

        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = when (checkedId) {
                R.id.irModeButton -> CaptureMode.IR
                else -> CaptureMode.REGULAR
            }
            switchMode(newMode)
        }

        flirPaletteSwitch.setOnCheckedChangeListener { _, isChecked ->
            flirController.setPalette(if (isChecked) 1 else 0)
        }

        flirFusionSwitch.setOnCheckedChangeListener { _, isChecked ->
            flirController.setFusionMode(if (isChecked) FusionMode.VISUAL_ONLY else FusionMode.THERMAL_ONLY)
        }

        flirMeasurementsSwitch.setOnCheckedChangeListener { _, isChecked ->
            flirController.setMeasurementsEnabled(isChecked)
        }

        flirMenuButton.setOnClickListener {
            showFlirOptionsMenu()
        }

        categoryMenuButton.setOnClickListener {
            showCategoryMenu()
        }

        lastPhotoPreview.setOnClickListener {
            // Could open photo gallery/review screen
        }
    }

    /**
     * Set up hardware button support for ACE platform.
     * Key.F1 = Trigger button (capture photo)
     * Key.Back = Back button (handled by system)
     */
    private fun setupHardwareButtons(view: View) {
        // Make the view focusable to receive key events
        view.isFocusableInTouchMode = true
        view.requestFocus()

        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_F1 -> {
                        // Trigger button pressed - capture photo
                        Log.d(TAG, "Hardware trigger button (F1) pressed")
                        if (shutterButton.isEnabled) {
                            triggerCapture()
                        }
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    }

    private fun showFlirOptionsMenu() {
        val items = arrayOf(
            getString(R.string.flir_palette),
            getString(R.string.flir_fusion),
            getString(R.string.flir_measurements)
        )
        val checked = booleanArrayOf(
            flirPaletteSwitch.isChecked,
            flirFusionSwitch.isChecked,
            flirMeasurementsSwitch.isChecked
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.flir_options)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> {
                        flirPaletteSwitch.isChecked = isChecked
                        flirController.setPalette(if (isChecked) 1 else 0)
                    }
                    1 -> {
                        flirFusionSwitch.isChecked = isChecked
                        flirController.setFusionMode(if (isChecked) FusionMode.VISUAL_ONLY else FusionMode.THERMAL_ONLY)
                    }
                    2 -> {
                        flirMeasurementsSwitch.isChecked = isChecked
                        flirController.setMeasurementsEnabled(isChecked)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCategoryMenu() {
        val categories = latestUiState.categories
        if (categories.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.batch_capture_no_categories), Toast.LENGTH_SHORT).show()
            return
        }

        val names = categories.map { it.name }.toTypedArray()
        val selectedIndex = categories.indexOfFirst { it.albumId == latestUiState.selectedCategoryId }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.batch_capture_select_category)
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                val selected = categories.getOrNull(which) ?: return@setSingleChoiceItems
                viewModel.selectCategory(selected.albumId)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Trigger photo capture based on current mode.
     * Called by both UI shutter button and hardware trigger button.
     */
    private fun triggerCapture() {
        Log.d(TAG, "triggerCapture() called, captureMode=$captureMode, isCapturing=$isCapturing")
        if (isCapturing) {
            Log.d(TAG, "Capture already in progress, ignoring")
            return
        }
        isCapturing = true
        if (captureMode == CaptureMode.REGULAR) {
            startCaptureTimeout(REGULAR_CAPTURE_TIMEOUT_MS, "regular_capture_timeout")
            capturePhoto()
        } else {
            Log.d(TAG, "Requesting FLIR snapshot...")
            startCaptureTimeout(FLIR_CAPTURE_TIMEOUT_MS, "flir_capture_timeout")
            flirController.requestSnapshot()
        }
    }

    private fun startCaptureTimeout(timeoutMs: Long, errorType: String) {
        captureTimeoutJob?.cancel()
        captureTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(timeoutMs)
            if (isCapturing) {
                Log.w(TAG, "$errorType - resetting capture lock")
                viewModel.logCameraError(errorType, "Capture request timed out after ${timeoutMs}ms")
                isCapturing = false
            }
        }
    }

    private fun handleBackPress() {
        val state = viewModel.uiState.value
        if (state.hasPhotos) {
            viewModel.clearBatch()
        }
        findNavController().navigateUp()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { state -> renderState(state) } }

                launch {
                    flirController.state.collect { state ->
                        when (state) {
                            FlirState.Idle -> {
                                flirStatusText.text = getString(R.string.flir_status_idle)
                                flirReady = false
                                captureTimeoutJob?.cancel()
                                isCapturing = false  // Reset if FLIR disconnected mid-capture
                            }
                            FlirState.Discovering -> {
                                flirStatusText.text = getString(R.string.flir_status_discovering)
                                flirReady = false
                                captureTimeoutJob?.cancel()
                                isCapturing = false  // Reset if reconnecting
                            }
                            is FlirState.Connecting -> {
                                flirStatusText.text =
                                    getString(R.string.flir_status_connecting, state.identity.deviceId)
                                flirReady = false
                                captureTimeoutJob?.cancel()
                                isCapturing = false  // Reset if reconnecting
                            }
                            is FlirState.Streaming -> {
                                flirStatusText.text =
                                    getString(R.string.flir_status_streaming, state.identity.deviceId)
                                flirReady = true
                                renderState(viewModel.uiState.value) // Update shutter button
                            }
                            is FlirState.Error -> {
                                flirStatusText.text = state.message
                                flirReady = false
                                captureTimeoutJob?.cancel()
                                isCapturing = false  // Reset capture lock so user can retry
                                viewModel.logCameraError("flir_state_error", state.message)
                            }
                        }
                    }
                }

                launch {
                    flirController.errors.collect { message ->
                        captureTimeoutJob?.cancel()
                        isCapturing = false
                        viewModel.logCameraError("flir_error", message)
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }

                launch {
                    flirController.snapshots.collect { file ->
                        captureTimeoutJob?.cancel()
                        isCapturing = false
                        val added = viewModel.addPhoto(file, isIr = true)
                        if (!added) {
                            file.delete()
                            Log.w(TAG, "Failed to add FLIR snapshot: limit reached")
                        } else {
                            Log.d(TAG, "FLIR snapshot added: ${file.name}")
                        }
                    }
                }

                launch {
                    viewModel.events.collect { event ->
                        handleEvent(event)
                    }
                }
            }
        }
    }

    private fun renderState(state: BatchCaptureUiState) {
        latestUiState = state
        photoCountText.text = "${state.photoCount}/${state.maxPhotos}"

        thumbnailAdapter.submitList(state.photos)

        renderCategories(state)

        thumbnailStrip.isVisible = state.hasPhotos
        doneButton.isVisible = state.hasPhotos

        // Update last photo preview
        val lastPhoto = state.photos.lastOrNull()
        if (lastPhoto != null) {
            lastPhotoPreview.visibility = View.VISIBLE
            lastPhotoPreview.load(lastPhoto.file) {
                crossfade(true)
            }
        } else {
            lastPhotoPreview.visibility = View.INVISIBLE
        }

        val shutterEnabled = state.canTakeMore &&
            (captureMode == CaptureMode.REGULAR || flirReady)
        Log.d(TAG, "Shutter enabled: $shutterEnabled (canTakeMore=${state.canTakeMore}, isProcessing=${state.isProcessing}, captureMode=$captureMode, flirReady=$flirReady)")
        shutterButton.isEnabled = shutterEnabled
        shutterInner.alpha = if (shutterEnabled) 1f else 0.5f

        doneButton.isEnabled = state.hasPhotos

        loadingOverlay.isVisible = false

        if (state.roomTitle.isNotEmpty()) {
            titleText.text = state.roomTitle
        }
    }

    private fun renderCategories(state: BatchCaptureUiState) {
        // Chip row hidden; selection handled via menu button instead.
        categoryChipGroup.isVisible = false
    }

    private fun handleEvent(event: BatchCaptureEvent) {
        when (event) {
            is BatchCaptureEvent.PhotosCommitted -> {
                findNavController().previousBackStackEntry?.savedStateHandle
                    ?.set(
                        com.example.rocketplan_android.ui.projects.RoomDetailFragment.PHOTOS_ADDED_RESULT_KEY,
                        PhotosAddedResult(event.count, event.assemblyId)
                    )
                Toast.makeText(
                    requireContext(),
                    getString(R.string.batch_capture_success),
                    Toast.LENGTH_SHORT
                ).show()
                findNavController().navigateUp()
            }
            is BatchCaptureEvent.Error -> {
                Toast.makeText(
                    requireContext(),
                    event.message,
                    Toast.LENGTH_LONG
                ).show()
            }
            is BatchCaptureEvent.LimitReached -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.batch_capture_limit_reached),
                    Toast.LENGTH_SHORT
                ).show()
            }
            is BatchCaptureEvent.BatchCleared -> {
                // No toast needed
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        cameraPermissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

    private fun isFlirSurfaceReady(): Boolean {
        return if (useGlSurfaceFallback) {
            flirFallbackSurface != null
        } else {
            flirTextureView.isAvailable && flirTextureView.surfaceTexture != null
        }
    }

    private fun switchMode(newMode: CaptureMode) {
        if (captureMode == newMode) return
        captureMode = newMode
        updateModeUi()
        if (hasCameraPermission()) {
            startActiveMode()
        } else {
            cameraPermissionLauncher.launch(cameraPermissions)
        }
    }

    private fun updateModeUi() {
        val isIr = captureMode == CaptureMode.IR
        Log.d(TAG, "updateModeUi: isIr=$isIr")
        flirControls.isVisible = isIr
        flirPreviewContainer.isVisible = isIr
        flirTextureView.isVisible = isIr && !useGlSurfaceFallback
        flirFallbackSurface?.isVisible = isIr && useGlSurfaceFallback
        cameraPreview.isVisible = !isIr
        flashButton.isEnabled = !isIr
        flashButton.alpha = if (isIr) 0.4f else 1f
        Log.d(
            TAG,
            "updateModeUi: flirPreviewContainer.visibility=${flirPreviewContainer.visibility}, cameraPreview.visibility=${cameraPreview.visibility}"
        )
    }

    private fun startActiveMode() {
        when (captureMode) {
            CaptureMode.REGULAR -> {
                flirReady = false
                flirController.disconnect()
                startCamera()
            }
            CaptureMode.IR -> {
                if (!isFlirSurfaceReady()) {
                    Log.d(TAG, "startActiveMode: FLIR surface not ready, waiting for availability")
                    return
                }
                stopRegularCamera()
                flirController.startDiscovery()
            }
        }
    }

    private fun stopRegularCamera() {
        cameraProvider?.unbindAll()
        imageCapture = null
    }

    private fun startCamera() {
        val context = context ?: return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
                viewModel.logCameraError("initialization_failed", e.message, e)
                Toast.makeText(
                    context,
                    getString(R.string.camera_error, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val context = context ?: return
        val cameraProvider = cameraProvider ?: run {
            Log.w(TAG, "bindCameraUseCases called but cameraProvider is null")
            viewModel.logCameraError("binding_skipped", "cameraProvider is null")
            return
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val preview = Preview.Builder()
            .build()
            .also {
                it.surfaceProvider = cameraPreview.surfaceProvider
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            Log.d(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            viewModel.logCameraError("binding_failed", e.message, e)
            Toast.makeText(
                context,
                getString(R.string.camera_error, e.message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun capturePhoto() {
        val imageCapture = imageCapture ?: run {
            Log.w(TAG, "capturePhoto called but imageCapture is null")
            viewModel.logCameraError("capture_skipped", "imageCapture is null - camera not ready")
            isCapturing = false
            return
        }
        val context = context ?: run {
            isCapturing = false
            return
        }

        val photoFile = createTempPhotoFile() ?: run {
            Log.e(TAG, "Failed to create temp photo file")
            viewModel.logCameraError("capture_skipped", "Failed to create temp photo file")
            isCapturing = false
            return
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Visual feedback - shrink the shutter button
        shutterInner.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(100)
            .withEndAction {
                shutterInner.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    captureTimeoutJob?.cancel()
                    isCapturing = false
                    Log.d(TAG, "Photo captured: ${photoFile.absolutePath}")
                    val added = viewModel.addPhoto(photoFile)
                    if (!added) {
                        photoFile.delete()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureTimeoutJob?.cancel()
                    isCapturing = false
                    Log.e(TAG, "Photo capture failed", exception)
                    viewModel.logCameraError("capture_failed", exception.message, exception)
                    photoFile.delete()
                    val ctx = context ?: return
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.camera_error, exception.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun toggleFlash() {
        if (captureMode != CaptureMode.REGULAR) {
            Toast.makeText(requireContext(), getString(R.string.flash_ir_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }

        updateFlashIcon()
        imageCapture?.flashMode = flashMode
    }

    private fun updateFlashIcon() {
        val iconRes = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        }
        flashButton.setImageResource(iconRes)
    }

    private fun createTempPhotoFile(): File? {
        val context = context ?: return null
        val storageDir = File(context.cacheDir, "captured_photos")
        if (!storageDir.exists() && !storageDir.mkdirs()) {
            Toast.makeText(context, getString(R.string.camera_file_error), Toast.LENGTH_SHORT).show()
            return null
        }
        val timestamp = System.currentTimeMillis()
        val fileName = "photo_${timestamp}.jpg"
        return File(storageDir, fileName)
    }

    override fun onDestroyView() {
        cameraProvider?.unbindAll()
        tearDownFlirPreviewSurface()
        flirController.disconnect()
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        flirController.onResume()
        // Re-request focus for hardware button support
        view?.requestFocus()
    }

    override fun onPause() {
        flirController.onPause()
        super.onPause()
    }

    companion object {
        private const val TAG = "BatchCaptureFrag"
        private const val REGULAR_CAPTURE_TIMEOUT_MS = 10000L  // 10 seconds for regular camera
        private const val FLIR_CAPTURE_TIMEOUT_MS = 5000L     // 5 seconds for FLIR
    }
}

// Adapter for the horizontal thumbnail strip
class ThumbnailStripAdapter(
    private val onDeleteClick: (String) -> Unit
) : ListAdapter<BatchPhotoItem, ThumbnailStripAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_thumbnail, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnailImage: ImageView = itemView.findViewById(R.id.thumbnailImage)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
        private val photoNumberBadge: TextView = itemView.findViewById(R.id.photoNumberBadge)

        fun bind(item: BatchPhotoItem) {
            thumbnailImage.load(item.file) {
                crossfade(true)
            }

            photoNumberBadge.text = item.number.toString()

            deleteButton.setOnClickListener {
                onDeleteClick(item.id)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BatchPhotoItem>() {
            override fun areItemsTheSame(oldItem: BatchPhotoItem, newItem: BatchPhotoItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: BatchPhotoItem, newItem: BatchPhotoItem): Boolean =
                oldItem == newItem
        }
    }
}
