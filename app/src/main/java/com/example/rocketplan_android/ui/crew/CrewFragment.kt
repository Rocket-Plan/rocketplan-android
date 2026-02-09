package com.example.rocketplan_android.ui.crew

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.rocketplan_android.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class CrewFragment : Fragment() {

    private val args: CrewFragmentArgs by navArgs()
    private val viewModel: CrewViewModel by viewModels {
        CrewViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var fab: FloatingActionButton

    private lateinit var crewAdapter: CrewAdapter
    private var addCrewDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_crew, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecycler()
        bindListeners()
        observeViewModel()
    }

    private fun bindViews(root: View) {
        swipeRefresh = root.findViewById(R.id.crewSwipeRefresh)
        recyclerView = root.findViewById(R.id.crewRecyclerView)
        emptyState = root.findViewById(R.id.crewEmptyState)
        progressBar = root.findViewById(R.id.crewProgressBar)
        fab = root.findViewById(R.id.crewFab)
    }

    private fun setupRecycler() {
        crewAdapter = CrewAdapter { member ->
            showRemoveConfirmation(member)
        }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = crewAdapter
    }

    private fun bindListeners() {
        swipeRefresh.setOnRefreshListener {
            viewModel.refreshCrew()
        }
        fab.setOnClickListener {
            viewModel.openAddCrewPicker()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> renderState(state) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.addCrewState.collect { state -> renderAddCrewState(state) }
            }
        }
    }

    private fun renderState(state: CrewUiState) {
        swipeRefresh.isRefreshing = false
        when (state) {
            is CrewUiState.Loading -> {
                progressBar.isVisible = true
                recyclerView.isVisible = false
                emptyState.isVisible = false
                fab.isVisible = false
            }
            is CrewUiState.Ready -> {
                progressBar.isVisible = false
                recyclerView.isVisible = !state.isEmpty
                emptyState.isVisible = state.isEmpty
                fab.isVisible = state.isCurrentUserAdmin

                crewAdapter.isCurrentUserAdmin = state.isCurrentUserAdmin
                crewAdapter.submitList(state.crewMembers)
            }
            is CrewUiState.Error -> {
                progressBar.isVisible = false
                recyclerView.isVisible = false
                emptyState.isVisible = true
                fab.isVisible = false
                Toast.makeText(context, R.string.crew_load_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderAddCrewState(state: AddCrewUiState) {
        when (state) {
            is AddCrewUiState.Hidden -> {
                addCrewDialog?.dismiss()
                addCrewDialog = null
            }
            is AddCrewUiState.Loading -> {
                // Show a loading dialog while employees load
                if (addCrewDialog == null) {
                    addCrewDialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.crew_add_title)
                        .setMessage(R.string.config_loading)
                        .setNegativeButton(R.string.cancel) { _, _ ->
                            viewModel.dismissAddCrewPicker()
                        }
                        .setCancelable(false)
                        .show()
                }
            }
            is AddCrewUiState.Ready -> {
                showAddCrewDialog(state)
            }
        }
    }

    private fun showAddCrewDialog(state: AddCrewUiState.Ready) {
        // Dismiss existing dialog if showing a loading state
        addCrewDialog?.dismiss()

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_crew, null)
        val searchInput = dialogView.findViewById<EditText>(R.id.addCrewSearchInput)
        val addCrewRecycler = dialogView.findViewById<RecyclerView>(R.id.addCrewRecyclerView)
        val emptyText = dialogView.findViewById<TextView>(R.id.addCrewEmptyText)

        val addCrewAdapter = AddCrewAdapter { userId ->
            viewModel.toggleEmployeeSelection(userId)
        }
        addCrewRecycler.layoutManager = LinearLayoutManager(context)
        addCrewRecycler.adapter = addCrewAdapter

        // Initial population
        addCrewAdapter.submitList(state.employees)
        addCrewAdapter.selectedIds = state.selectedIds
        emptyText.isVisible = state.employees.isEmpty()
        addCrewRecycler.isVisible = state.employees.isNotEmpty()

        // Search filtering
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                val currentState = viewModel.addCrewState.value
                if (currentState is AddCrewUiState.Ready) {
                    val filtered = if (query.isEmpty()) {
                        currentState.employees
                    } else {
                        currentState.employees.filter {
                            it.name.lowercase().contains(query) ||
                                it.email.lowercase().contains(query)
                        }
                    }
                    addCrewAdapter.submitList(filtered)
                    emptyText.isVisible = filtered.isEmpty()
                    addCrewRecycler.isVisible = filtered.isNotEmpty()
                }
            }
        })

        addCrewDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.crew_add_title)
            .setView(dialogView)
            .setPositiveButton(R.string.crew_add_confirm, null) // set below
            .setNegativeButton(R.string.cancel) { _, _ ->
                viewModel.dismissAddCrewPicker()
            }
            .setCancelable(false)
            .create()

        addCrewDialog?.setOnShowListener {
            addCrewDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                viewModel.confirmAddCrew()
            }
        }

        addCrewDialog?.show()

        // Observe state changes to update the dialog content
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.addCrewState.collect { addState ->
                    when (addState) {
                        is AddCrewUiState.Ready -> {
                            addCrewAdapter.selectedIds = addState.selectedIds
                            addCrewDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
                                addState.selectedIds.isNotEmpty() && !addState.isAdding
                            if (addState.isAdding) {
                                addCrewDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text =
                                    getString(R.string.config_loading)
                            } else {
                                addCrewDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text =
                                    getString(R.string.crew_add_confirm)
                            }
                        }
                        is AddCrewUiState.Hidden -> {
                            // Handled by renderAddCrewState
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun showRemoveConfirmation(member: CrewMember) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.crew_remove_title)
            .setMessage(getString(R.string.crew_remove_message, member.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.crew_remove_confirm) { _, _ ->
                viewModel.removeCrewMember(member.userId)
                Toast.makeText(context, R.string.crew_member_removed, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroyView() {
        addCrewDialog?.dismiss()
        addCrewDialog = null
        super.onDestroyView()
    }
}
