package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.rocketplan_android.R
import com.example.rocketplan_android.ui.projects.addroom.RoomTypePickerMode
import com.example.rocketplan_android.ui.projects.ProjectRoomsAdapter.RoomStatMode
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class ProjectDetailFragment : Fragment() {

    private val args: ProjectDetailFragmentArgs by navArgs()

    private val viewModel: ProjectDetailViewModel by viewModels {
        ProjectDetailViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var headerTitle: TextView
    private lateinit var projectTitle: TextView
    private lateinit var projectCode: TextView
    private lateinit var noteSummary: TextView
    private lateinit var damageCountLabel: TextView
    private lateinit var noteCard: View
    private lateinit var addRoomCard: View
    private lateinit var addExteriorCard: View
    private lateinit var roomActionsProgressBar: ProgressBar
    private lateinit var albumsHeader: TextView
    private lateinit var albumsRecyclerView: RecyclerView
    private lateinit var roomsRecyclerView: RecyclerView
    private lateinit var roomsProgressBar: ProgressBar
    private lateinit var roomsPlaceholder: TextView
    private lateinit var tabPlaceholder: TextView
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var photosButton: MaterialButton
    private lateinit var damagesButton: MaterialButton
    private lateinit var sketchButton: MaterialButton
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private val albumsAdapter by lazy {
        AlbumsAdapter(
            onAlbumClick = { album ->
                Toast.makeText(requireContext(), "Album: ${album.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private val roomsAdapter by lazy {
        ProjectRoomsAdapter(
            onRoomClick = { room ->
                val action = ProjectDetailFragmentDirections
                    .actionProjectDetailFragmentToRoomDetailFragment(
                        projectId = args.projectId,
                        roomId = room.roomId,
                        startTab = when (viewModel.selectedTab.value) {
                            ProjectDetailTab.DAMAGES -> "damages"
                            else -> "photos"
                        }
                    )
                findNavController().navigate(action)
            }
        )
    }
    private var roomsSectionIsLoading = true
    private var roomCreationStatus: RoomCreationStatus = RoomCreationStatus.UNKNOWN
    private var isBackgroundSyncing: Boolean = false
    private var lastSubmittedRoomCount: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_project_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        configureRecycler()
        configureToggleGroup()
        bindListeners()
        observeViewModel()
    }

    private fun bindViews(root: View) {
        headerTitle = root.findViewById(R.id.headerTitle)
        projectTitle = root.findViewById(R.id.projectTitle)
        projectCode = root.findViewById(R.id.projectCode)
        noteSummary = root.findViewById(R.id.noteSummary)
        damageCountLabel = root.findViewById(R.id.damageCountLabel)
        noteCard = root.findViewById(R.id.noteCard)
        addRoomCard = root.findViewById(R.id.addRoomCard)
        addExteriorCard = root.findViewById(R.id.addExteriorCard)
        roomActionsProgressBar = root.findViewById(R.id.roomActionsProgressBar)
        albumsHeader = root.findViewById(R.id.albumsHeader)
        albumsRecyclerView = root.findViewById(R.id.albumsRecyclerView)
        roomsRecyclerView = root.findViewById(R.id.roomsRecyclerView)
        roomsProgressBar = root.findViewById(R.id.roomsProgressBar)
        roomsPlaceholder = root.findViewById(R.id.roomsPlaceholder)
        tabPlaceholder = root.findViewById(R.id.tabPlaceholder)
        toggleGroup = root.findViewById(R.id.tabToggleGroup)
        photosButton = root.findViewById(R.id.photosTabButton)
        damagesButton = root.findViewById(R.id.damagesTabButton)
        sketchButton = root.findViewById(R.id.sketchTabButton)
        swipeRefreshLayout = root.findViewById(R.id.projectDetailSwipeRefresh)

        headerTitle.text = getString(R.string.project_home)
    }

    private fun configureRecycler() {
        albumsRecyclerView.configureForAlbums(albumsAdapter)
        roomsRecyclerView.configureForProjectRooms(roomsAdapter)
    }

    private fun configureToggleGroup() {
        toggleGroup.check(R.id.photosTabButton)
        updateToggleStyles(ProjectDetailTab.PHOTOS)
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val tab = when (checkedId) {
                R.id.photosTabButton -> ProjectDetailTab.PHOTOS
                R.id.damagesTabButton -> ProjectDetailTab.DAMAGES
                else -> ProjectDetailTab.SKETCH
            }
            viewModel.selectTab(tab)
            updateToggleStyles(tab)
        }
    }

    private fun updateToggleStyles(activeTab: ProjectDetailTab) {
        val selectedBackground =
            ContextCompat.getColorStateList(requireContext(), R.color.main_purple)
        val unselectedBackground =
            ContextCompat.getColorStateList(requireContext(), android.R.color.white)
        val selectedText = ContextCompat.getColor(requireContext(), android.R.color.white)
        val unselectedText = ContextCompat.getColor(requireContext(), R.color.main_purple)

        listOf(
            photosButton to ProjectDetailTab.PHOTOS,
            damagesButton to ProjectDetailTab.DAMAGES,
            sketchButton to ProjectDetailTab.SKETCH
        ).forEach { (button, tab) ->
            val isSelected = tab == activeTab
            button.backgroundTintList = if (isSelected) selectedBackground else unselectedBackground
            button.strokeColor =
                ContextCompat.getColorStateList(requireContext(), R.color.main_purple)
            button.setTextColor(if (isSelected) selectedText else unselectedText)
        }
    }

    private fun bindListeners() {
        swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshRoomsAndThumbnails()
        }
        noteCard.setOnClickListener {
            val action = ProjectDetailFragmentDirections
                .actionProjectDetailFragmentToProjectNotesFragment(args.projectId)
            findNavController().navigate(action)
        }
        addRoomCard.setOnClickListener {
            handleAddRoomClick(RoomTypePickerMode.ROOM)
        }
        addExteriorCard.setOnClickListener {
            handleAddRoomClick(RoomTypePickerMode.EXTERIOR)
        }
    }

    private fun navigateToRoomTypePicker(mode: RoomTypePickerMode) {
        val action = ProjectDetailFragmentDirections
            .actionProjectDetailFragmentToRoomTypePickerFragment(
                projectId = args.projectId,
                mode = mode.name
            )
        findNavController().navigate(action)
    }

    fun promptDeleteProject() {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_project_title)
            .setMessage(R.string.delete_project_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                confirmDeleteProject()
            }
            .show()
    }

    private fun confirmDeleteProject() {
        viewLifecycleOwner.lifecycleScope.launch {
            val success = viewModel.deleteProject()
            if (success) {
                Toast.makeText(requireContext(), R.string.project_deleted, Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } else {
                Toast.makeText(requireContext(), R.string.project_delete_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        when (state) {
                            ProjectDetailUiState.Loading -> showLoadingState()
                            is ProjectDetailUiState.Ready -> renderState(state)
                        }
                    }
                }
                launch {
                    viewModel.selectedTab.collect { tab ->
                        applyTabVisibility(tab)
                        updateToggleStyles(tab)
                    }
                }
                launch {
                    viewModel.isRefreshing.collect { refreshing ->
                        swipeRefreshLayout.isRefreshing = refreshing
                    }
                }
            }
        }
    }

    private fun showLoadingState() {
        roomsSectionIsLoading = true
        roomCreationStatus = RoomCreationStatus.UNKNOWN
        isBackgroundSyncing = false
        lastSubmittedRoomCount = 0
        tabPlaceholder.isVisible = false
        updateRoomCreationUi()
        updateRoomsSectionVisibility()
    }

    private fun renderState(state: ProjectDetailUiState.Ready) {
        roomCreationStatus = state.roomCreationStatus
        isBackgroundSyncing = state.isBackgroundSyncing
        projectTitle.text = state.header.projectTitle
        projectCode.isVisible = state.header.projectCode.isNotBlank()
        projectCode.text = state.header.projectCode
        noteSummary.text = state.header.noteSummary
        val damageCount = state.header.damageCountTotal
        damageCountLabel.text = resources.getQuantityString(R.plurals.damage_count, damageCount, damageCount)
        damageCountLabel.isVisible = viewModel.selectedTab.value == ProjectDetailTab.DAMAGES

        // Albums
        Log.d("ProjectDetailFrag", "📚 Submitting ${state.albums.size} albums to albumsAdapter")
        albumsAdapter.submitList(state.albums)
        albumsHeader.isVisible = state.albums.isNotEmpty() && viewModel.selectedTab.value == ProjectDetailTab.PHOTOS
        albumsRecyclerView.isVisible = state.albums.isNotEmpty() && viewModel.selectedTab.value == ProjectDetailTab.PHOTOS

        // Rooms
        val flattenedItems = state.levelSections.flatMap { section ->
            if (section.rooms.isEmpty()) {
                emptyList()
            } else {
                listOf(RoomListItem.Header(section.levelName)) +
                    section.rooms.map { RoomListItem.Room(it) }
            }
        }
        Log.d("ProjectDetailFrag", "🏠 Submitting ${flattenedItems.size} room items to roomsAdapter (${state.levelSections.size} sections)")
        lastSubmittedRoomCount = flattenedItems.size
        roomsAdapter.submitList(flattenedItems)
        roomsSectionIsLoading = false
        updateRoomCreationUi()
        updateRoomsSectionVisibility()
    }

    private fun applyTabVisibility(tab: ProjectDetailTab) {
        when (tab) {
            ProjectDetailTab.PHOTOS -> {
                roomsAdapter.statMode = RoomStatMode.PHOTOS
                tabPlaceholder.isVisible = false
                albumsHeader.isVisible = albumsAdapter.currentList.isNotEmpty()
                albumsRecyclerView.isVisible = albumsAdapter.currentList.isNotEmpty()
                damageCountLabel.isVisible = false
                updateRoomsSectionVisibility(ProjectDetailTab.PHOTOS)
            }
            ProjectDetailTab.DAMAGES -> {
                roomsAdapter.statMode = RoomStatMode.DAMAGES
                albumsHeader.isVisible = false
                albumsRecyclerView.isVisible = false
                damageCountLabel.isVisible = true
                updateRoomsSectionVisibility(ProjectDetailTab.DAMAGES)
                tabPlaceholder.isVisible = false
            }
            ProjectDetailTab.SKETCH -> {
                roomsAdapter.statMode = RoomStatMode.PHOTOS
                albumsHeader.isVisible = false
                albumsRecyclerView.isVisible = false
                damageCountLabel.isVisible = false
                updateRoomsSectionVisibility(ProjectDetailTab.SKETCH)
                tabPlaceholder.isVisible = true
                tabPlaceholder.text = getString(R.string.sketch_coming_soon)
            }
        }
    }

    private fun updateRoomsSectionVisibility(activeTab: ProjectDetailTab = viewModel.selectedTab.value) {
        if (activeTab == ProjectDetailTab.SKETCH) {
            roomsProgressBar.isVisible = false
            roomsRecyclerView.isVisible = false
            roomsPlaceholder.isVisible = false
            return
        }

        val hasRooms = lastSubmittedRoomCount > 0
        val shouldShowRoomsSpinner =
            roomsSectionIsLoading || (isBackgroundSyncing && !hasRooms)

        when {
            shouldShowRoomsSpinner -> {
                roomsProgressBar.isVisible = true
                roomsRecyclerView.isVisible = false
                roomsPlaceholder.isVisible = false
            }
            !hasRooms -> {
                roomsProgressBar.isVisible = false
                roomsRecyclerView.isVisible = false
                roomsPlaceholder.isVisible = true
            }
            else -> {
                roomsProgressBar.isVisible = false
                roomsRecyclerView.isVisible = true
                roomsPlaceholder.isVisible = false
            }
        }
    }

    private fun updateRoomCreationUi() {
        // Keep the add-room row static; room cards already show sync spinners.
        roomActionsProgressBar.isVisible = false
        val isBlocked = roomCreationStatus == RoomCreationStatus.MISSING_PROPERTY ||
            roomCreationStatus == RoomCreationStatus.UNSYNCED_PROPERTY ||
            roomCreationStatus == RoomCreationStatus.SYNCING
        addRoomCard.isEnabled = !isBlocked
        addRoomCard.isClickable = !isBlocked
        addExteriorCard.isEnabled = !isBlocked
        addExteriorCard.isClickable = !isBlocked
        val alpha = if (isBlocked) 0.6f else 1f
        addRoomCard.alpha = alpha
        addExteriorCard.alpha = alpha
    }

    private fun handleAddRoomClick(mode: RoomTypePickerMode) {
        when (roomCreationStatus) {
            RoomCreationStatus.AVAILABLE -> navigateToRoomTypePicker(mode)
            RoomCreationStatus.MISSING_PROPERTY ->
                showRoomCreationWarning(R.string.room_type_error_missing_property)
            RoomCreationStatus.UNSYNCED_PROPERTY ->
                showRoomCreationWarning(R.string.room_creation_property_sync_pending)
            RoomCreationStatus.SYNCING ->
                showRoomCreationWarning(R.string.room_creation_sync_in_progress)
            RoomCreationStatus.UNKNOWN ->
                showRoomCreationWarning(R.string.room_creation_loading_message)
        }
    }

    private fun showRoomCreationWarning(@StringRes messageRes: Int) {
        if (!isAdded) return
        Toast.makeText(requireContext(), getString(messageRes), Toast.LENGTH_LONG).show()
    }
}
