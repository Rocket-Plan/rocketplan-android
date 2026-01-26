package com.example.rocketplan_android.ui.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SupportFragment : Fragment() {

    private val viewModel: SupportViewModel by viewModels {
        SupportViewModel.provideFactory(requireActivity().application)
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var loadingIndicator: ProgressBar

    private val adapter = SupportConversationsAdapter { conversation ->
        findNavController().navigate(
            SupportFragmentDirections.actionToChat(conversation.id)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_support, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.conversationsRecyclerView)
        emptyState = view.findViewById(R.id.emptyState)
        loadingIndicator = view.findViewById(R.id.loadingIndicator)
        val newConversationFab: FloatingActionButton = view.findViewById(R.id.newConversationFab)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        newConversationFab.setOnClickListener {
            findNavController().navigate(
                SupportFragmentDirections.actionToNew()
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    adapter.submitList(state.conversations)

                    loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    emptyState.visibility = if (state.isEmpty && !state.isLoading) View.VISIBLE else View.GONE
                    recyclerView.visibility = if (state.isEmpty || state.isLoading) View.GONE else View.VISIBLE
                }
            }
        }
    }
}
