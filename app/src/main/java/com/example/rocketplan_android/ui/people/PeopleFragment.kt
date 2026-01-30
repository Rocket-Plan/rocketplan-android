package com.example.rocketplan_android.ui.people

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentPeopleBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class PeopleFragment : Fragment() {

    private var _binding: FragmentPeopleBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PeopleViewModel by viewModels()

    private val adapter = PeopleAdapter(
        onEditClicked = { person ->
            // TODO: Implement edit person functionality
            Toast.makeText(requireContext(), "Edit ${person.name}", Toast.LENGTH_SHORT).show()
        },
        onDeleteClicked = { person ->
            confirmDeletePerson(person)
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPeopleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.peopleRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.peopleRecyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.swipeRefresh.isRefreshing = false
                    binding.loadingIndicator.isVisible = state.isLoading && state.items.isEmpty()
                    binding.emptyState.isVisible = state.isEmpty && !state.isLoading
                    binding.peopleRecyclerView.isVisible = state.items.isNotEmpty()

                    adapter.isCurrentUserAdmin = state.isCurrentUserAdmin
                    adapter.submitList(state.items)

                    state.error?.let { error ->
                        binding.emptyMessage.text = error
                    }
                }
            }
        }
    }

    private fun confirmDeletePerson(person: PersonListItem) {
        if (person.isCurrentUser) {
            Toast.makeText(requireContext(), R.string.cannot_delete_self, Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_person_title)
            .setMessage(getString(R.string.delete_person_message, person.name))
            .setPositiveButton(R.string.delete) { dialog, _ ->
                // TODO: Implement delete person via API
                Toast.makeText(requireContext(), R.string.person_deleted, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
