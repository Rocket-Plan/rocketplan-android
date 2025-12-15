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
import com.example.rocketplan_android.thermal.FusionMode
import com.example.rocketplan_android.ui.projects.PhotosAddedResult
import com.example.rocketplan_android.ui.projects.RoomDetailFragment
import com.example.rocketplan_android.ui.projects.batchcapture.BatchCaptureEvent
import com.example.rocketplan_android.ui.projects.batchcapture.BatchCaptureViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private lateinit var startButton: MaterialButton
    private lateinit var snapshotButton: MaterialButton
    private lateinit var paletteSwitch: SwitchMaterial
    private lateinit var fusionSwitch: SwitchMaterial
    private lateinit var measurementsSwitch: SwitchMaterial
    private lateinit var uploadButton: MaterialButton
    private lateinit var closeButton: MaterialButton
    private lateinit var loadingOverlay: View
    private lateinit var loadingText: TextView

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<Array<String>>
    private val cameraPermissions = arrayOf(Manifest.permission.CAMERA)

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

        if (hasCameraPermission()) {
            startDiscovery()
        } else {
            cameraPermissionLauncher.launch(cameraPermissions)
        }
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
        startButton = root.findViewById(R.id.startButton)
        snapshotButton = root.findViewById(R.id.snapshotButton)
        paletteSwitch = root.findViewById(R.id.paletteSwitch)
        fusionSwitch = root.findViewById(R.id.fusionSwitch)
        measurementsSwitch = root.findViewById(R.id.measurementsSwitch)
        uploadButton = root.findViewById(R.id.uploadButton)
        closeButton = root.findViewById(R.id.closeButton)
        loadingOverlay = root.findViewById(R.id.loadingOverlay)
        loadingText = root.findViewById(R.id.loadingText)

        statusText.text = getString(R.string.flir_status_idle)
    }

    private fun setupListeners() {
        startButton.setOnClickListener { startDiscovery() }
        snapshotButton.setOnClickListener { controller.requestSnapshot() }
        closeButton.setOnClickListener { findNavController().navigateUp() }

        paletteSwitch.setOnCheckedChangeListener { _, isChecked ->
            controller.setPalette(if (isChecked) 1 else 0)
        }

        fusionSwitch.setOnCheckedChangeListener { _, isChecked ->
            controller.setFusionMode(if (isChecked) FusionMode.VISUAL_ONLY else FusionMode.THERMAL_ONLY)
        }

        measurementsSwitch.setOnCheckedChangeListener { _, isChecked ->
            controller.setMeasurementsEnabled(isChecked)
        }

        uploadButton.setOnClickListener {
            viewModel.commitPhotos()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    controller.state.collect { state ->
                        when (state) {
                            FlirState.Idle -> {
                                statusText.text = getString(R.string.flir_status_idle)
                            }

                            FlirState.Discovering -> {
                                statusText.text = getString(R.string.flir_status_discovering)
                            }

                            is FlirState.Connecting -> {
                                statusText.text =
                                    getString(R.string.flir_status_connecting, state.identity.deviceId)
                            }

                            is FlirState.Streaming -> {
                                statusText.text = getString(
                                    R.string.flir_status_streaming,
                                    state.identity.deviceId
                                )
                                logSurfaceAndOverlay("streaming")
                            }

                            is FlirState.Error -> {
                                statusText.text = state.message
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
                        val count = state.photoCount
                        photoCountText.text = getString(
                            if (state.isProcessing) R.string.flir_upload_in_progress else R.string.flir_upload_ready,
                            count
                        )
                        uploadButton.isEnabled = state.hasPhotos && !state.isProcessing
                        snapshotButton.isEnabled = state.canTakeMore && !state.isProcessing
                        loadingOverlay.isVisible = state.isProcessing
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

    companion object {
        private const val TAG = "FlirCaptureFrag"
    }
}
