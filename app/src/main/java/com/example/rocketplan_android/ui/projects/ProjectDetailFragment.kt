package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class ProjectDetailFragment : Fragment() {

    private val args: ProjectDetailFragmentArgs by navArgs()

    private val viewModel: ProjectDetailViewModel by viewModels {
        ProjectDetailViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var backButton: ImageButton
    private lateinit var editButton: ImageButton
    private lateinit var headerTitle: TextView
    private lateinit var projectTitle: TextView
    private lateinit var projectCode: TextView
    private lateinit var noteSummary: TextView
    private lateinit var addRoomCard: View
    private lateinit var addExteriorCard: View
    private lateinit var albumsHeader: TextView
    private lateinit var albumsRecyclerView: RecyclerView
    private lateinit var roomsRecyclerView: RecyclerView
    private lateinit var roomsPlaceholder: TextView
    private lateinit var tabPlaceholder: TextView
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var photosButton: MaterialButton
    private lateinit var damagesButton: MaterialButton
    private lateinit var sketchButton: MaterialButton

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
                    .actionProjectDetailFragmentToRoomDetailFragment(args.projectId, room.roomId)
                findNavController().navigate(action)
            }
        )
    }

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
        backButton = root.findViewById(R.id.backButton)
        editButton = root.findViewById(R.id.editProjectButton)
        headerTitle = root.findViewById(R.id.headerTitle)
        projectTitle = root.findViewById(R.id.projectTitle)
        projectCode = root.findViewById(R.id.projectCode)
        noteSummary = root.findViewById(R.id.noteSummary)
        addRoomCard = root.findViewById(R.id.addRoomCard)
        addExteriorCard = root.findViewById(R.id.addExteriorCard)
        albumsHeader = root.findViewById(R.id.albumsHeader)
        albumsRecyclerView = root.findViewById(R.id.albumsRecyclerView)
        roomsRecyclerView = root.findViewById(R.id.roomsRecyclerView)
        roomsPlaceholder = root.findViewById(R.id.roomsPlaceholder)
        tabPlaceholder = root.findViewById(R.id.tabPlaceholder)
        toggleGroup = root.findViewById(R.id.tabToggleGroup)
        photosButton = root.findViewById(R.id.photosTabButton)
        damagesButton = root.findViewById(R.id.damagesTabButton)
        sketchButton = root.findViewById(R.id.sketchTabButton)

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
        backButton.setOnClickListener { findNavController().navigateUp() }
        editButton.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.edit_project), Toast.LENGTH_SHORT).show()
        }
        addRoomCard.setOnClickListener {
            navigateToRocketScan()
        }
        addExteriorCard.setOnClickListener {
            navigateToRocketScan()
        }
    }

    private fun navigateToRocketScan() {
        val action = ProjectDetailFragmentDirections
            .actionProjectDetailFragmentToRocketScanFragment(args.projectId)
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
            }
        }
    }

    private fun showLoadingState() {
        roomsPlaceholder.isVisible = false
        tabPlaceholder.isVisible = false
        roomsRecyclerView.isVisible = false
    }

    private fun renderState(state: ProjectDetailUiState.Ready) {
        projectTitle.text = state.header.projectTitle
        projectCode.isVisible = state.header.projectCode.isNotBlank()
        projectCode.text = state.header.projectCode
        noteSummary.text = state.header.noteSummary

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
        roomsAdapter.submitList(flattenedItems)
        roomsPlaceholder.isVisible = flattenedItems.isEmpty() && viewModel.selectedTab.value == ProjectDetailTab.PHOTOS
        roomsRecyclerView.isVisible = flattenedItems.isNotEmpty() && viewModel.selectedTab.value == ProjectDetailTab.PHOTOS
    }

    private fun applyTabVisibility(tab: ProjectDetailTab) {
        when (tab) {
            ProjectDetailTab.PHOTOS -> {
                tabPlaceholder.isVisible = false
                albumsHeader.isVisible = albumsAdapter.currentList.isNotEmpty()
                albumsRecyclerView.isVisible = albumsAdapter.currentList.isNotEmpty()
                roomsRecyclerView.isVisible = roomsAdapter.currentList.isNotEmpty()
                roomsPlaceholder.isVisible = roomsAdapter.currentList.isEmpty()
            }
            ProjectDetailTab.DAMAGES -> {
                albumsHeader.isVisible = false
                albumsRecyclerView.isVisible = false
                roomsRecyclerView.isVisible = false
                roomsPlaceholder.isVisible = false
                tabPlaceholder.isVisible = true
                tabPlaceholder.text = getString(R.string.damages) + " coming soon"
            }
            ProjectDetailTab.SKETCH -> {
                albumsHeader.isVisible = false
                albumsRecyclerView.isVisible = false
                roomsRecyclerView.isVisible = false
                roomsPlaceholder.isVisible = false
                tabPlaceholder.isVisible = true
                tabPlaceholder.text = getString(R.string.sketch) + " coming soon"
            }
        }
    }
}
