package com.example.rocketplan_android.ui.rocketdry

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExternalAtmosphericLogsFragment : Fragment() {

    companion object {
        private const val TAG = "ExternalAtmosLogsFrag"
    }

    private val args: ExternalAtmosphericLogsFragmentArgs by navArgs()
    private val viewModel: ExternalAtmosphericLogsViewModel by viewModels {
        ExternalAtmosphericLogsViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var logsRecyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var addLogFab: FloatingActionButton

    private lateinit var adapter: ExternalAtmosphericLogAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_external_atmospheric_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeViewModel()
        observePhotoResult()
    }

    private fun observePhotoResult() {
        findNavController().currentBackStackEntry?.savedStateHandle
            ?.getLiveData<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)
            ?.observe(viewLifecycleOwner) { photoPath ->
                if (!photoPath.isNullOrBlank()) {
                    Log.d(TAG, "📸 Received photo from camera: $photoPath")
                    val file = File(photoPath)
                    val validPath = if (file.exists()) photoPath else null

                    // Clear the result to avoid re-processing
                    findNavController().currentBackStackEntry?.savedStateHandle
                        ?.remove<String>(SinglePhotoCaptureFragment.PHOTO_RESULT_KEY)

                    // Check if we have pending dialog values from before camera navigation
                    val pending = viewModel.pendingLogCapture.value
                    if (pending != null) {
                        Log.d(TAG, "📸 Restoring dialog with pending values and photo")
                        viewModel.clearPendingCapture()
                        showAddLogDialogWithPhoto(pending, validPath)
                    } else {
                        Log.w(TAG, "📸 No pending capture found, photo may be lost")
                    }
                }
            }
    }

    private fun initializeViews(view: View) {
        toolbar = view.findViewById(R.id.toolbar)
        logsRecyclerView = view.findViewById(R.id.logsRecyclerView)
        emptyState = view.findViewById(R.id.emptyState)
        addLogFab = view.findViewById(R.id.addLogFab)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        adapter = ExternalAtmosphericLogAdapter { log ->
            showLogDetail(log)
        }
        logsRecyclerView.layoutManager = LinearLayoutManager(context)
        logsRecyclerView.adapter = adapter
    }

    private fun setupFab() {
        addLogFab.setOnClickListener {
            showAddLogDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is ExternalAtmosphericLogsUiState.Ready -> renderState(state)
                        ExternalAtmosphericLogsUiState.Loading -> showLoadingState()
                    }
                }
            }
        }
    }

    private fun showLoadingState() {
        adapter.submitLogs(emptyList())
        emptyState.isVisible = false
    }

    private fun renderState(state: ExternalAtmosphericLogsUiState.Ready) {
        adapter.submitLogs(state.logs)
        emptyState.isVisible = state.logs.isEmpty()
        logsRecyclerView.isVisible = state.logs.isNotEmpty()
    }

    private fun showLogDetail(log: AtmosphericLogItem) {
        Log.d(TAG, "📋 Showing log detail for logId=${log.logId}")
        val bottomSheet = AtmosphericLogDetailBottomSheet.newInstance(log)
        bottomSheet.callback = object : AtmosphericLogDetailBottomSheet.Callback {
            override fun onEditRequested(logId: Long) {
                Log.d(TAG, "✏️ Edit requested for logId=$logId")
                Toast.makeText(requireContext(), "Edit coming soon", Toast.LENGTH_SHORT).show()
            }

            override fun onDeleteRequested(logId: Long) {
                Log.d(TAG, "🗑️ Delete requested for logId=$logId")
                viewModel.deleteAtmosphericLog(logId)
                Toast.makeText(requireContext(), R.string.atmospheric_log_deleted, Toast.LENGTH_SHORT).show()
            }
        }
        bottomSheet.show(childFragmentManager, AtmosphericLogDetailBottomSheet.TAG)
    }

    private fun showAddLogDialog() {
        val now = Date()
        val title = getString(
            R.string.rocketdry_external_log_title,
            formatLogDate(now)
        )

        // Track current values in the dialog for camera navigation
        var currentHumidity: Double? = null
        var currentTemperature: Double? = null
        var currentPressure: Double? = null
        var currentWindSpeed: Double? = null

        showAtmosphericLogDialogWithValueTracking(
            title = title,
            areaLabel = getString(R.string.rocketdry_atmos_room_external),
            photoCallback = object : AtmosphericLogPhotoCallback {
                override fun onTakePhotoRequested(callback: (Uri?) -> Unit) {
                    // Don't use the callback - instead save values and navigate
                    launchCamera(currentHumidity, currentTemperature, currentPressure, currentWindSpeed)
                }
            },
            onValuesChanged = { h, t, p, w ->
                currentHumidity = h
                currentTemperature = t
                currentPressure = p
                currentWindSpeed = w
            }
        ) { humidity, temperature, pressure, windSpeed, photoLocalPath ->
            Log.d(TAG, "📩 External atmospheric log submitted: rh=$humidity temp=$temperature pressure=$pressure wind=$windSpeed photo=$photoLocalPath")
            viewModel.addExternalAtmosphericLog(
                humidity = humidity,
                temperature = temperature,
                pressure = pressure,
                windSpeed = windSpeed,
                photoLocalPath = photoLocalPath
            )
        }
    }

    private fun formatLogDate(date: Date): String {
        val formatter = SimpleDateFormat("MMM d, h:mma", Locale.getDefault())
        val formatted = formatter.format(date)
        return formatted
            .replace("AM", "am")
            .replace("PM", "pm")
    }

    private fun launchCamera(humidity: Double?, temperature: Double?, pressure: Double?, windSpeed: Double?) {
        // Save dialog values to ViewModel before navigation
        viewModel.savePendingCapture(humidity, temperature, pressure, windSpeed)
        Log.d(TAG, "📷 Navigating to camera screen")
        findNavController().navigate(
            ExternalAtmosphericLogsFragmentDirections
                .actionExternalAtmosphericLogsFragmentToSinglePhotoCaptureFragment()
        )
    }

    /**
     * Show the add log dialog pre-populated with values and photo from camera capture.
     */
    private fun showAddLogDialogWithPhoto(pending: PendingLogCapture, photoPath: String?) {
        val now = Date()
        val title = getString(
            R.string.rocketdry_external_log_title,
            formatLogDate(now)
        )

        showAtmosphericLogDialogWithValues(
            title = title,
            areaLabel = getString(R.string.rocketdry_atmos_room_external),
            initialHumidity = pending.humidity,
            initialTemperature = pending.temperature,
            initialPressure = pending.pressure,
            initialWindSpeed = pending.windSpeed,
            initialPhotoPath = photoPath,
            photoCallback = object : AtmosphericLogPhotoCallback {
                override fun onTakePhotoRequested(callback: (Uri?) -> Unit) {
                    // This shouldn't be called since we already have a photo,
                    // but handle it just in case user wants to retake
                    val currentHumidity = pending.humidity
                    val currentTemperature = pending.temperature
                    val currentPressure = pending.pressure
                    val currentWindSpeed = pending.windSpeed
                    launchCamera(currentHumidity, currentTemperature, currentPressure, currentWindSpeed)
                }
            }
        ) { humidity, temperature, pressure, windSpeed, finalPhotoPath ->
            Log.d(TAG, "📩 External atmospheric log submitted: rh=$humidity temp=$temperature pressure=$pressure wind=$windSpeed photo=$finalPhotoPath")
            viewModel.addExternalAtmosphericLog(
                humidity = humidity,
                temperature = temperature,
                pressure = pressure,
                windSpeed = windSpeed,
                photoLocalPath = finalPhotoPath
            )
        }
    }
}
