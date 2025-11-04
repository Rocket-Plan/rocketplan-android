package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.paging.LoadState
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

class RoomDetailFragment : Fragment() {

    private val args: RoomDetailFragmentArgs by navArgs()

    private val viewModel: RoomDetailViewModel by viewModels {
        RoomDetailViewModel.provideFactory(requireActivity().application, args.projectId, args.roomId)
    }

    private lateinit var backButton: ImageButton
    private lateinit var menuButton: ImageButton
    private lateinit var roomIcon: ImageView
    private lateinit var roomTitle: TextView
    private lateinit var noteSummary: TextView
    private lateinit var tabToggleGroup: MaterialButtonToggleGroup
    private lateinit var photosTabButton: MaterialButton
    private lateinit var damagesTabButton: MaterialButton
    private lateinit var noteCard: View
    private lateinit var noteCardLabel: TextView
    private lateinit var noteCardSummary: TextView
    private lateinit var gridSectionTitle: TextView
    private lateinit var albumsHeader: TextView
    private lateinit var albumsRecyclerView: RecyclerView
    private lateinit var filterChipGroup: ChipGroup
    private lateinit var addPhotoChip: Chip
    private lateinit var damageAssessmentChip: Chip
    private lateinit var photosRecyclerView: RecyclerView
    private lateinit var placeholderText: TextView
    private lateinit var loadingOverlay: View
    private lateinit var photoConcatAdapter: ConcatAdapter
    private var latestPhotoCount: Int = 0

    private val albumsAdapter by lazy {
        AlbumsAdapter(
            onAlbumClick = { album ->
                Toast.makeText(requireContext(), "Album: ${album.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private val addPhotoAdapter by lazy {
        RoomPhotoAddAdapter {
            Toast.makeText(requireContext(), getString(R.string.add_photo), Toast.LENGTH_SHORT).show()
        }
    }

    private val photoAdapter by lazy {
        RoomPhotoPagingAdapter(
            onPhotoSelected = { openPhotoViewer(it) }
        )
    }

    private val photoLoadStateAdapter by lazy {
        RoomPhotoLoadStateAdapter { photoAdapter.retry() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_room_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "🧭 onViewCreated(projectId=${args.projectId}, roomId=${args.roomId})")
        bindViews(view)
        configureRecycler()
        configureToggleGroup()
        bindListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "▶️ onResume -> ensureRoomPhotosFresh()")
        viewModel.ensureRoomPhotosFresh()
    }

    private fun bindViews(root: View) {
        backButton = root.findViewById(R.id.backButton)
        menuButton = root.findViewById(R.id.menuButton)
        roomIcon = root.findViewById(R.id.roomIcon)
        roomTitle = root.findViewById(R.id.roomTitle)
        noteSummary = root.findViewById(R.id.noteSummary)
        tabToggleGroup = root.findViewById(R.id.roomTabGroup)
        photosTabButton = root.findViewById(R.id.roomPhotosTabButton)
        damagesTabButton = root.findViewById(R.id.roomDamagesTabButton)
        noteCard = root.findViewById(R.id.roomNoteCard)
        noteCardLabel = root.findViewById(R.id.roomNoteLabel)
        noteCardSummary = root.findViewById(R.id.roomNoteSummary)
        albumsHeader = root.findViewById(R.id.roomAlbumsHeader)
        albumsRecyclerView = root.findViewById(R.id.roomAlbumsRecyclerView)
        gridSectionTitle = root.findViewById(R.id.gridSectionTitle)
        filterChipGroup = root.findViewById(R.id.photoFilterChips)
        addPhotoChip = root.findViewById(R.id.addPhotoChip)
        damageAssessmentChip = root.findViewById(R.id.damageAssessmentChip)
        photosRecyclerView = root.findViewById(R.id.roomPhotosRecyclerView)
        placeholderText = root.findViewById(R.id.photosPlaceholder)
        loadingOverlay = root.findViewById(R.id.loadingOverlay)

        tabToggleGroup.check(R.id.roomPhotosTabButton)
        addPhotoChip.isChecked = true
        gridSectionTitle.text = getString(R.string.damage_assessment)
        noteCardLabel.text = getString(R.string.add_note)
        backButton.contentDescription = getString(R.string.all_locations)
    }

    private fun configureRecycler() {
        albumsRecyclerView.configureForAlbums(albumsAdapter)

        photoConcatAdapter = ConcatAdapter(addPhotoAdapter, photoAdapter, photoLoadStateAdapter)

        val gridLayoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3).apply {
            spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val isHeader = position == 0
                    val loadStateCount = photoLoadStateAdapter.itemCount
                    val isFooter = loadStateCount > 0 &&
                        position >= photoConcatAdapter.itemCount - loadStateCount
                    return if (isHeader || isFooter) spanCount else 1
                }
            }
        }

        photosRecyclerView.apply {
            layoutManager = gridLayoutManager
            adapter = photoConcatAdapter
            setHasFixedSize(true)
            (itemAnimator as? SimpleItemAnimator)?.apply {
                supportsChangeAnimations = false
                changeDuration = 0
            }
        }
        photosRecyclerView.addItemDecoration(
            SimpleGridSpacingDecoration(
                spanCount = 3,
                spacing = resources.getDimensionPixelSize(R.dimen.room_grid_spacing)
            )
        )
    }

    private fun configureToggleGroup() {
        updateToggleStyles(RoomDetailTab.PHOTOS)
        tabToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val tab = when (checkedId) {
                R.id.roomPhotosTabButton -> RoomDetailTab.PHOTOS
                else -> RoomDetailTab.DAMAGES
            }
            viewModel.selectTab(tab)
            updateToggleStyles(tab)
        }
    }

    private fun updateToggleStyles(active: RoomDetailTab) {
        val selectedBackground =
            ContextCompat.getColorStateList(requireContext(), R.color.main_purple)
        val unselectedBackground =
            ContextCompat.getColorStateList(requireContext(), android.R.color.white)
        val selectedText = ContextCompat.getColor(requireContext(), android.R.color.white)
        val unselectedText = ContextCompat.getColor(requireContext(), R.color.main_purple)

        listOf(
            photosTabButton to RoomDetailTab.PHOTOS,
            damagesTabButton to RoomDetailTab.DAMAGES
        ).forEach { (button, tab) ->
            val isSelected = tab == active
            button.backgroundTintList = if (isSelected) selectedBackground else unselectedBackground
            button.strokeColor = ContextCompat.getColorStateList(requireContext(), R.color.main_purple)
            button.setTextColor(if (isSelected) selectedText else unselectedText)
        }
    }

    private fun bindListeners() {
        backButton.setOnClickListener { findNavController().navigateUp() }
        menuButton.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.menu), Toast.LENGTH_SHORT).show()
        }
        addPhotoChip.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.add_photo), Toast.LENGTH_SHORT).show()
        }
        damageAssessmentChip.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.damage_assessment), Toast.LENGTH_SHORT).show()
        }
        noteCard.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.add_note), Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        Log.d(TAG, "🎨 State received: $state")
                        when (state) {
                            RoomDetailUiState.Loading -> showLoading()
                            is RoomDetailUiState.Ready -> {
                                Log.d(TAG, "📸 Ready state: ${state.photoCount} photos, ${state.albums.size} albums")
                                renderState(state)
                            }
                        }
                    }
                }
                launch {
                    viewModel.photoPagingData.collectLatest { pagingData ->
                        Log.d(TAG, "📥 Received new PagingData, submitting to adapter")
                        photoAdapter.submitData(viewLifecycleOwner.lifecycle, pagingData)
                    }
                }
                launch {
                    photoAdapter.loadStateFlow.collectLatest { loadStates ->
                        Log.d(TAG, "📊 LoadState: refresh=${loadStates.refresh}, append=${loadStates.append}, itemCount=${photoAdapter.itemCount}")
                        photoLoadStateAdapter.loadState = loadStates.append
                        updatePhotoVisibility(loadStates.refresh)
                    }
                }
                launch {
                    viewModel.selectedTab.collect { tab ->
                        applyTabState(tab)
                        updateToggleStyles(tab)
                    }
                }
            }
        }
    }

    private fun showLoading() {
        latestPhotoCount = 0
        loadingOverlay.isVisible = true
        roomTitle.text = ""
        noteSummary.text = ""
        photosRecyclerView.isVisible = false
        placeholderText.isVisible = false
    }

    private fun renderState(state: RoomDetailUiState.Ready) {
        Log.d(TAG, "🎛 renderState: photoCount=${state.photoCount}, albums=${state.albums.size}")
        loadingOverlay.isVisible = false
        roomTitle.text = state.header.title
        noteSummary.text = state.header.noteSummary
        noteCardSummary.text = state.header.noteSummary
        latestPhotoCount = state.photoCount

        // Albums - convert RoomAlbumItem to AlbumSection
        val albumSections = state.albums.map { albumItem ->
            AlbumSection(
                albumId = albumItem.id,
                name = albumItem.name,
                photoCount = albumItem.photoCount,
                thumbnailUrl = albumItem.thumbnailUrl
            )
        }
        Log.d(TAG, "📚 submitting albums: ${albumSections.size}")
        albumsAdapter.submitList(albumSections)
        albumsHeader.isVisible = albumSections.isNotEmpty() && viewModel.selectedTab.value == RoomDetailTab.PHOTOS
        albumsRecyclerView.isVisible = albumSections.isNotEmpty() && viewModel.selectedTab.value == RoomDetailTab.PHOTOS

        // Photos (visibility handled via load state listener)
        updatePhotoVisibility()
    }

    private fun applyTabState(tab: RoomDetailTab) {
        Log.d(TAG, "🧩 applyTabState: $tab, albums=${albumsAdapter.currentList.size}, photoCount=$latestPhotoCount")
        when (tab) {
            RoomDetailTab.PHOTOS -> {
                albumsHeader.isVisible = albumsAdapter.currentList.isNotEmpty()
                albumsRecyclerView.isVisible = albumsAdapter.currentList.isNotEmpty()
                updatePhotoVisibility()
                filterChipGroup.isVisible = true
                gridSectionTitle.isVisible = true
            }
            RoomDetailTab.DAMAGES -> {
                albumsHeader.isVisible = false
                albumsRecyclerView.isVisible = false
                photosRecyclerView.isVisible = false
                placeholderText.isVisible = true
                placeholderText.text = getString(R.string.damages) + " coming soon"
                filterChipGroup.isVisible = false
                gridSectionTitle.isVisible = false
                loadingOverlay.isVisible = false
            }
        }
    }

    private fun openPhotoViewer(photo: RoomPhotoItem) {
        val action = RoomDetailFragmentDirections
            .actionRoomDetailFragmentToPhotoViewerFragment(photo.id)
        findNavController().navigate(action)
    }

    private fun updatePhotoVisibility(loadState: LoadState? = null) {
        if (viewModel.selectedTab.value != RoomDetailTab.PHOTOS) {
            photosRecyclerView.isVisible = false
            placeholderText.isVisible = false
            return
        }

        val hasPhotos = latestPhotoCount > 0 || photoAdapter.itemCount > 0
        val isLoading = loadState is LoadState.Loading && !hasPhotos
        loadingOverlay.isVisible = isLoading

        val showPlaceholder = !hasPhotos && loadState !is LoadState.Loading

        Log.d(TAG, "👁 updatePhotoVisibility: latestPhotoCount=$latestPhotoCount, adapterItemCount=${photoAdapter.itemCount}, loadState=$loadState, hasPhotos=$hasPhotos, showPlaceholder=$showPlaceholder")

        photosRecyclerView.isVisible = hasPhotos
        placeholderText.isVisible = showPlaceholder
        placeholderText.text = if (showPlaceholder) getString(R.string.rocket_scan_empty_state) else ""
    }

    companion object {
        private const val TAG = "RoomDetailFrag"
    }
}
