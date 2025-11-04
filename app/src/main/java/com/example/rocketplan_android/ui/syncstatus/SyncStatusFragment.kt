package com.example.rocketplan_android.ui.syncstatus

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentSyncStatusBinding
import kotlinx.coroutines.launch

class SyncStatusFragment : Fragment() {

    private var _binding: FragmentSyncStatusBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SyncStatusViewModel by viewModels()
    private lateinit var adapter: SyncStatusAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSyncStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().popBackStack()
        }

        adapter = SyncStatusAdapter { projectStatus ->
            val action = SyncStatusFragmentDirections
                .actionSyncStatusFragmentToProjectLandingFragment(projectStatus.projectId)
            findNavController().navigate(action)
        }
        binding.syncStatusRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SyncStatusFragment.adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is SyncStatusUiState.Loading -> {
                        binding.syncStatusRecyclerView.visibility = View.GONE
                        binding.emptyPlaceholder.visibility = View.GONE
                    }
                    is SyncStatusUiState.Empty -> {
                        binding.syncStatusRecyclerView.visibility = View.GONE
                        binding.emptyPlaceholder.visibility = View.VISIBLE
                    }
                    is SyncStatusUiState.Content -> {
                        binding.syncStatusRecyclerView.visibility = View.VISIBLE
                        binding.emptyPlaceholder.visibility = View.GONE
                        adapter.submitList(state.projects)
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

class SyncStatusAdapter(
    private val onItemClick: (ProjectSyncStatus) -> Unit
) : RecyclerView.Adapter<SyncStatusViewHolder>() {

    private var items: List<ProjectSyncStatus> = emptyList()

    fun submitList(newItems: List<ProjectSyncStatus>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SyncStatusViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sync_status, parent, false)
        return SyncStatusViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: SyncStatusViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

class SyncStatusViewHolder(
    itemView: View,
    private val onItemClick: (ProjectSyncStatus) -> Unit
) : RecyclerView.ViewHolder(itemView) {
    private val projectTitle: TextView = itemView.findViewById(R.id.projectTitle)
    private val projectId: TextView = itemView.findViewById(R.id.projectId)
    private val roomCount: TextView = itemView.findViewById(R.id.roomCount)
    private val photoCount: TextView = itemView.findViewById(R.id.photoCount)
    private val albumCount: TextView = itemView.findViewById(R.id.albumCount)

    fun bind(status: ProjectSyncStatus) {
        projectTitle.text = status.projectTitle
        projectId.text = "ID: ${status.projectId}"
        roomCount.text = status.roomCount.toString()
        photoCount.text = status.photoCount.toString()
        albumCount.text = status.albumCount.toString()

        itemView.setOnClickListener {
            onItemClick(status)
        }
    }
}
