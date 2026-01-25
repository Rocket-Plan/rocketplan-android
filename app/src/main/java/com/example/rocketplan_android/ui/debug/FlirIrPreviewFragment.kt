package com.example.rocketplan_android.ui.debug

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
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
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.thermal.FlirCameraController
import com.example.rocketplan_android.thermal.FlirState
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class FlirIrPreviewFragment : Fragment(), CoroutineScope {

    private lateinit var controller: FlirCameraController

    private lateinit var glSurface: GLSurfaceView
    private lateinit var controls: View
    private lateinit var statusText: TextView
    private lateinit var startButton: MaterialButton

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private val cameraPermissions = arrayOf(Manifest.permission.CAMERA)

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rocketPlanApp = requireActivity().application as RocketPlanApplication
        controller = FlirCameraController(requireContext(), rocketPlanApp.remoteLogger)
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
        return inflater.inflate(R.layout.fragment_flir_ir_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        glSurface = view.findViewById(R.id.irGlSurface)
        controls = view.findViewById(R.id.irControls)
        statusText = view.findViewById(R.id.irStatus)
        startButton = view.findViewById(R.id.irStartButton)

        controller.attachSurface(glSurface)
        // Don't set fusion mode before streaming - use the default like FlirCaptureFragment

        statusText.text = getString(R.string.flir_ir_preview_status_idle)
        startButton.setOnClickListener { startDiscovery() }

        launch {
            controller.state.collectLatest { state ->
                when (state) {
                    FlirState.Idle -> {
                        controls.visibility = View.VISIBLE
                        statusText.text = getString(R.string.flir_ir_preview_status_idle)
                    }
                    FlirState.Discovering -> {
                        controls.visibility = View.VISIBLE
                        statusText.text = getString(R.string.flir_status_discovering)
                    }
                    is FlirState.Connecting -> {
                        controls.visibility = View.VISIBLE
                        statusText.text = getString(R.string.flir_status_connecting, state.identity.deviceId)
                    }
                    is FlirState.Streaming -> {
                        controls.visibility = View.GONE
                    }
                    is FlirState.Error -> {
                        controls.visibility = View.VISIBLE
                        statusText.text = state.message
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
}
