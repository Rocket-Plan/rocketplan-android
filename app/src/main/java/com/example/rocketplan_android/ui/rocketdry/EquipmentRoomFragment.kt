package com.example.rocketplan_android.ui.rocketdry

import android.app.DatePickerDialog
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
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class EquipmentRoomFragment : Fragment() {

    companion object {
        private const val TAG = "EquipmentRoomFragment"
    }

    private val args: EquipmentRoomFragmentArgs by navArgs()
    private val viewModel: EquipmentRoomViewModel by viewModels {
        EquipmentRoomViewModel.provideFactory(
            requireActivity().application,
            args.projectId,
            args.roomId
        )
    }

    private lateinit var projectAddress: TextView
    private lateinit var roomTitle: TextView
    private lateinit var roomIcon: ImageView
    private lateinit var addEquipmentCard: View
    private lateinit var equipmentRecyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var addCardSubtitle: TextView

    private lateinit var adapter: RoomEquipmentAdapter
    private var latestState: EquipmentRoomUiState.Ready? = null
    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_equipment_room, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecycler()
        bindListeners()
        observeViewModel()
    }

    private fun bindViews(root: View) {
        projectAddress = root.findViewById(R.id.equipmentProjectAddress)
        roomTitle = root.findViewById(R.id.equipmentRoomTitle)
        roomIcon = root.findViewById(R.id.equipmentRoomIcon)
        addEquipmentCard = root.findViewById(R.id.addEquipmentCard)
        equipmentRecyclerView = root.findViewById(R.id.roomEquipmentRecyclerView)
        emptyState = root.findViewById(R.id.equipmentEmptyState)
        addCardSubtitle = root.findViewById(R.id.equipmentEmptyHint)
    }

    private fun setupRecycler() {
        adapter = RoomEquipmentAdapter(
            onIncrease = { item -> viewModel.changeQuantity(item, 1) },
            onDecrease = { item -> viewModel.changeQuantity(item, -1) },
            onStartDateClick = { item -> openDatePicker(item.startDate) { viewModel.updateStartDate(item, it) } },
            onEndDateClick = { item -> openDatePicker(item.endDate ?: item.startDate) { viewModel.updateEndDate(item, it) } },
            onDelete = { item -> confirmDelete(item) },
            onMove = { item -> confirmMove(item) },
            onTransfer = { item -> confirmTransfer(item) },
            onHistory = { item -> showMovementHistory(item) },
            dateFormatter = { item -> formatDates(item) }
        )
        equipmentRecyclerView.layoutManager = LinearLayoutManager(context)
        equipmentRecyclerView.adapter = adapter
    }

    private fun bindListeners() {
        addEquipmentCard.setOnClickListener {
            Log.d(TAG, "➕ Add equipment tapped for roomId=${args.roomId}")
            openAddEquipmentDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: EquipmentRoomUiState) {
        when (state) {
            EquipmentRoomUiState.Loading -> {
                projectAddress.text = getString(R.string.loading_project)
                roomTitle.text = ""
                adapter.submitList(emptyList())
                emptyState.isVisible = false
            }

            is EquipmentRoomUiState.Ready -> {
                latestState = state
                projectAddress.text = state.projectAddress
                roomTitle.text = state.roomName
                roomIcon.setImageResource(state.roomIconRes)
                roomIcon.contentDescription = state.roomName
                adapter.moveTransferEnabled = state.moveTransferEnabled
                adapter.submitList(state.equipment)
                emptyState.isVisible = state.equipment.isEmpty()
                addCardSubtitle.isVisible = state.equipment.isEmpty()
            }
        }
    }

    private fun formatDates(item: RoomEquipmentItem): FormattedEquipmentDates {
        val placeholder = getString(R.string.equipment_room_date_placeholder)
        val startLabel = item.startDate?.let { dateFormatter.format(it) } ?: placeholder
        val endLabel = item.endDate?.let { dateFormatter.format(it) } ?: placeholder
        return FormattedEquipmentDates(startLabel, endLabel)
    }

    private fun openAddEquipmentDialog() {
        val readyState = latestState ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_equipment, null)
        val typeLayout = dialogView.findViewById<TextInputLayout>(R.id.equipmentTypeInputLayout)
        val typeInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.equipmentTypeInput)
        val minusButton = dialogView.findViewById<View>(R.id.addEquipmentMinus)
        val plusButton = dialogView.findViewById<View>(R.id.addEquipmentPlus)
        val quantityLabel = dialogView.findViewById<TextView>(R.id.addEquipmentQuantity)
        val startDateButton = dialogView.findViewById<MaterialButton>(R.id.addEquipmentStartDate)
        val endDateButton = dialogView.findViewById<MaterialButton>(R.id.addEquipmentEndDate)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.addEquipmentCancel)
        val saveButton = dialogView.findViewById<MaterialButton>(R.id.addEquipmentSave)

        var selectedQuantity = 1
        var selectedStart: Date = Date()
        var selectedEnd: Date = Date()
        val options = readyState.typeOptions
        val labels = options.map { it.label }
        typeInput.setSimpleItems(labels.toTypedArray())
        typeInput.setOnItemClickListener { _, _, position, _ ->
            typeLayout.error = null
            typeInput.setTag(R.id.equipmentTypeInput, options.getOrNull(position)?.key)
        }

        startDateButton.text = dateFormatter.format(selectedStart)
        endDateButton.text = dateFormatter.format(selectedEnd)
        quantityLabel.text = selectedQuantity.toString()

        minusButton.setOnClickListener {
            selectedQuantity = (selectedQuantity - 1).coerceAtLeast(1)
            quantityLabel.text = selectedQuantity.toString()
        }
        plusButton.setOnClickListener {
            selectedQuantity += 1
            quantityLabel.text = selectedQuantity.toString()
        }

        startDateButton.setOnClickListener {
            openDatePicker(selectedStart) { date ->
                selectedStart = date
                if (date.after(selectedEnd)) {
                    selectedEnd = date
                    endDateButton.text = dateFormatter.format(date)
                }
                startDateButton.text = dateFormatter.format(date)
            }
        }

        endDateButton.setOnClickListener {
            openDatePicker(selectedEnd) { date ->
                selectedEnd = date
                endDateButton.text = dateFormatter.format(date)
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.equipment_room_add_title))
            .setView(dialogView)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val selectedLabel = typeInput.text?.toString().orEmpty()
            val typeKey = typeInput.getTag(R.id.equipmentTypeInput) as? String
                ?: options.firstOrNull { it.label.equals(selectedLabel, ignoreCase = true) }?.key

            if (typeKey.isNullOrBlank()) {
                typeLayout.error = getString(R.string.equipment_room_no_type_error)
                return@setOnClickListener
            }

            viewModel.addEquipment(typeKey, selectedQuantity, selectedStart, selectedEnd)
            Toast.makeText(requireContext(), R.string.equipment_room_saved, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun confirmDelete(item: RoomEquipmentItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.equipment_room_delete))
            .setMessage(getString(R.string.equipment_room_delete_confirm, item.typeLabel))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteEquipment(item)
                Toast.makeText(
                    requireContext(),
                    R.string.equipment_room_deleted_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun confirmMove(item: RoomEquipmentItem) {
        val otherRooms = latestState?.otherRooms ?: emptyList()
        if (otherRooms.isEmpty()) {
            Toast.makeText(requireContext(), "No other rooms available", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_move_equipment, null)
        val roomLayout = dialogView.findViewById<TextInputLayout>(R.id.moveRoomInputLayout)
        val roomInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.moveRoomInput)
        val minusButton = dialogView.findViewById<View>(R.id.moveQuantityMinus)
        val plusButton = dialogView.findViewById<View>(R.id.moveQuantityPlus)
        val quantityLabel = dialogView.findViewById<TextView>(R.id.moveQuantityLabel)
        val quantityHint = dialogView.findViewById<TextView>(R.id.moveQuantityHint)
        val noteInput = dialogView.findViewById<TextInputLayout>(R.id.moveNoteInputLayout)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.moveCancel)
        val saveButton = dialogView.findViewById<MaterialButton>(R.id.moveSave)

        val roomNames = otherRooms.map { it.name }
        val roomAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, roomNames)
        roomInput.setAdapter(roomAdapter)

        var selectedRoom: RoomMeta? = null
        var selectedQuantity = item.quantity

        roomInput.setOnItemClickListener { _, _, position, _ ->
            selectedRoom = otherRooms.getOrNull(position)
        }

        quantityLabel.text = selectedQuantity.toString()
        quantityHint.text = getString(R.string.equipment_room_move_all, item.quantity)
        quantityHint.visibility = android.view.View.VISIBLE

        minusButton.setOnClickListener {
            selectedQuantity = (selectedQuantity - 1).coerceAtLeast(1)
            quantityLabel.text = selectedQuantity.toString()
        }
        plusButton.setOnClickListener {
            selectedQuantity = (selectedQuantity + 1).coerceAtMost(item.quantity)
            quantityLabel.text = selectedQuantity.toString()
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.equipment_room_move_confirm, item.typeLabel))
            .setView(dialogView)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val room = selectedRoom
            if (room == null) {
                roomLayout.error = getString(R.string.equipment_room_no_room_error)
                return@setOnClickListener
            }
            // Keep full moves explicit. A queued null means "whatever remains" on the
            // server and can move more than the user selected after an offline retry.
            val quantity = selectedQuantity
            val note = noteInput.editText?.text?.toString()?.takeIf { it.isNotBlank() }
            viewModel.moveEquipment(
                item = item,
                toRoomId = room.roomId,
                toRoomUuid = room.uuid,
                quantity = quantity,
                note = note
            )
            Toast.makeText(requireContext(), "Equipment move queued", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun confirmTransfer(item: RoomEquipmentItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_transfer_equipment, null)
        val projectLayout = dialogView.findViewById<TextInputLayout>(R.id.transferProjectInputLayout)
        val projectInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.transferProjectInput)
        val roomLayout = dialogView.findViewById<TextInputLayout>(R.id.transferRoomInputLayout)
        val roomInput = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.transferRoomInput)
        val loadingIndicator = dialogView.findViewById<View>(R.id.transferLoading)
        val minusButton = dialogView.findViewById<View>(R.id.transferQuantityMinus)
        val plusButton = dialogView.findViewById<View>(R.id.transferQuantityPlus)
        val quantityLabel = dialogView.findViewById<TextView>(R.id.transferQuantityLabel)
        val quantityHint = dialogView.findViewById<TextView>(R.id.transferQuantityHint)
        val noteInput = dialogView.findViewById<TextInputLayout>(R.id.transferNoteInputLayout)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.transferCancel)
        val saveButton = dialogView.findViewById<MaterialButton>(R.id.transferSave)

        var selectedProject: OfflineProjectEntity? = null
        var selectedRoom: OfflineRoomEntity? = null
        var selectedQuantity = item.quantity
        var availableRooms = emptyList<OfflineRoomEntity>()

        quantityLabel.text = selectedQuantity.toString()
        quantityHint.text = getString(R.string.equipment_room_transfer_all, item.quantity)
        quantityHint.visibility = View.VISIBLE
        roomLayout.isEnabled = false
        saveButton.isEnabled = false

        minusButton.setOnClickListener {
            selectedQuantity = (selectedQuantity - 1).coerceAtLeast(1)
            quantityLabel.text = selectedQuantity.toString()
        }
        plusButton.setOnClickListener {
            selectedQuantity = (selectedQuantity + 1).coerceAtMost(item.quantity)
            quantityLabel.text = selectedQuantity.toString()
        }

        lifecycleScope.launch {
            val projects = viewModel.getAllProjectsForTransfer()
            val projectNames = projects.map { it.title }
            val projectAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, projectNames)
            projectInput.setAdapter(projectAdapter)

            projectInput.setOnItemClickListener { _, _, position, _ ->
                selectedProject = projects.getOrNull(position)
                selectedRoom = null
                roomInput.setText("", false)
                roomLayout.isEnabled = true
                saveButton.isEnabled = false
                loadingIndicator.visibility = View.VISIBLE

                lifecycleScope.launch {
                    val project = selectedProject
                    if (project?.serverId != null) {
                        availableRooms = viewModel.getRoomsForProject(project.serverId)
                    } else {
                        availableRooms = emptyList()
                    }
                    loadingIndicator.visibility = View.GONE
                    val roomNames = availableRooms.map { it.title }
                    val roomAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, roomNames)
                    roomInput.setAdapter(roomAdapter)
                }
            }
        }

        roomInput.setOnItemClickListener { _, _, position, _ ->
            selectedRoom = availableRooms.getOrNull(position)
            saveButton.isEnabled = selectedRoom != null && selectedProject != null
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.equipment_room_transfer_confirm, item.typeLabel))
            .setView(dialogView)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val project = selectedProject
            val room = selectedRoom
            if (project == null) {
                projectLayout.error = getString(R.string.equipment_room_no_project_error)
                return@setOnClickListener
            }
            if (room == null) {
                roomLayout.error = getString(R.string.equipment_room_no_room_error)
                return@setOnClickListener
            }
            val quantity = if (selectedQuantity == item.quantity) item.quantity else selectedQuantity
            val note = noteInput.editText?.text?.toString()?.takeIf { it.isNotBlank() }
            viewModel.transferEquipment(
                item = item,
                toRoomId = room.serverId ?: return@setOnClickListener,
                toRoomUuid = room.uuid,
                toProjectId = project.serverId ?: return@setOnClickListener,
                quantity = quantity,
                note = note
            )
            Toast.makeText(requireContext(), "Equipment transfer queued", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showMovementHistory(item: RoomEquipmentItem) {
        lifecycleScope.launch {
            val dialogView = layoutInflater.inflate(R.layout.dialog_equipment_history, null)
            val loadingView = dialogView.findViewById<View>(R.id.historyLoading)
            val emptyView = dialogView.findViewById<TextView>(R.id.historyEmpty)
            val errorView = dialogView.findViewById<TextView>(R.id.historyError)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.historyRecycler)

            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.equipment_room_history_title))
                .setView(dialogView)
                .setPositiveButton(R.string.equipment_room_dialog_cancel, null)
                .create()

            loadingView.visibility = View.VISIBLE
            recyclerView.layoutManager = LinearLayoutManager(context)
            val historyAdapter = MovementHistoryAdapter()
            recyclerView.adapter = historyAdapter

            dialog.show()

            val equipment = viewModel.getEquipmentForUuid(item.uuid)
            if (equipment == null || equipment.serverId == null) {
                loadingView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                emptyView.setText(R.string.equipment_room_history_error)
                return@launch
            }

            val result = viewModel.loadMovementHistory(equipment.serverId)
            loadingView.visibility = View.GONE

            result.onSuccess { movements ->
                if (movements.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                } else {
                    historyAdapter.submitList(movements)
                    recyclerView.visibility = View.VISIBLE
                }
            }.onFailure {
                errorView.visibility = View.VISIBLE
            }
        }
    }

    private fun openDatePicker(initialDate: Date?, onSelected: (Date) -> Unit) {
        val calendar = Calendar.getInstance()
        if (initialDate != null) calendar.time = initialDate
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth, 0, 0, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                onSelected(calendar.time)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
}
