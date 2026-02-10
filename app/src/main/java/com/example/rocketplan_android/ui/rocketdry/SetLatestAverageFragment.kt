package com.example.rocketplan_android.ui.rocketdry

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.example.rocketplan_android.ui.common.SinglePhotoCaptureFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.io.File

class SetLatestAverageFragment : Fragment() {

    private val args: SetLatestAverageFragmentArgs by navArgs()
    private val viewModel: RocketDryRoomViewModel by viewModels {
        RocketDryRoomViewModel.provideFactory(
            requireActivity().application,
            args.projectId,
            args.roomId
        )
    }

    private var capturedPhotoPath: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_set_latest_average, container, false)
    }

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Allow ScrollView to scroll when keyboard is open
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        view.findViewById<ImageView>(R.id.backButton).setOnClickListener {
            findNavController().popBackStack()
        }

        val areaName = args.areaName.takeIf { it.isNotBlank() } ?: ""
        val description = view.findViewById<TextView>(R.id.latestDescription)
        description.text = getString(R.string.drying_set_latest_description, args.materialName, areaName)

        val readingInput = view.findViewById<EditText>(R.id.readingInput)
        readingInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                readingInput.clearFocus()
                true
            } else false
        }

        val photoPreview = view.findViewById<ImageView>(R.id.photoPreview)
        val photoHint = view.findViewById<TextView>(R.id.photoHint)
        val takePhotoButton = view.findViewById<MaterialButton>(R.id.takePhotoButton)
        val removeButton = view.findViewById<MaterialButton>(R.id.removeButton)
        val saveWithoutButton = view.findViewById<TextView>(R.id.saveWithoutButton)

        // Observe photo result from camera
        findNavController().currentBackStackEntry?.savedStateHandle
            ?.getLiveData<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)
            ?.observe(viewLifecycleOwner) { photoPath ->
                if (!photoPath.isNullOrBlank()) {
                    val file = File(photoPath)
                    if (file.exists()) {
                        capturedPhotoPath = photoPath
                        photoPreview.setImageURI(Uri.fromFile(file))
                        photoPreview.isVisible = true
                        photoHint.isVisible = false
                        takePhotoButton.text = getString(R.string.save)
                    }
                    findNavController().currentBackStackEntry?.savedStateHandle
                        ?.remove<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)
                }
            }

        takePhotoButton.setOnClickListener {
            if (capturedPhotoPath != null) {
                saveReading(readingInput, photoLocalPath = capturedPhotoPath)
            } else {
                findNavController().navigate(
                    SetLatestAverageFragmentDirections
                        .actionMaterialDryingReadingFragmentToSinglePhotoCaptureFragment()
                )
            }
        }

        saveWithoutButton.setOnClickListener {
            saveReading(readingInput, photoLocalPath = null)
        }

        removeButton.setOnClickListener {
            saveRemoved(readingInput)
        }

        readingInput.requestFocus()
    }

    private fun saveReading(readingInput: EditText, photoLocalPath: String?) {
        val raw = readingInput.text?.toString()?.trim().orEmpty()
        val reading = raw.toDoubleOrNull()
        if (reading == null) {
            Toast.makeText(requireContext(), R.string.rocketdry_material_invalid_reading, Toast.LENGTH_SHORT).show()
            return
        }

        val goalValue = args.goalValue.toDouble()

        viewLifecycleOwner.lifecycleScope.launch {
            val success = if (args.materialId == -1L) {
                val created = viewModel.addMaterialDryingGoal(args.materialName, goalValue)
                if (created) {
                    viewModel.addMaterialMoistureLogByName(
                        materialName = args.materialName,
                        moistureContent = reading,
                        location = null,
                        dryingGoal = goalValue,
                        photoLocalPath = photoLocalPath
                    )
                } else false
            } else {
                viewModel.addMaterialMoistureLog(
                    materialId = args.materialId,
                    moistureContent = reading,
                    location = null,
                    dryingGoal = goalValue,
                    photoLocalPath = photoLocalPath
                )
            }

            if (success) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.rocketdry_material_log_added, args.materialName),
                    Toast.LENGTH_SHORT
                ).show()
                findNavController().popBackStack(R.id.rocketDryRoomFragment, false)
            } else {
                Toast.makeText(requireContext(), R.string.rocketdry_material_save_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        // Restore default soft input mode
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        super.onDestroyView()
    }

    private fun saveRemoved(readingInput: EditText) {
        val raw = readingInput.text?.toString()?.trim().orEmpty()
        val reading = raw.toDoubleOrNull()

        viewLifecycleOwner.lifecycleScope.launch {
            val goalValue = args.goalValue.toDouble()
            val success = if (args.materialId == -1L) {
                val created = viewModel.addMaterialDryingGoal(args.materialName, goalValue)
                if (created) {
                    viewModel.addMaterialMoistureLogByName(
                        materialName = args.materialName,
                        moistureContent = reading ?: 0.0,
                        location = "Removed",
                        dryingGoal = goalValue,
                        removed = true
                    )
                } else false
            } else {
                viewModel.addMaterialMoistureLog(
                    materialId = args.materialId,
                    moistureContent = reading ?: 0.0,
                    location = "Removed",
                    dryingGoal = goalValue,
                    removed = true
                )
            }

            if (success) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.rocketdry_material_log_added, args.materialName),
                    Toast.LENGTH_SHORT
                ).show()
                findNavController().popBackStack(R.id.rocketDryRoomFragment, false)
            } else {
                Toast.makeText(requireContext(), R.string.rocketdry_material_save_error, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
