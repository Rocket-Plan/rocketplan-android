package com.example.rocketplan_android.ui.rocketdry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RocketDryRoomFragment : Fragment() {

    companion object {
        private const val TAG = "RocketDryRoomFragment"
    }

    private val args: RocketDryRoomFragmentArgs by navArgs()
    private val viewModel: RocketDryRoomViewModel by viewModels {
        RocketDryRoomViewModel.provideFactory(
            requireActivity().application,
            args.projectId,
            args.roomId
        )
    }

    private lateinit var projectAddress: TextView
    private lateinit var roomTitle: TextView
    private lateinit var roomIcon: ImageView
    private lateinit var startAtmosphericButton: MaterialButton
    private lateinit var atmosphericLogsRecyclerView: RecyclerView
    private lateinit var addMaterialGoalCard: View
    private lateinit var materialGoalsRecyclerView: RecyclerView

    private lateinit var atmosphericLogAdapter: AtmosphericLogAdapter
    private val materialGoalAdapter = MaterialGoalsAdapter { item ->
        showAddMaterialLogDialog(item)
    }
    private var latestRoomName: String = ""
    private var materialOptions: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_rocket_dry_room, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupRecycler()
        setupClickListeners()
        observeViewModel()
    }

    private fun initializeViews(view: View) {
        projectAddress = view.findViewById(R.id.roomProjectAddress)
        roomTitle = view.findViewById(R.id.roomTitle)
        roomIcon = view.findViewById(R.id.roomIcon)
        startAtmosphericButton = view.findViewById(R.id.startRoomAtmosphericButton)
        atmosphericLogsRecyclerView = view.findViewById(R.id.atmosphericLogsRecyclerView)
        addMaterialGoalCard = view.findViewById(R.id.addMaterialGoalCard)
        materialGoalsRecyclerView = view.findViewById(R.id.materialGoalsRecyclerView)
    }

    private fun setupRecycler() {
        atmosphericLogAdapter = AtmosphericLogAdapter { openAtmosphericLogDialog() }
        atmosphericLogsRecyclerView.layoutManager = LinearLayoutManager(context)
        atmosphericLogsRecyclerView.adapter = atmosphericLogAdapter

        materialGoalsRecyclerView.layoutManager = LinearLayoutManager(context)
        materialGoalsRecyclerView.adapter = materialGoalAdapter
    }

    private fun setupClickListeners() {
        startAtmosphericButton.setOnClickListener {
            Log.d(TAG, "🧪 Start room atmospheric log tapped (roomId=${args.roomId})")
            openAtmosphericLogDialog()
        }
        addMaterialGoalCard.setOnClickListener {
            Log.d(TAG, "🎯 Add material goal tapped (roomId=${args.roomId})")
            showAddMaterialGoalDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: RocketDryRoomUiState) {
        when (state) {
            RocketDryRoomUiState.Loading -> {
            Log.d(TAG, "🎨 render: Loading state")
            startAtmosphericButton.isEnabled = false
            addMaterialGoalCard.isEnabled = false
            updateAtmosphericLogs(emptyList())
            materialGoalsRecyclerView.isVisible = false
            materialOptions = emptyList()
        }

        is RocketDryRoomUiState.Ready -> {
            Log.d(
                TAG,
                "🎨 render: Ready state room='${state.roomName}' atmosphericLogs=${state.atmosphericLogCount} materialGoals=${state.materialGoals.size}"
            )
            latestRoomName = state.roomName
            materialOptions = state.materialOptions
            projectAddress.text = state.projectAddress
            roomTitle.text = state.roomName
            roomIcon.setImageResource(state.roomIconRes)
            roomIcon.contentDescription = state.roomName
            updateAtmosphericLogs(state.atmosphericLogs)
                startAtmosphericButton.isEnabled = true
                addMaterialGoalCard.isEnabled = true
                updateMaterialGoals(state.materialGoals)
            }
        }
    }

    private fun updateAtmosphericLogs(logs: List<AtmosphericLogItem>) {
        atmosphericLogAdapter.submitLogs(logs)
        atmosphericLogsRecyclerView.isVisible = logs.isNotEmpty()
    }

    private fun updateMaterialGoals(goals: List<MaterialDryingGoalItem>) {
        materialGoalAdapter.submitList(goals)
        materialGoalsRecyclerView.isVisible = goals.isNotEmpty()
    }

    private fun openAtmosphericLogDialog() {
        val roomName = latestRoomName.ifBlank { getString(R.string.room) }
        val title = getString(
            R.string.rocketdry_room_log_title,
            roomName,
            formatLogDate(Date())
        )
        showAtmosphericLogDialog(title) { humidity, temperature, pressure, windSpeed ->
            Log.d(
                TAG,
                "📩 Room atmospheric log submitted: rh=$humidity temp=$temperature pressure=$pressure wind=$windSpeed room='${latestRoomName}' roomId=${args.roomId}"
            )
            viewModel.addRoomAtmosphericLog(humidity, temperature, pressure, windSpeed)
            Toast.makeText(
                requireContext(),
                getString(R.string.rocketdry_atmospheric_log_added, roomName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Suppress("DEPRECATION") // SOFT_INPUT_ADJUST_RESIZE still needed for dialog keyboard behavior
    private fun showAddMaterialGoalDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_material_goal, null)
        val materialNameInputLayout =
            dialogView.findViewById<TextInputLayout>(R.id.materialNameInputLayout)
        val materialNameInput =
            dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.materialNameInput)
        val targetInputLayout =
            dialogView.findViewById<TextInputLayout>(R.id.targetMoistureInputLayout)
        val targetInput =
            dialogView.findViewById<TextInputEditText>(R.id.targetMoistureInput)
        val cancelButton =
            dialogView.findViewById<MaterialButton>(R.id.cancelMaterialGoalButton)
        val saveButton =
            dialogView.findViewById<MaterialButton>(R.id.saveMaterialGoalButton)

        val materialAdapter = ArrayAdapter(
            requireContext(),
            R.layout.list_item_dropdown,
            materialOptions
        )
        materialNameInput.setAdapter(materialAdapter)
        materialNameInputLayout.isEndIconVisible = materialAdapter.count > 0
        materialNameInput.threshold = 0
        materialNameInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && materialAdapter.count > 0) {
                materialNameInput.showDropDown()
            }
        }
        materialNameInput.setOnClickListener {
            if (materialAdapter.count > 0) {
                materialNameInput.showDropDown()
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            materialNameInputLayout.error = null
            targetInputLayout.error = null

            val name = materialNameInput.text?.toString().orEmpty().trim()
            if (name.isBlank()) {
                materialNameInputLayout.error = getString(R.string.rocketdry_goal_material_required)
                Log.w(TAG, "⚠️ Material goal validation failed: name is blank")
                return@setOnClickListener
            }
            val targetRaw = targetInput.text?.toString()?.trim().orEmpty()
            val target = targetRaw.toDoubleOrNull()
            if (targetRaw.isNotBlank() && target == null) {
                targetInputLayout.error = getString(R.string.rocketdry_goal_target_invalid)
                Log.w(TAG, "⚠️ Material goal validation failed: invalid target '$targetRaw'")
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {
                Log.d(
                    TAG,
                    "📤 Saving material goal name='$name' target=$target roomId=${args.roomId}"
                )
                val success = viewModel.addMaterialDryingGoal(name, target)
                if (success) {
                    Log.d(TAG, "✅ Material goal saved: name='$name'")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.rocketdry_material_goal_added, name),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                } else {
                    Log.e(TAG, "❌ Failed to save material goal: name='$name'")
                    Toast.makeText(
                        requireContext(),
                        R.string.rocketdry_material_goal_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        dialog.show()
        materialNameInput.requestFocus()
        materialNameInput.post {
            if (materialAdapter.count > 0) {
                materialNameInput.showDropDown()
            }
        }
    }

    @Suppress("DEPRECATION") // SOFT_INPUT_ADJUST_RESIZE still needed for dialog keyboard behavior
    private fun showAddMaterialLogDialog(item: MaterialDryingGoalItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_moisture_log, null)
        val readingInputLayout =
            dialogView.findViewById<TextInputLayout>(R.id.moistureReadingInputLayout)
        val readingInput =
            dialogView.findViewById<TextInputEditText>(R.id.moistureReadingInput)
        val locationInput =
            dialogView.findViewById<TextInputEditText>(R.id.moistureLocationInput)
        val cancelButton =
            dialogView.findViewById<MaterialButton>(R.id.cancelMoistureLogButton)
        val saveButton =
            dialogView.findViewById<MaterialButton>(R.id.saveMoistureLogButton)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rocketdry_material_add_log_title, item.name))
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            readingInputLayout.error = null

            val readingRaw = readingInput.text?.toString()?.trim().orEmpty()
            val reading = readingRaw.toDoubleOrNull()
            if (reading == null) {
                readingInputLayout.error = getString(R.string.rocketdry_material_invalid_reading)
                return@setOnClickListener
            }
            val location = locationInput.text?.toString()?.trim().orEmpty().ifBlank { null }

            viewLifecycleOwner.lifecycleScope.launch {
                val success = viewModel.addMaterialMoistureLog(item.materialId, reading, location)
                if (success) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.rocketdry_material_log_added, item.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.rocketdry_material_save_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        dialog.show()
        readingInput.requestFocus()
    }

    private fun formatLogDate(date: Date): String {
        val formatter = SimpleDateFormat("MMM d, h:mma", Locale.getDefault())
        val formatted = formatter.format(date)
        return formatted
            .replace("AM", "am")
            .replace("PM", "pm")
    }
}
