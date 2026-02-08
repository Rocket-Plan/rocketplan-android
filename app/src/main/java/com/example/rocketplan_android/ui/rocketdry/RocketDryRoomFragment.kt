package com.example.rocketplan_android.ui.rocketdry

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.ui.common.SinglePhotoCaptureFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.io.File
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
    private val materialGoalAdapter = MaterialGoalsAdapter(
        onCardTapped = { item -> onMaterialCardTapped(item) },
        onAddLogTapped = { item -> onMaterialCardTapped(item) }
    )
    private var latestRoomName: String = ""

    // Photo capture callback - stored while navigating to camera
    private var pendingPhotoCallback: ((Uri?) -> Unit)? = null

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
        observePhotoResult()
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
        atmosphericLogAdapter = AtmosphericLogAdapter(onAddLogClicked = { openAtmosphericLogDialog() })
        atmosphericLogsRecyclerView.layoutManager = LinearLayoutManager(context)
        atmosphericLogsRecyclerView.adapter = atmosphericLogAdapter

        materialGoalsRecyclerView.layoutManager = LinearLayoutManager(context)
        materialGoalsRecyclerView.adapter = materialGoalAdapter
    }

    private fun setupClickListeners() {
        startAtmosphericButton.setOnClickListener {
            Log.d(TAG, "Start room atmospheric log tapped (roomId=${args.roomId})")
            openAtmosphericLogDialog()
        }
        addMaterialGoalCard.setOnClickListener {
            Log.d(TAG, "Add material tapped — navigating to area selection (roomId=${args.roomId})")
            findNavController().navigate(
                RocketDryRoomFragmentDirections
                    .actionRocketDryRoomFragmentToMaterialDryingAreaFragment(
                        projectId = args.projectId,
                        roomId = args.roomId
                    )
            )
        }
    }

    private fun onMaterialCardTapped(item: MaterialDryingGoalItem) {
        if (item.targetMoisture == null) {
            // No goal yet → go to Set Goal Average
            Log.d(TAG, "Material '${item.name}' has no goal — navigating to Set Goal Average")
            findNavController().navigate(
                RocketDryRoomFragmentDirections
                    .actionRocketDryRoomFragmentToMaterialDryingGoalFragment(
                        projectId = args.projectId,
                        roomId = args.roomId,
                        materialName = item.name,
                        materialId = item.materialId
                    )
            )
        } else {
            // Has goal → go directly to Set Latest Average
            Log.d(TAG, "Material '${item.name}' has goal=${item.targetMoisture} — navigating to Set Latest Average")
            findNavController().navigate(
                RocketDryRoomFragmentDirections
                    .actionRocketDryRoomFragmentToMaterialDryingReadingFragment(
                        projectId = args.projectId,
                        roomId = args.roomId,
                        materialName = item.name,
                        materialId = item.materialId,
                        goalValue = item.targetMoisture.toFloat()
                    )
            )
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

    private fun observePhotoResult() {
        findNavController().currentBackStackEntry?.savedStateHandle
            ?.getLiveData<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)
            ?.observe(viewLifecycleOwner) { photoPath ->
                if (!photoPath.isNullOrBlank()) {
                    Log.d(TAG, "Received photo from camera: $photoPath")
                    val file = File(photoPath)
                    if (file.exists()) {
                        pendingPhotoCallback?.invoke(Uri.fromFile(file))
                    } else {
                        pendingPhotoCallback?.invoke(null)
                    }
                    pendingPhotoCallback = null
                    // Clear the result to avoid re-processing
                    findNavController().currentBackStackEntry?.savedStateHandle
                        ?.remove<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)
                }
            }
    }

    private fun render(state: RocketDryRoomUiState) {
        when (state) {
            RocketDryRoomUiState.Loading -> {
            Log.d(TAG, "render: Loading state")
            startAtmosphericButton.isEnabled = false
            addMaterialGoalCard.isEnabled = false
            updateAtmosphericLogs(emptyList())
            materialGoalsRecyclerView.isVisible = false
        }

        is RocketDryRoomUiState.Ready -> {
            Log.d(
                TAG,
                "render: Ready state room='${state.roomName}' atmosphericLogs=${state.atmosphericLogCount} materialGoals=${state.materialGoals.size}"
            )
            latestRoomName = state.roomName
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

        val photoCallback = object : AtmosphericLogPhotoCallback {
            override fun onTakePhotoRequested(callback: (Uri?) -> Unit) {
                launchCamera(callback)
            }
        }

        showAtmosphericLogDialog(
            title = title,
            areaLabel = roomName,
            photoCallback = photoCallback
        ) { humidity, temperature, pressure, windSpeed, photoLocalPath ->
            Log.d(
                TAG,
                "Room atmospheric log submitted: rh=$humidity temp=$temperature pressure=$pressure wind=$windSpeed photo=$photoLocalPath room='${latestRoomName}' roomId=${args.roomId}"
            )
            viewModel.addRoomAtmosphericLog(
                humidity = humidity,
                temperature = temperature,
                pressure = pressure,
                windSpeed = windSpeed,
                photoLocalPath = photoLocalPath
            )
            Toast.makeText(
                requireContext(),
                getString(R.string.rocketdry_atmospheric_log_added, roomName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun launchCamera(callback: (Uri?) -> Unit) {
        pendingPhotoCallback = callback
        Log.d(TAG, "Navigating to camera screen")
        findNavController().navigate(
            RocketDryRoomFragmentDirections
                .actionRocketDryRoomFragmentToSinglePhotoCaptureFragment()
        )
    }

    private fun formatLogDate(date: Date): String {
        val formatter = SimpleDateFormat("MMM d, h:mma", Locale.getDefault())
        val formatted = formatter.format(date)
        return formatted
            .replace("AM", "am")
            .replace("PM", "pm")
    }
}
