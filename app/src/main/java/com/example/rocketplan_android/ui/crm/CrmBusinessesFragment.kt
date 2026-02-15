package com.example.rocketplan_android.ui.crm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.databinding.FragmentCrmBusinessesBinding
import kotlinx.coroutines.launch

class CrmBusinessesFragment : Fragment() {

    private var _binding: FragmentCrmBusinessesBinding? = null
    private val binding get() = _binding!!

    val viewModel: CrmBusinessesViewModel by viewModels()

    private val adapter = CrmBusinessesAdapter { business ->
        try {
            val action = CrmContactsFragmentDirections
                .actionCrmContactsToCrmBusinessDetail(business.id)
            parentFragment?.findNavController()?.navigate(action)
        } catch (e: Exception) {
            android.util.Log.w("CrmBusinesses", "Navigation to business detail failed", e)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCrmBusinessesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        observeState()

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }
    }

    private fun setupRecyclerView() {
        binding.businessesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.businessesRecyclerView.adapter = adapter

        binding.businessesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = lm.itemCount
                if (lastVisible >= total - 5) {
                    viewModel.loadNextPage()
                }
            }
        })
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.swipeRefresh.isRefreshing = false
                    binding.loadingIndicator.isVisible = state.isLoading && state.items.isEmpty()
                    binding.emptyState.isVisible = state.isEmpty && !state.isLoading
                    binding.businessesRecyclerView.isVisible = state.items.isNotEmpty()

                    adapter.submitList(state.items)

                    state.error?.let { error ->
                        binding.emptyMessage.text = error
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
