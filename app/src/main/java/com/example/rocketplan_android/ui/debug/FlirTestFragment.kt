package com.example.rocketplan_android.ui.debug

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.rocketplan_android.R
import com.example.rocketplan_android.thermal.FlirCameraController
import com.example.rocketplan_android.thermal.FlirState
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import android.util.Log

class FlirTestFragment : Fragment(), CoroutineScope {

    private lateinit var controller: FlirCameraController

    private lateinit var toolbar: MaterialToolbar
    private lateinit var glSurface: android.opengl.GLSurfaceView
    private lateinit var controlsContainer: View
    private lateinit var statusText: TextView
    private lateinit var streamLabel: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var prevButton: MaterialButton
    private lateinit var nextButton: MaterialButton

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private val cameraPermissions = arrayOf(Manifest.permission.CAMERA)

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private fun logSurfaceState(reason: String) {
        glSurface.post {
            val holderValid = runCatching { glSurface.holder.surface.isValid }.getOrDefault(false)
            Log.d(TAG, "[$reason] GLSurface size=${glSurface.width}x${glSurface.height}, shown=${glSurface.isShown}, attached=${glSurface.isAttachedToWindow}, holderValid=$holderValid, visibility=${glSurface.visibility}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = FlirCameraController(requireContext())
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val granted = perms.entries.all { it.value }
            if (granted) {
                startDiscovery()
            } else {
                Toast.makeText(requireContext(), getString(R.string.flir_permission_required), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_flir_test, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.testToolbar)
        glSurface = view.findViewById(R.id.testGlSurface)
        controlsContainer = view.findViewById(R.id.testControls)
        statusText = view.findViewById(R.id.testStatus)
        streamLabel = view.findViewById(R.id.testStreamLabel)
        startButton = view.findViewById(R.id.testStartButton)
        prevButton = view.findViewById(R.id.testPrevStream)
        nextButton = view.findViewById(R.id.testNextStream)

        statusText.text = getString(R.string.flir_test_status_idle)

        // Keep the reliable GL path for this test view and hide controls when streaming
        controller.attachSurface(glSurface, FlirCameraController.SurfaceOrder.ON_TOP)
        // Don't set fusion mode before streaming - use the default like FlirCaptureFragment
        logSurfaceState("onViewCreated")

        toolbar.inflateMenu(R.menu.flir_test_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.flir_test_options -> {
                    showOptionsMenu()
                    true
                }

                else -> false
            }
        }

        startButton.setOnClickListener { startDiscovery() }
        prevButton.setOnClickListener { updateStreamLabel(controller.cycleStream(-1)) }
        nextButton.setOnClickListener { updateStreamLabel(controller.cycleStream(1)) }

        launch {
            controller.state.collectLatest { state ->
                when (state) {
                    FlirState.Idle -> {
                        statusText.text = getString(R.string.flir_test_status_idle)
                        controlsContainer.visibility = View.VISIBLE
                    }
                    FlirState.Discovering -> {
                        statusText.text = getString(R.string.flir_status_discovering)
                        controlsContainer.visibility = View.VISIBLE
                    }
                    is FlirState.Connecting -> {
                        statusText.text = getString(R.string.flir_status_connecting, state.identity.deviceId)
                        controlsContainer.visibility = View.VISIBLE
                    }
                    is FlirState.Streaming -> {
                        statusText.text = getString(R.string.flir_status_streaming, state.identity.deviceId)
                        updateStreamLabel(controller.currentStreamSelection())
                        controlsContainer.visibility = View.GONE
                        logSurfaceState("streaming")
                    }
                    is FlirState.Error -> {
                        statusText.text = state.message
                        controlsContainer.visibility = View.VISIBLE
                        logSurfaceState("error")
                    }
                }
            }
        }

        launch {
            controller.errors.collectLatest { message ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDiscovery() {
        if (hasCameraPermission()) {
            controller.startDiscovery()
        } else {
            permissionLauncher.launch(cameraPermissions)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun updateStreamLabel(selection: FlirCameraController.StreamSelection?) {
        val text = selection?.let {
            val type = if (it.isThermal) getString(R.string.flir_stream_type_thermal) else getString(R.string.flir_stream_type_visible)
            getString(R.string.flir_stream_counter, it.index + 1, it.count, type)
        } ?: getString(R.string.flir_stream_counter_unknown)
        streamLabel.text = text
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
        job.cancel()
        super.onDestroyView()
    }

    private fun showOptionsMenu() {
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

    companion object {
        private const val TAG = "FlirTest"
    }
}
