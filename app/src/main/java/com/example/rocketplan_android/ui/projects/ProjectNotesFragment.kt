package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class ProjectNotesFragment : Fragment() {

    private val args: ProjectNotesFragmentArgs by navArgs()

    private val viewModel: ProjectNotesViewModel by viewModels {
        ProjectNotesViewModel.provideFactory(
            requireActivity().application,
            args.projectId,
            args.roomId.takeIf { it != 0L }
        )
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var subtitle: TextView
    private lateinit var toolbar: MaterialToolbar
    private val adapter = ProjectNotesAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_project_notes, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.notesRecyclerView)
        emptyState = view.findViewById(R.id.notesEmptyState)
        subtitle = view.findViewById(R.id.notesSubtitle)
        toolbar = view.findViewById(R.id.notesToolbar)
        val addButton: FloatingActionButton = view.findViewById(R.id.addNoteFab)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        toolbar.title = getString(R.string.all_project_notes_title)

        addButton.setOnClickListener { showAddNoteDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    adapter.submitList(state.items)
                    subtitle.text = state.subtitle
                    emptyState.visibility = if (state.isEmpty) View.VISIBLE else View.GONE
                    recyclerView.visibility = if (state.isEmpty) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun showAddNoteDialog() {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.add_note)
            minLines = 3
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(editText)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_note)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    viewModel.addNote(text)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
