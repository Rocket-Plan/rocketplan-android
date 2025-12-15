package com.example.rocketplan_android.ui.projects.flir

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.example.rocketplan_android.thermal.FlirCameraController
import com.example.rocketplan_android.thermal.FlirState
import com.example.rocketplan_android.ui.projects.PhotosAddedResult
import com.example.rocketplan_android.ui.projects.RoomDetailFragment
import com.example.rocketplan_android.ui.projects.batchcapture.BatchCaptureEvent
import com.example.rocketplan_android.ui.projects.batchcapture.BatchCaptureUiState
import com.example.rocketplan_android.ui.projects.batchcapture.BatchCaptureViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class FlirCaptureFragment : Fragment() {

    private val args: FlirCaptureFragmentArgs by navArgs()
    private val viewModel: BatchCaptureViewModel by viewModels {
        BatchCaptureViewModel.provideFactory(
            requireActivity().application,
            args.projectId,
            args.roomId
        )
    }

    private lateinit var controller: FlirCameraController

    private lateinit var glSurface: android.opengl.GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var photoCountText: TextView
    private lateinit var streamLabel: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var snapshotButton: MaterialButton
    private lateinit var optionsButton: MaterialButton
    private lateinit var uploadButton: MaterialButton
    private lateinit var closeButton: MaterialButton
    private lateinit var prevStreamButton: MaterialButton
    private lateinit var nextStreamButton: MaterialButton
    private lateinit var loadingOverlay: View
    private lateinit var loadingText: TextView

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<Array<String>>
    private val cameraPermissions = arrayOf(Manifest.permission.CAMERA)
    private var isStreaming = false
    private var lastUiState: BatchCaptureUiState = BatchCaptureUiState()

    private fun logSurfaceAndOverlay(reason: String) {
        glSurface.post {
            val holderValid = runCatching { glSurface.holder.surface.isValid }.getOrDefault(false)
            Log.d(
                TAG,
                "[$reason] GLSurface size=${glSurface.width}x${glSurface.height}, shown=${glSurface.isShown}, attached=${glSurface.isAttachedToWindow}, holderValid=$holderValid, overlayVisible=${loadingOverlay.isVisible}, overlayVis=${loadingOverlay.visibility}, overlayAlpha=${loadingOverlay.alpha}"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = FlirCameraController(requireContext())
        cameraPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val granted = permissions.entries.all { it.value }
                if (granted) {
                    startDiscovery()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.flir_permission_required),
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
    ): View = inflater.inflate(R.layout.fragment_flir_capture, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        controller.attachSurface(glSurface)
        logSurfaceAndOverlay("onViewCreated")
        setupListeners()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }

    override fun onPause() {
        controller.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        controller.disconnect()
        super.onDestroyView()
    }

    private fun bindViews(root: View) {
        glSurface = root.findViewById(R.id.glSurface)
        statusText = root.findViewById(R.id.statusText)
        photoCountText = root.findViewById(R.id.photoCountText)
        streamLabel = root.findViewById(R.id.streamLabel)
        startButton = root.findViewById(R.id.startButton)
        snapshotButton = root.findViewById(R.id.snapshotButton)
        optionsButton = root.findViewById(R.id.optionsButton)
        uploadButton = root.findViewById(R.id.uploadButton)
        closeButton = root.findViewById(R.id.closeButton)
        prevStreamButton = root.findViewById(R.id.prevStreamButton)
        nextStreamButton = root.findViewById(R.id.nextStreamButton)
        loadingOverlay = root.findViewById(R.id.loadingOverlay)
        loadingText = root.findViewById(R.id.loadingText)

        statusText.text = getString(R.string.flir_status_idle)
        streamLabel.text = getString(R.string.flir_stream_counter_unknown)
        snapshotButton.isEnabled = false
        prevStreamButton.isEnabled = false
        nextStreamButton.isEnabled = false
        lastUiState = viewModel.uiState.value
    }

    private fun setupListeners() {
        startButton.setOnClickListener { startDiscovery() }
        snapshotButton.setOnClickListener { controller.requestSnapshot() }
        closeButton.setOnClickListener { findNavController().navigateUp() }
        optionsButton.setOnClickListener { showOptionsMenu(it) }
        prevStreamButton.setOnClickListener { updateStreamLabel(controller.cycleStream(-1)) }
        nextStreamButton.setOnClickListener { updateStreamLabel(controller.cycleStream(1)) }

        uploadButton.setOnClickListener {
            viewModel.commitPhotos()
        }
    }

    private fun showOptionsMenu(anchor: View) {
        val items = arrayOf(
            getString(R.string.flir_palette),
            getString(R.string.flir_fusion),
            getString(R.string.flir_measurements)
        )
        val checked = booleanArrayOf(false, false, false)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.flir_options)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> controller.setPalette(if (isChecked) 1 else 0)
                    1 -> controller.setFusionMode(if (isChecked) com.example.rocketplan_android.thermal.FusionMode.VISUAL_ONLY else com.example.rocketplan_android.thermal.FusionMode.THERMAL_ONLY)
                    2 -> controller.setMeasurementsEnabled(isChecked)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    controller.state.collect { state ->
                        isStreaming = state is FlirState.Streaming
                        prevStreamButton.isEnabled = isStreaming
                        nextStreamButton.isEnabled = isStreaming
                        updateSnapshotButtonState()
                        when (state) {
                            FlirState.Idle -> {
                                statusText.text = getString(R.string.flir_status_idle)
                                streamLabel.text =
                                    getString(R.string.flir_stream_counter_unknown)
                            }

                            FlirState.Discovering -> {
                                statusText.text = getString(R.string.flir_status_discovering)
                                streamLabel.text =
                                    getString(R.string.flir_stream_counter_unknown)
                            }

                            is FlirState.Connecting -> {
                                statusText.text =
                                    getString(R.string.flir_status_connecting, state.identity.deviceId)
                                streamLabel.text =
                                    getString(R.string.flir_stream_counter_unknown)
                            }

                            is FlirState.Streaming -> {
                                statusText.text = getString(
                                    R.string.flir_status_streaming,
                                    state.identity.deviceId
                                )
                                updateStreamLabel(controller.currentStreamSelection())
                                logSurfaceAndOverlay("streaming")
                            }

                            is FlirState.Error -> {
                                statusText.text = state.message
                                streamLabel.text =
                                    getString(R.string.flir_stream_counter_unknown)
                                logSurfaceAndOverlay("error")
                            }
                        }
                    }
                }

                launch {
                    controller.errors.collect { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }

                launch {
                    controller.snapshots.collect { file ->
                        val added = viewModel.addPhoto(file)
                        if (!added) {
                            file.delete()
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.flir_snapshot_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.flir_snapshot_saved),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                launch {
                    viewModel.uiState.collect { state ->
                        lastUiState = state
                        val count = state.photoCount
                        photoCountText.text = getString(
                            if (state.isProcessing) R.string.flir_upload_in_progress else R.string.flir_upload_ready,
                            count
                        )
                        uploadButton.isEnabled = state.hasPhotos && !state.isProcessing
                        updateSnapshotButtonState()
                        // Keep overlay hidden; preview stays on top for reliability.
                        loadingOverlay.isVisible = false
                        if (state.isProcessing) {
                            loadingText.text = getString(R.string.flir_upload_in_progress, count)
                        }
                        logSurfaceAndOverlay("uiState isProcessing=${state.isProcessing}")
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

    private fun handleEvent(event: BatchCaptureEvent) {
        when (event) {
            is BatchCaptureEvent.PhotosCommitted -> {
                findNavController().previousBackStackEntry?.savedStateHandle
                    ?.set(
                        RoomDetailFragment.PHOTOS_ADDED_RESULT_KEY,
                        PhotosAddedResult(event.count, event.assemblyId)
                    )
                Toast.makeText(
                    requireContext(),
                    getString(R.string.flir_upload_success),
                    Toast.LENGTH_SHORT
                ).show()
                findNavController().navigateUp()
            }

            is BatchCaptureEvent.Error -> {
                Toast.makeText(
                    requireContext(),
                    event.message ?: getString(R.string.flir_upload_error),
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

            BatchCaptureEvent.BatchCleared -> Unit
        }
    }

    private fun startDiscovery() {
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(cameraPermissions)
            return
        }
        controller.startDiscovery()
    }

    private fun hasCameraPermission(): Boolean =
        cameraPermissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

    private fun updateSnapshotButtonState() {
        val enabledByUiState = lastUiState.canTakeMore && !lastUiState.isProcessing
        snapshotButton.isEnabled = isStreaming && enabledByUiState
    }

    private fun updateStreamLabel(selection: FlirCameraController.StreamSelection?) {
        val text = selection?.let {
            val type =
                if (it.isThermal) getString(R.string.flir_stream_type_thermal) else getString(R.string.flir_stream_type_visible)
            getString(R.string.flir_stream_counter, it.index + 1, it.count, type)
        } ?: getString(R.string.flir_stream_counter_unknown)
        streamLabel.text = text
    }

    companion object {
        private const val TAG = "FlirCaptureFrag"
    }
}
