package com.example.rocketplan_android.ui.imageprocessor

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import com.example.rocketplan_android.databinding.FragmentImageProcessorAssembliesBinding
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

class ImageProcessorAssembliesFragment : Fragment() {

    private var _binding: FragmentImageProcessorAssembliesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ImageProcessorAssembliesViewModel by viewModels()
    private lateinit var adapter: ImageProcessorAssembliesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageProcessorAssembliesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().popBackStack()
        }

        adapter = ImageProcessorAssembliesAdapter()
        binding.assembliesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.assembliesRecyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        ImageProcessorAssembliesUiState.Loading -> {
                            binding.loadingIndicator.visibility = View.VISIBLE
                            binding.assembliesRecyclerView.visibility = View.GONE
                            binding.emptyPlaceholder.visibility = View.GONE
                        }

                        ImageProcessorAssembliesUiState.Empty -> {
                            binding.loadingIndicator.visibility = View.GONE
                            binding.assembliesRecyclerView.visibility = View.GONE
                            binding.emptyPlaceholder.visibility = View.VISIBLE
                        }

                        is ImageProcessorAssembliesUiState.Content -> {
                            binding.loadingIndicator.visibility = View.GONE
                            binding.assembliesRecyclerView.visibility = View.VISIBLE
                            binding.emptyPlaceholder.visibility = View.GONE
                            adapter.submitList(state.assemblies)
                        }
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

private class ImageProcessorAssembliesAdapter :
    RecyclerView.Adapter<ImageProcessorAssembliesViewHolder>() {

    private var items: List<ImageProcessorAssemblyEntity> = emptyList()

    fun submitList(newItems: List<ImageProcessorAssemblyEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageProcessorAssembliesViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_processor_assembly, parent, false)
        return ImageProcessorAssembliesViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageProcessorAssembliesViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

private class ImageProcessorAssembliesViewHolder(itemView: View) :
    RecyclerView.ViewHolder(itemView) {

    private val assemblyIdText: TextView = itemView.findViewById(R.id.assemblyIdText)
    private val statusChip: TextView = itemView.findViewById(R.id.statusChip)
    private val destinationText: TextView = itemView.findViewById(R.id.destinationText)
    private val filesText: TextView = itemView.findViewById(R.id.filesText)
    private val createdText: TextView = itemView.findViewById(R.id.createdText)
    private val updatedText: TextView = itemView.findViewById(R.id.updatedText)
    private val errorText: TextView = itemView.findViewById(R.id.errorText)

    fun bind(entity: ImageProcessorAssemblyEntity) {
        assemblyIdText.text = itemView.context.getString(
            R.string.image_processor_assembly_id_format,
            entity.assemblyId.take(8)
        )

        val status = AssemblyStatus.fromValue(entity.status)
        statusChip.text = status?.toDisplayName() ?: itemView.context.getString(
            R.string.image_processor_assembly_status_unknown,
            entity.status
        )
        applyStatusChipStyle(status)

        destinationText.text = buildDestinationText(entity)
        filesText.text = itemView.context.getString(
            R.string.image_processor_assembly_files_format,
            entity.totalFiles,
            formatBytes(entity.bytesReceived)
        )
        createdText.text = itemView.context.getString(
            R.string.image_processor_assembly_created_format,
            formatDate(entity.createdAt)
        )
        updatedText.text = itemView.context.getString(
            R.string.image_processor_assembly_updated_format,
            DateUtils.getRelativeTimeSpanString(
                entity.lastUpdatedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
        )

        if (entity.errorMessage.isNullOrBlank()) {
            errorText.visibility = View.GONE
        } else {
            errorText.visibility = View.VISIBLE
            errorText.text = itemView.context.getString(
                R.string.image_processor_assembly_error_format,
                entity.errorMessage
            )
        }
    }

    private fun applyStatusChipStyle(status: AssemblyStatus?) {
        val context = itemView.context
        val (backgroundColor, textColor) = when (status) {
            AssemblyStatus.COMPLETED -> {
                ContextCompat.getColor(context, R.color.light_green) to
                    ContextCompat.getColor(context, R.color.dark_green)
            }

            AssemblyStatus.FAILED,
            AssemblyStatus.CANCELLED -> {
                ContextCompat.getColor(context, R.color.error_fill) to
                    ContextCompat.getColor(context, R.color.dark_red)
            }

            AssemblyStatus.RETRYING -> {
                val base = ContextCompat.getColor(context, R.color.secondary_yellow)
                ColorUtils.setAlphaComponent(base, 80) to base
            }

            AssemblyStatus.WAITING_FOR_CONNECTIVITY,
            AssemblyStatus.QUEUED,
            AssemblyStatus.PENDING -> {
                ContextCompat.getColor(context, R.color.team_member_tag) to
                    ContextCompat.getColor(context, R.color.team_member_tag_dark)
            }

            else -> {
                ContextCompat.getColor(context, R.color.light_purple) to
                    ContextCompat.getColor(context, R.color.main_purple)
            }
        }

        ViewCompat.setBackgroundTintList(
            statusChip,
            ColorStateList.valueOf(backgroundColor)
        )
        statusChip.setTextColor(textColor)
    }

    private fun buildDestinationText(entity: ImageProcessorAssemblyEntity): String {
        val context = itemView.context
        val target = when {
            !entity.entityType.isNullOrBlank() && entity.entityId != null -> {
                context.getString(
                    R.string.image_processor_assembly_target_entity,
                    formatLabel(entity.entityType),
                    entity.entityId
                )
            }

            entity.roomId != null -> {
                context.getString(
                    R.string.image_processor_assembly_target_room,
                    entity.roomId
                )
            }

            else -> context.getString(R.string.image_processor_assembly_target_project)
        }

        return context.getString(
            R.string.image_processor_assembly_destination_format,
            entity.projectId,
            target
        )
    }

    private fun formatLabel(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val cleaned = raw.replace('_', ' ').lowercase(Locale.getDefault())
        return cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = ((Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt())
            .coerceAtMost(units.lastIndex)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.getDefault(), "%.1f %s", value, units[digitGroups])
    }

    private fun formatDate(timestamp: Long): String {
        val dateFormat = DateFormat.getMediumDateFormat(itemView.context)
        val timeFormat = DateFormat.getTimeFormat(itemView.context)
        val date = Date(timestamp)
        return itemView.context.getString(
            R.string.image_processor_assembly_date_format,
            dateFormat.format(date),
            timeFormat.format(date)
        )
    }

    private fun AssemblyStatus.toDisplayName(): String =
        name.lowercase(Locale.getDefault())
            .replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}
