package com.example.rocketplan_android.ui.projects

import android.os.Bundle
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
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch
import android.util.Log

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

    private val albumsAdapter by lazy {
        AlbumsAdapter(
            onAlbumClick = { album ->
                Toast.makeText(requireContext(), "Album: ${album.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private val photoAdapter by lazy {
        RoomPhotoAdapter(
            onAddPhoto = {
                Toast.makeText(requireContext(), getString(R.string.add_photo), Toast.LENGTH_SHORT).show()
            },
            onPhotoSelected = { /* TODO: open photo detail */ }
        )
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
        photosRecyclerView.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3)
            adapter = photoAdapter
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
                                Log.d(TAG, "📸 Ready state: ${state.photos.size} photos, ${state.albums.size} albums")
                                renderState(state)
                            }
                        }
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
        loadingOverlay.isVisible = true
        roomTitle.text = ""
        noteSummary.text = ""
        photosRecyclerView.isVisible = false
        placeholderText.isVisible = false
    }

    private fun renderState(state: RoomDetailUiState.Ready) {
        Log.d(TAG, "🎛 renderState: photos=${state.photos.size}, albums=${state.albums.size}")
        loadingOverlay.isVisible = false
        roomTitle.text = state.header.title
        noteSummary.text = state.header.noteSummary
        noteCardSummary.text = state.header.noteSummary

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

        // Photos
        val items = listOf(RoomPhotoListItem.AddPhoto) + state.photos.map { RoomPhotoListItem.Photo(it) }
        Log.d(TAG, "🖼 submitting photos items: ${items.size} (incl AddPhoto)")
        photoAdapter.submitList(items)

        val hasPhotos = state.photos.isNotEmpty()
        photosRecyclerView.isVisible = hasPhotos && viewModel.selectedTab.value == RoomDetailTab.PHOTOS
        placeholderText.isVisible = !hasPhotos && viewModel.selectedTab.value == RoomDetailTab.PHOTOS
        placeholderText.text = if (hasPhotos) "" else getString(R.string.rocket_scan_empty_state)
        Log.d(TAG, "👁 visibility -> photosRV=${photosRecyclerView.isVisible}, placeholder=${placeholderText.isVisible}")
    }

    private fun applyTabState(tab: RoomDetailTab) {
        Log.d(TAG, "🧩 applyTabState: $tab, albums=${albumsAdapter.currentList.size}, photoItems=${photoAdapter.currentList.size}")
        when (tab) {
            RoomDetailTab.PHOTOS -> {
                albumsHeader.isVisible = albumsAdapter.currentList.isNotEmpty()
                albumsRecyclerView.isVisible = albumsAdapter.currentList.isNotEmpty()
                photosRecyclerView.isVisible = photoAdapter.currentList.size > 1
                placeholderText.isVisible = photoAdapter.currentList.size <= 1
                placeholderText.text = getString(R.string.rocket_scan_empty_state)
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
            }
        }
    }

    companion object {
        private const val TAG = "RoomDetailFrag"
    }
}
