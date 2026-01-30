package com.example.rocketplan_android.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R
import java.io.File

/**
 * Simple single-photo capture fragment using CameraX.
 * Returns the captured photo path via savedStateHandle.
 */
class SinglePhotoCaptureFragment : Fragment() {

    companion object {
        private const val TAG = "SinglePhotoCapture"
        const val PHOTO_RESULT_KEY = "single_photo_result"
    }

    // Views
    private lateinit var cameraPreview: PreviewView
    private lateinit var closeButton: ImageButton
    private lateinit var flashButton: ImageButton
    private lateinit var shutterButton: FrameLayout
    private lateinit var shutterInner: View

    // Camera
    private var imageCapture: ImageCapture? = null
    private var isCapturing = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<Array<String>>
    private val cameraPermissions = arrayOf(Manifest.permission.CAMERA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val granted = permissions.entries.all { it.value }
                if (granted) {
                    startCamera()
                } else if (isAdded) {
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
    ): View = inflater.inflate(R.layout.fragment_single_photo_capture, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Remove status bar padding so preview can draw to the top edge
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, navInsets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        bindViews(view)
        setupListeners()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(cameraPermissions)
        }
    }

    private fun bindViews(view: View) {
        cameraPreview = view.findViewById(R.id.cameraPreview)
        closeButton = view.findViewById(R.id.closeButton)
        flashButton = view.findViewById(R.id.flashButton)
        shutterButton = view.findViewById(R.id.shutterButton)
        shutterInner = view.findViewById(R.id.shutterInner)
    }

    private fun setupListeners() {
        closeButton.setOnClickListener {
            findNavController().navigateUp()
        }

        shutterButton.setOnClickListener {
            capturePhoto()
        }

        flashButton.setOnClickListener {
            toggleFlash()
        }
    }

    private fun hasCameraPermission(): Boolean =
        cameraPermissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
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
        val cameraProvider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
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
            Toast.makeText(
                context,
                getString(R.string.camera_error, e.message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun capturePhoto() {
        if (isCapturing) return
        isCapturing = true

        val imageCapture = imageCapture ?: run {
            Log.w(TAG, "capturePhoto called but imageCapture is null")
            isCapturing = false
            return
        }
        val context = context ?: run {
            isCapturing = false
            return
        }

        val photoFile = createTempPhotoFile() ?: run {
            Log.e(TAG, "Failed to create temp photo file")
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
                    isCapturing = false
                    Log.d(TAG, "Photo captured: ${photoFile.absolutePath}")

                    // Return result to previous fragment
                    findNavController().previousBackStackEntry?.savedStateHandle
                        ?.set(PHOTO_RESULT_KEY, photoFile.absolutePath)
                    findNavController().navigateUp()
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    Log.e(TAG, "Photo capture failed", exception)
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
        // Use filesDir instead of cacheDir so photos persist
        val storageDir = File(context.filesDir, "captured_photos")
        if (!storageDir.exists() && !storageDir.mkdirs()) {
            Toast.makeText(context, getString(R.string.camera_file_error), Toast.LENGTH_SHORT).show()
            return null
        }
        val timestamp = System.currentTimeMillis()
        val fileName = "atmos_${timestamp}.jpg"
        return File(storageDir, fileName)
    }

    override fun onDestroyView() {
        cameraProvider?.unbindAll()
        super.onDestroyView()
    }
}
