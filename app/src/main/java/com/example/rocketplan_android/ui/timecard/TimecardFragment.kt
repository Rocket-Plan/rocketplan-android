package com.example.rocketplan_android.ui.timecard

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TimecardFragment : Fragment() {

    private val args: TimecardFragmentArgs by navArgs()
    private val viewModel: TimecardViewModel by viewModels {
        TimecardViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var projectAddress: TextView
    private lateinit var statusLabel: TextView
    private lateinit var elapsedTime: TextView
    private lateinit var typeLabel: TextView
    private lateinit var clockButton: MaterialButton
    private lateinit var todayTotal: TextView
    private lateinit var weekTotal: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView

    private lateinit var adapter: TimecardAdapter
    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateTimeFormatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_timecard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecycler()
        bindListeners()
        observeViewModel()
    }

    private fun bindViews(root: View) {
        projectAddress = root.findViewById(R.id.timecardProjectAddress)
        statusLabel = root.findViewById(R.id.timecardStatusLabel)
        elapsedTime = root.findViewById(R.id.timecardElapsedTime)
        typeLabel = root.findViewById(R.id.timecardTypeLabel)
        clockButton = root.findViewById(R.id.timecardClockButton)
        todayTotal = root.findViewById(R.id.timecardTodayTotal)
        weekTotal = root.findViewById(R.id.timecardWeekTotal)
        recyclerView = root.findViewById(R.id.timecardRecyclerView)
        emptyState = root.findViewById(R.id.timecardEmptyState)
    }

    private fun setupRecycler() {
        adapter = TimecardAdapter { item ->
            if (!item.isActive) {
                showEditTimecardDialog(item)
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter
    }

    private fun bindListeners() {
        clockButton.setOnClickListener {
            val state = viewModel.uiState.value
            if (state is TimecardUiState.Ready) {
                if (state.isClockedIn) {
                    viewModel.clockOut()
                    Toast.makeText(context, R.string.timecard_clocked_out_toast, Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.clockIn()
                    Toast.makeText(context, R.string.timecard_clocked_in_toast, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: TimecardUiState) {
        when (state) {
            is TimecardUiState.Loading -> {
                projectAddress.text = getString(R.string.loading_project)
                elapsedTime.text = "00:00:00"
                adapter.submitList(emptyList())
            }

            is TimecardUiState.Ready -> {
                projectAddress.text = state.projectAddress

                // Clock status
                if (state.isClockedIn) {
                    statusLabel.text = getString(R.string.timecard_clocked_in)
                    elapsedTime.text = formatElapsedTime(state.elapsedSeconds)
                    clockButton.text = getString(R.string.timecard_clock_out)
                    clockButton.setBackgroundColor(
                        resources.getColor(R.color.dark_red, null)
                    )
                    typeLabel.isVisible = true
                    typeLabel.text = state.activeTimecard?.timecardTypeName
                } else {
                    statusLabel.text = getString(R.string.timecard_clocked_out)
                    elapsedTime.text = "00:00:00"
                    clockButton.text = getString(R.string.timecard_clock_in)
                    clockButton.setBackgroundColor(
                        resources.getColor(R.color.main_purple, null)
                    )
                    typeLabel.isVisible = false
                }

                // Totals
                todayTotal.text = formatHoursMinutes(state.todayTotalSeconds)
                weekTotal.text = formatHoursMinutes(state.weekTotalSeconds)

                // Timecards list
                adapter.submitList(state.timecards)
                emptyState.isVisible = state.timecards.isEmpty()
            }

            is TimecardUiState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatElapsedTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs)
    }

    private fun formatHoursMinutes(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return String.format(Locale.getDefault(), "%d:%02d", hours, minutes)
    }

    private fun showEditTimecardDialog(item: TimecardUiItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_timecard, null)
        val timeInButton = dialogView.findViewById<MaterialButton>(R.id.editTimecardTimeIn)
        val timeOutButton = dialogView.findViewById<MaterialButton>(R.id.editTimecardTimeOut)
        val notesInput = dialogView.findViewById<EditText>(R.id.editTimecardNotes)
        val deleteButton = dialogView.findViewById<MaterialButton>(R.id.editTimecardDelete)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.editTimecardCancel)
        val saveButton = dialogView.findViewById<MaterialButton>(R.id.editTimecardSave)

        var selectedTimeIn = item.timeIn
        var selectedTimeOut = item.timeOut

        timeInButton.text = dateTimeFormatter.format(selectedTimeIn)
        timeOutButton.text = selectedTimeOut?.let { dateTimeFormatter.format(it) }
            ?: getString(R.string.timecard_select_time)
        notesInput.setText(item.notes ?: "")

        timeInButton.setOnClickListener {
            showDateTimePicker(selectedTimeIn) { date ->
                selectedTimeIn = date
                timeInButton.text = dateTimeFormatter.format(date)
            }
        }

        timeOutButton.setOnClickListener {
            showDateTimePicker(selectedTimeOut ?: selectedTimeIn) { date ->
                selectedTimeOut = date
                timeOutButton.text = dateTimeFormatter.format(date)
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.timecard_edit_title))
            .setView(dialogView)
            .create()

        deleteButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.timecard_delete_title))
                .setMessage(getString(R.string.timecard_delete_confirm))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewModel.deleteTimecard(item.timecardId)
                    Toast.makeText(context, R.string.timecard_deleted_toast, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .show()
        }

        cancelButton.setOnClickListener { dialog.dismiss() }

        saveButton.setOnClickListener {
            val notes = notesInput.text?.toString()?.takeIf { it.isNotBlank() }
            viewModel.updateTimecard(
                timecardId = item.timecardId,
                timeIn = selectedTimeIn,
                timeOut = selectedTimeOut,
                notes = notes
            )
            Toast.makeText(context, R.string.timecard_updated_toast, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDateTimePicker(initialDate: Date, onSelected: (Date) -> Unit) {
        val calendar = Calendar.getInstance()
        calendar.time = initialDate

        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                TimePickerDialog(
                    requireContext(),
                    { _, hourOfDay, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        onSelected(calendar.time)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    false
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
}
