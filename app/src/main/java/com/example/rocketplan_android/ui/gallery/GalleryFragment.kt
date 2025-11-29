package com.example.rocketplan_android.ui.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.LibraryMediaItem
import com.example.rocketplan_android.data.model.LibraryMediaType
import com.example.rocketplan_android.databinding.FragmentGalleryBinding

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var mediaAdapter: LibraryMediaAdapter
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private val mediaPermissions: Array<String> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                val granted = results.values.all { it }
                if (granted) {
                    showPermissionPrompt(false)
                    viewModel.loadLibraryMedia()
                } else {
                    showPermissionPrompt(true)
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecycler()
        setupListeners()
        observeViewModel()

        if (hasMediaPermissions()) {
            showPermissionPrompt(false)
            viewModel.loadLibraryMedia()
        } else {
            showPermissionPrompt(true)
        }
    }

    private fun setupRecycler() {
        mediaAdapter = LibraryMediaAdapter()
        binding.mediaRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = mediaAdapter
        }
    }

    private fun setupListeners() {
        binding.requestPermissionButton.setOnClickListener {
            requestMediaPermissions()
        }
        binding.retryButton.setOnClickListener {
            if (hasMediaPermissions()) {
                viewModel.loadLibraryMedia()
            } else {
                requestMediaPermissions()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            binding.loadingIndicator.isVisible = state.isLoading
            binding.errorGroup.isVisible = state.errorMessage != null
            binding.errorText.text = state.errorMessage

            val hasItems = state.items.isNotEmpty()
            val canShowContent = !binding.permissionMessage.isVisible
            binding.emptyState.isVisible = canShowContent && !state.isLoading && !hasItems && state.errorMessage == null
            binding.mediaRecycler.isVisible = hasItems
            binding.filterHint.isVisible = canShowContent && state.errorMessage == null

            mediaAdapter.submitList(state.items)
        }
    }

    private fun hasMediaPermissions(): Boolean =
        mediaPermissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestMediaPermissions() {
        permissionLauncher.launch(mediaPermissions)
    }

    private fun showPermissionPrompt(show: Boolean) {
        binding.permissionMessage.isVisible = show
        binding.requestPermissionButton.isVisible = show
        if (show) {
            binding.emptyState.isVisible = false
            binding.mediaRecycler.isVisible = false
            binding.filterHint.isVisible = false
            binding.errorGroup.isVisible = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class LibraryMediaAdapter :
    ListAdapter<LibraryMediaItem, LibraryMediaViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryMediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_library_media, parent, false)
        return LibraryMediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: LibraryMediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LibraryMediaItem>() {
            override fun areItemsTheSame(oldItem: LibraryMediaItem, newItem: LibraryMediaItem): Boolean =
                oldItem.uri == newItem.uri

            override fun areContentsTheSame(oldItem: LibraryMediaItem, newItem: LibraryMediaItem): Boolean =
                oldItem == newItem
        }
    }
}

private class LibraryMediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val thumbnail: ImageView = view.findViewById(R.id.mediaThumbnail)
    private val title: TextView = view.findViewById(R.id.mediaTitle)
    private val subtitle: TextView = view.findViewById(R.id.mediaSubtitle)
    private val typeBadge: TextView = view.findViewById(R.id.mediaTypeBadge)

    fun bind(item: LibraryMediaItem) {
        val context = itemView.context
        title.text = item.displayName.ifBlank { context.getString(R.string.library_unknown_file) }
        subtitle.text = DateUtils.getRelativeTimeSpanString(
            item.dateAddedMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )
        typeBadge.text = when (item.type) {
            LibraryMediaType.IMAGE -> context.getString(R.string.media_type_image)
            LibraryMediaType.VIDEO -> context.getString(R.string.media_type_video)
        }
        thumbnail.load(item.uri) {
            crossfade(true)
            error(R.drawable.ic_photo)
            placeholder(R.drawable.ic_photo)
        }
    }
}
