package com.example.rocketplan_android.ui.projects.lossinfo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rocketplan_android.R
import kotlinx.coroutines.launch

class ClaimsInfoFragment : Fragment() {

    private val projectId: Long by lazy {
        requireArguments().getLong(ARG_PROJECT_ID)
    }

    private val viewModel: ProjectLossInfoViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
        factoryProducer = {
            ProjectLossInfoViewModel.provideFactory(requireActivity().application, projectId)
        }
    )

    private lateinit var claimsList: androidx.recyclerview.widget.RecyclerView
    private lateinit var emptyState: View
    private lateinit var loadingIndicator: View
    private val adapter = ClaimsListAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_claims_info_tab, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        claimsList = view.findViewById(R.id.claimsRecyclerView)
        emptyState = view.findViewById(R.id.claimsEmptyState)
        loadingIndicator = view.findViewById(R.id.claimsLoading)

        claimsList.layoutManager = LinearLayoutManager(requireContext())
        claimsList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    private fun render(state: ProjectLossInfoUiState) {
        loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        if (state.claims.isEmpty()) {
            claimsList.visibility = View.GONE
            emptyState.visibility = if (state.isLoading) View.GONE else View.VISIBLE
        } else {
            claimsList.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            adapter.submitList(state.claims)
        }
    }

    companion object {
        private const val ARG_PROJECT_ID = "arg_project_id"

        fun newInstance(projectId: Long): ClaimsInfoFragment {
            return ClaimsInfoFragment().apply {
                arguments = Bundle().apply { putLong(ARG_PROJECT_ID, projectId) }
            }
        }
    }
}
