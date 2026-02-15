package com.example.rocketplan_android.ui.crm

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.CrmContactDto
import com.example.rocketplan_android.data.model.CrmNoteDto
import com.example.rocketplan_android.data.model.CrmTaskDto
import com.example.rocketplan_android.data.model.CrmTaskRequest
import com.example.rocketplan_android.data.model.CrmTaskUpdateRequest
import com.example.rocketplan_android.databinding.FragmentCrmContactDetailBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CrmContactDetailFragment : Fragment() {

    private var _binding: FragmentCrmContactDetailBinding? = null
    private val binding get() = _binding!!

    private val args: CrmContactDetailFragmentArgs by navArgs()
    private val viewModel: CrmContactDetailViewModel by viewModels()

    private var notesAdapter: CrmNotesAdapter? = null
    private var tasksAdapter: CrmTasksAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCrmContactDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupNotesRecyclerView()
        setupTasksRecyclerView()

        binding.editButton.setOnClickListener {
            val action = CrmContactDetailFragmentDirections
                .actionCrmContactDetailToCrmContactForm(args.contactId)
            findNavController().navigate(action)
        }

        binding.addNoteButton.setOnClickListener { showAddNoteDialog() }
        binding.addTaskButton.setOnClickListener { showAddTaskDialog() }

        observeState()
        observeEvents()

        viewModel.loadContact(args.contactId)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.loadingIndicator.isVisible = state.isLoading

                    // Only show the contact tab content when it's the selected tab
                    val selectedTab = binding.tabLayout.selectedTabPosition
                    if (!state.isLoading && state.contact != null) {
                        showTab(
                            contact = selectedTab == 0,
                            notes = selectedTab == 1,
                            tasks = selectedTab == 2
                        )
                    }

                    state.contact?.let { bindContact(it, state) }

                    // Notes
                    binding.notesLoading.isVisible = state.isNotesLoading && selectedTab == 1
                    if (state.notesLoaded && !state.isNotesLoading) {
                        if (state.notes.isEmpty()) {
                            binding.notesEmpty.isVisible = selectedTab == 1
                            binding.notesRecyclerView.isVisible = false
                        } else {
                            binding.notesEmpty.isVisible = false
                            binding.notesRecyclerView.isVisible = selectedTab == 1
                            notesAdapter?.submitList(state.notes)
                        }
                    }

                    // Tasks
                    binding.tasksLoading.isVisible = state.isTasksLoading && selectedTab == 2
                    if (state.tasksLoaded && !state.isTasksLoading) {
                        if (state.tasks.isEmpty()) {
                            binding.tasksEmpty.isVisible = selectedTab == 2
                            binding.tasksRecyclerView.isVisible = false
                        } else {
                            binding.tasksEmpty.isVisible = false
                            binding.tasksRecyclerView.isVisible = selectedTab == 2
                            tasksAdapter?.submitList(state.tasks)
                        }
                    }
                }
            }
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is CrmContactDetailEvent.ContactNotFound -> {
                            Toast.makeText(requireContext(), "Contact not found", Toast.LENGTH_SHORT).show()
                            findNavController().popBackStack()
                        }
                        is CrmContactDetailEvent.ShowError -> {
                            Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showTab(contact = true)
                    1 -> {
                        showTab(notes = true)
                        if (!viewModel.uiState.value.notesLoaded) viewModel.loadNotes(args.contactId)
                    }
                    2 -> {
                        showTab(tasks = true)
                        if (!viewModel.uiState.value.tasksLoaded) viewModel.loadTasks(args.contactId)
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showTab(contact: Boolean = false, notes: Boolean = false, tasks: Boolean = false) {
        binding.contactContent.isVisible = contact
        binding.notesContent.isVisible = notes
        binding.tasksContent.isVisible = tasks
    }

    private fun setupNotesRecyclerView() {
        notesAdapter = CrmNotesAdapter(
            onEditClick = { note -> showEditNoteDialog(note) },
            onDeleteClick = { note -> confirmDeleteNote(note) }
        )
        binding.notesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.notesRecyclerView.adapter = notesAdapter
    }

    private fun setupTasksRecyclerView() {
        tasksAdapter = CrmTasksAdapter(
            onCheckedChange = { task, isChecked -> toggleTaskComplete(task, isChecked) },
            onDeleteClick = { task -> confirmDeleteTask(task) }
        )
        binding.tasksRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.tasksRecyclerView.adapter = tasksAdapter
    }

    // ── Contact ──

    private fun bindContact(contact: CrmContactDto, state: CrmContactDetailUiState) {
        val displayName = contact.displayName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(
                contact.firstName?.trim()?.takeIf { it.isNotBlank() },
                contact.lastName?.trim()?.takeIf { it.isNotBlank() }
            ).joinToString(" ").ifBlank { "Unknown" }

        binding.contactName.text = displayName
        binding.contactEmail.text = contact.email ?: "\u2014"
        binding.contactPhone.text = contact.phone ?: "\u2014"
        binding.contactType.text = contact.type?.replaceFirstChar { it.uppercaseChar() } ?: "\u2014"
        binding.contactSource.text = contact.source ?: "\u2014"
        // Business association
        val businessId = contact.businessId?.takeIf { it.isNotBlank() }
        if (businessId != null) {
            binding.contactBusinessLabel.isVisible = true
            binding.contactBusiness.isVisible = true
            binding.contactBusiness.text = state.businessName ?: businessId
            binding.contactBusiness.setOnClickListener {
                val action = CrmContactDetailFragmentDirections
                    .actionCrmContactDetailToCrmBusinessDetail(businessId)
                findNavController().navigate(action)
            }
        } else {
            binding.contactBusinessLabel.isVisible = false
            binding.contactBusiness.isVisible = false
        }

        binding.contactCompanyName.text = contact.companyName ?: "\u2014"
        binding.contactWebsite.text = contact.website ?: "\u2014"

        val addressText = listOfNotNull(
            contact.address1,
            contact.city,
            contact.state,
            contact.postalCode,
            contact.country
        ).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "\u2014" }
        binding.contactAddress.text = addressText

        // Custom fields — show all definitions, with saved values where available
        CrmCustomFieldRenderer.render(
            requireContext(),
            binding.customFieldsContainer,
            state.customFieldDefinitions,
            contact.customFields
        )
    }

    // ── Notes ──

    private fun showAddNoteDialog() {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.crm_note_hint)
            minLines = 3
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.crm_add_note)
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                val body = editText.text.toString().trim()
                if (body.isNotEmpty()) viewModel.createNote(args.contactId, body)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditNoteDialog(note: CrmNoteDto) {
        val editText = EditText(requireContext()).apply {
            setText(note.bodyText?.takeIf { it.isNotBlank() } ?: note.body ?: "")
            minLines = 3
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Note")
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                val body = editText.text.toString().trim()
                if (body.isNotEmpty() && note.id != null) {
                    viewModel.updateNote(args.contactId, note.id, body)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteNote(note: CrmNoteDto) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.crm_delete_note_title)
            .setMessage(R.string.crm_delete_note_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                note.id?.let { viewModel.deleteNote(args.contactId, it) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Tasks ──

    private fun showAddTaskDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val titleInput = EditText(requireContext()).apply {
            hint = getString(R.string.crm_task_title_hint)
        }
        layout.addView(titleInput)

        val descInput = EditText(requireContext()).apply {
            hint = getString(R.string.crm_task_description_hint)
            minLines = 2
        }
        layout.addView(descInput)

        val dueDateInput = EditText(requireContext()).apply {
            hint = getString(R.string.crm_task_due_date_hint)
            isFocusable = false
            setOnClickListener { pickDate(this) }
        }
        layout.addView(dueDateInput)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.crm_add_task)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isNotEmpty()) {
                    val dueDate = (dueDateInput.tag as? String) ?: run {
                        // Default to 24 hours from now — dueDate is required by GHL
                        val cal = Calendar.getInstance()
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                        String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                    }
                    val request = CrmTaskRequest(
                        contactId = args.contactId,
                        title = title,
                        description = descInput.text.toString().trim().takeIf { it.isNotEmpty() },
                        dueDate = dueDate
                    )
                    viewModel.createTask(args.contactId, request)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickDate(editText: EditText) {
        val cal = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, year, month, day ->
            val dateStr = String.format("%04d-%02d-%02d", year, month + 1, day)
            editText.tag = dateStr
            val displayFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
            cal.set(year, month, day)
            editText.setText(displayFormat.format(cal.time))
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun toggleTaskComplete(task: CrmTaskDto, isCompleted: Boolean) {
        val taskId = task.id ?: return
        viewModel.updateTask(args.contactId, taskId, CrmTaskUpdateRequest(isCompleted = isCompleted))
    }

    private fun confirmDeleteTask(task: CrmTaskDto) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.crm_delete_task_title)
            .setMessage(R.string.crm_delete_task_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                task.id?.let { viewModel.deleteTask(args.contactId, it) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        notesAdapter = null
        tasksAdapter = null
        _binding = null
    }
}
