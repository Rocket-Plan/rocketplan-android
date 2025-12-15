package com.example.rocketplan_android.ui.syncstatus

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.databinding.FragmentSyncStatusBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class SyncStatusFragment : Fragment() {

    private var _binding: FragmentSyncStatusBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SyncStatusViewModel by viewModels()
    private lateinit var projectAdapter: SyncStatusAdapter
    private lateinit var activeSyncAdapter: SyncActivityAdapter
    private lateinit var logAdapter: SyncActivityAdapter
    private lateinit var queueAdapter: SyncQueueAdapter

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

        projectAdapter = SyncStatusAdapter { projectStatus ->
            val action = SyncStatusFragmentDirections
                .actionSyncStatusFragmentToProjectLandingFragment(projectStatus.projectId)
            findNavController().navigate(action)
        }
        activeSyncAdapter = SyncActivityAdapter()
        logAdapter = SyncActivityAdapter()
        queueAdapter = SyncQueueAdapter()

        binding.syncStatusRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = projectAdapter
        }
        binding.syncActivityRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = activeSyncAdapter
        }
        binding.syncLogRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = logAdapter
        }
        binding.syncQueueRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = queueAdapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is SyncStatusUiState.Loading -> showLoading()
                    is SyncStatusUiState.Empty -> showEmpty()
                    is SyncStatusUiState.Content -> renderContent(state)
                }
            }
        }
    }

    private fun showLoading() {
        binding.syncStatusRecyclerView.isVisible = false
        binding.syncActivityRecyclerView.isVisible = false
        binding.syncLogRecyclerView.isVisible = false
        binding.syncQueueRecyclerView.isVisible = false
        binding.emptyPlaceholder.isVisible = false
        binding.activePlaceholder.isVisible = false
        binding.logPlaceholder.isVisible = false
        binding.queuePlaceholder.isVisible = false
    }

    private fun showEmpty() {
        binding.syncStatusRecyclerView.isVisible = false
        binding.syncActivityRecyclerView.isVisible = false
        binding.syncLogRecyclerView.isVisible = false
        binding.syncQueueRecyclerView.isVisible = false
        binding.emptyPlaceholder.isVisible = true
        binding.activePlaceholder.isVisible = true
        binding.logPlaceholder.isVisible = true
        binding.queuePlaceholder.isVisible = true
    }

    private fun renderContent(state: SyncStatusUiState.Content) {
        projectAdapter.submitList(state.projects)
        binding.syncStatusRecyclerView.isVisible = state.projects.isNotEmpty()
        binding.emptyPlaceholder.isVisible = state.projects.isEmpty()

        activeSyncAdapter.submitList(state.activeSyncs)
        binding.syncActivityRecyclerView.isVisible = state.activeSyncs.isNotEmpty()
        binding.activePlaceholder.isVisible = state.activeSyncs.isEmpty()

        logAdapter.submitList(state.recentEvents)
        binding.syncLogRecyclerView.isVisible = state.recentEvents.isNotEmpty()
        binding.logPlaceholder.isVisible = state.recentEvents.isEmpty()

        queueAdapter.submitList(state.queueItems)
        binding.syncQueueRecyclerView.isVisible = state.queueItems.isNotEmpty()
        binding.queuePlaceholder.isVisible = state.queueItems.isEmpty()
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

class SyncActivityAdapter : RecyclerView.Adapter<SyncActivityViewHolder>() {

    private var items: List<SyncActivityItem> = emptyList()

    fun submitList(newItems: List<SyncActivityItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SyncActivityViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sync_activity, parent, false)
        return SyncActivityViewHolder(view)
    }

    override fun onBindViewHolder(holder: SyncActivityViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

class SyncActivityViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    private val title: TextView = itemView.findViewById(R.id.syncActivityTitle)
    private val status: TextView = itemView.findViewById(R.id.syncActivityStatus)
    private val timestamp: TextView = itemView.findViewById(R.id.syncActivityTimestamp)

    fun bind(item: SyncActivityItem) {
        title.text = item.title
        status.text = item.status
        timestamp.text = formatTimestamp(item.timestamp)

        val colorRes = when (item.type) {
            SyncActivityType.ACTIVE -> R.color.main_purple
            SyncActivityType.ERROR -> R.color.warning_red
            SyncActivityType.INFO -> R.color.light_text_rp
        }
        status.setTextColor(ContextCompat.getColor(itemView.context, colorRes))
    }

    private fun formatTimestamp(timestampMillis: Long): String =
        DATE_FORMAT.format(Date(timestampMillis))

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    }
}

class SyncQueueAdapter : RecyclerView.Adapter<SyncQueueViewHolder>() {

    private var items: List<SyncQueueItem> = emptyList()

    fun submitList(newItems: List<SyncQueueItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SyncQueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sync_queue, parent, false)
        return SyncQueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: SyncQueueViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

class SyncQueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    private val title: TextView = itemView.findViewById(R.id.queueTitle)
    private val status: TextView = itemView.findViewById(R.id.queueStatus)
    private val timestamp: TextView = itemView.findViewById(R.id.queueTimestamp)
    private val detail: TextView = itemView.findViewById(R.id.queueDetail)

    fun bind(item: SyncQueueItem) {
        val operation = item.operationType.name.lowercase()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        title.text = "${item.entityType} • $operation"

        val statusLabel = when (item.status) {
            SyncStatus.PENDING -> itemView.context.getString(R.string.sync_status_pending)
            SyncStatus.FAILED -> itemView.context.getString(R.string.sync_status_failed)
            else -> item.status.name
        }
        status.text = statusLabel
        val statusColor = when (item.status) {
            SyncStatus.FAILED -> R.color.warning_red
            else -> R.color.main_purple
        }
        status.setTextColor(ContextCompat.getColor(itemView.context, statusColor))

        val timeLabel = when {
            item.lastAttemptAt != null -> itemView.context.getString(R.string.sync_time_last_attempt)
            item.scheduledAt != null -> itemView.context.getString(R.string.sync_time_scheduled)
            else -> itemView.context.getString(R.string.sync_time_created)
        }
        timestamp.text = "$timeLabel • ${formatTimestamp(item.displayTimestamp)}"

        val error = item.errorMessage
        if (error.isNullOrBlank()) {
            detail.visibility = View.GONE
        } else {
            detail.visibility = View.VISIBLE
            detail.text = error
        }
    }

    private fun formatTimestamp(timestampMillis: Long): String =
        DATE_FORMAT.format(Date(timestampMillis))

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    }
}
