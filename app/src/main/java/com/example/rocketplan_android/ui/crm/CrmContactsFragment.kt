package com.example.rocketplan_android.ui.crm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentCrmContactsBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class CrmContactsFragment : Fragment() {

    private var _binding: FragmentCrmContactsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CrmContactsViewModel by viewModels()

    private val adapter = CrmContactsAdapter(onContactClicked = { contact ->
        val action = CrmContactsFragmentDirections
            .actionCrmContactsToCrmContactDetail(contact.id)
        findNavController().navigate(action)
    })

    private var isShowingOpportunities = false
    private var isShowingBusinesses = false

    private val opportunitiesFragment: CrmOpportunitiesFragment?
        get() = childFragmentManager.findFragmentByTag(OPPORTUNITIES_TAG) as? CrmOpportunitiesFragment

    private val businessesFragment: CrmBusinessesFragment?
        get() = childFragmentManager.findFragmentByTag(BUSINESSES_TAG) as? CrmBusinessesFragment

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCrmContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState != null) {
            isShowingOpportunities = savedInstanceState.getBoolean("isShowingOpportunities", false)
            isShowingBusinesses = savedInstanceState.getBoolean("isShowingBusinesses", false)
        }

        setupRecyclerView()
        setupSearch()
        setupTabs()
        setupFab()
        observeState()

        binding.swipeRefresh.setOnRefreshListener {
            if (isShowingOpportunities) {
                opportunitiesFragment?.viewModel?.refresh()
                binding.swipeRefresh.isRefreshing = false // opportunities fragment has its own
            } else if (isShowingBusinesses) {
                businessesFragment?.viewModel?.refresh()
                binding.swipeRefresh.isRefreshing = false // businesses fragment has its own
            } else {
                viewModel.refresh()
                // isRefreshing will be set to false by observeState when loading completes
            }
        }
    }

    private fun setupRecyclerView() {
        binding.contactsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.contactsRecyclerView.adapter = adapter

        binding.contactsRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
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

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                when {
                    isShowingOpportunities -> {
                        opportunitiesFragment?.viewModel?.setSearchQuery(newText.orEmpty())
                    }
                    isShowingBusinesses -> {
                        businessesFragment?.viewModel?.setSearchQuery(newText.orEmpty())
                    }
                    else -> {
                        viewModel.setSearchQuery(newText.orEmpty())
                    }
                }
                return true
            }
        })
    }

    private fun setupTabs() {
        // Restore tab if returning
        when {
            isShowingOpportunities -> {
                binding.tabLayout.getTabAt(1)?.select()
                showOpportunities()
            }
            isShowingBusinesses -> {
                binding.tabLayout.getTabAt(2)?.select()
                showBusinesses()
            }
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        showContacts()
                        viewModel.setSelectedType(null)
                    }
                    1 -> {
                        showOpportunities()
                    }
                    2 -> {
                        showBusinesses()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showContacts() {
        isShowingOpportunities = false
        isShowingBusinesses = false
        binding.searchView.isVisible = true
        binding.searchView.queryHint = getString(R.string.crm_search_contacts)
        binding.swipeRefresh.isVisible = true
        binding.contactsRecyclerView.isVisible = true
        binding.fabAddContact.isVisible = true
        binding.opportunitiesContainer.isVisible = false
        binding.businessesContainer.isVisible = false
    }

    private fun showOpportunities() {
        isShowingOpportunities = true
        isShowingBusinesses = false
        binding.searchView.isVisible = true
        binding.searchView.queryHint = getString(R.string.crm_search_opportunities)
        binding.searchView.setQuery("", false)
        binding.swipeRefresh.isVisible = false
        binding.emptyState.isVisible = false
        binding.loadingIndicator.isVisible = false
        binding.fabAddContact.isVisible = true
        binding.opportunitiesContainer.isVisible = true
        binding.businessesContainer.isVisible = false

        if (opportunitiesFragment == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.opportunitiesContainer, CrmOpportunitiesFragment(), OPPORTUNITIES_TAG)
                .commit()
        }
    }

    private fun showBusinesses() {
        isShowingOpportunities = false
        isShowingBusinesses = true
        binding.searchView.isVisible = true
        binding.searchView.queryHint = getString(R.string.crm_search_businesses)
        binding.searchView.setQuery("", false)
        binding.swipeRefresh.isVisible = false
        binding.emptyState.isVisible = false
        binding.loadingIndicator.isVisible = false
        binding.fabAddContact.isVisible = true
        binding.opportunitiesContainer.isVisible = false
        binding.businessesContainer.isVisible = true

        if (businessesFragment == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.businessesContainer, CrmBusinessesFragment(), BUSINESSES_TAG)
                .commit()
        }
    }

    private fun setupFab() {
        binding.fabAddContact.setOnClickListener {
            when {
                isShowingOpportunities -> {
                    val currentPipelineId = opportunitiesFragment?.viewModel?.uiState?.value?.selectedPipelineId
                    val action = CrmContactsFragmentDirections
                        .actionCrmContactsToCrmOpportunityForm(null, currentPipelineId)
                    findNavController().navigate(action)
                }
                isShowingBusinesses -> {
                    val action = CrmContactsFragmentDirections
                        .actionCrmContactsToCrmBusinessForm(null)
                    findNavController().navigate(action)
                }
                else -> {
                    val action = CrmContactsFragmentDirections
                        .actionCrmContactsToCrmContactForm(null)
                    findNavController().navigate(action)
                }
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (isShowingOpportunities || isShowingBusinesses) return@collect

                    binding.swipeRefresh.isRefreshing = false
                    binding.loadingIndicator.isVisible = state.isLoading && state.items.isEmpty()
                    binding.emptyState.isVisible = state.isEmpty && !state.isLoading
                    binding.contactsRecyclerView.isVisible = state.items.isNotEmpty()

                    adapter.submitList(state.items)

                    state.error?.let { error ->
                        binding.emptyMessage.text = error
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isShowingOpportunities", isShowingOpportunities)
        outState.putBoolean("isShowingBusinesses", isShowingBusinesses)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        private const val OPPORTUNITIES_TAG = "opportunities_fragment"
        private const val BUSINESSES_TAG = "businesses_fragment"
    }
}
