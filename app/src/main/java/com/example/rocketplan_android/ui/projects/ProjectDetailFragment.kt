package com.example.rocketplan_android.ui.projects

import android.os.Bundle
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
import kotlinx.coroutines.launch

class ProjectDetailFragment : Fragment() {

    private val args: ProjectDetailFragmentArgs by navArgs()

    private val viewModel: ProjectDetailViewModel by viewModels {
        ProjectDetailViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var backButton: ImageButton
    private lateinit var menuButton: ImageButton
    private lateinit var editButton: ImageButton
    private lateinit var headerTitle: TextView
    private lateinit var projectTitle: TextView
    private lateinit var projectCode: TextView
    private lateinit var noteSummary: TextView
    private lateinit var addRoomCard: View
    private lateinit var addExteriorCard: View
    private lateinit var roomsRecyclerView: RecyclerView
    private lateinit var roomsPlaceholder: TextView
    private lateinit var tabPlaceholder: TextView
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var photosButton: MaterialButton
    private lateinit var damagesButton: MaterialButton
    private lateinit var sketchButton: MaterialButton

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
        menuButton = root.findViewById(R.id.menuButton)
        editButton = root.findViewById(R.id.editProjectButton)
        headerTitle = root.findViewById(R.id.headerTitle)
        projectTitle = root.findViewById(R.id.projectTitle)
        projectCode = root.findViewById(R.id.projectCode)
        noteSummary = root.findViewById(R.id.noteSummary)
        addRoomCard = root.findViewById(R.id.addRoomCard)
        addExteriorCard = root.findViewById(R.id.addExteriorCard)
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
        menuButton.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.menu), Toast.LENGTH_SHORT).show()
        }
        editButton.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.edit_project), Toast.LENGTH_SHORT).show()
        }
        addRoomCard.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.add_room), Toast.LENGTH_SHORT).show()
        }
        addExteriorCard.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.add_exterior_space), Toast.LENGTH_SHORT).show()
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

        val flattenedItems = state.levelSections.flatMap { section ->
            if (section.rooms.isEmpty()) {
                emptyList()
            } else {
                listOf(RoomListItem.Header(section.levelName)) +
                    section.rooms.map { RoomListItem.Room(it) }
            }
        }
        roomsAdapter.submitList(flattenedItems)
        roomsPlaceholder.isVisible = flattenedItems.isEmpty() && viewModel.selectedTab.value == ProjectDetailTab.PHOTOS
        roomsRecyclerView.isVisible = flattenedItems.isNotEmpty() && viewModel.selectedTab.value == ProjectDetailTab.PHOTOS
    }

    private fun applyTabVisibility(tab: ProjectDetailTab) {
        when (tab) {
            ProjectDetailTab.PHOTOS -> {
                tabPlaceholder.isVisible = false
                roomsRecyclerView.isVisible = roomsAdapter.currentList.isNotEmpty()
                roomsPlaceholder.isVisible = roomsAdapter.currentList.isEmpty()
            }
            ProjectDetailTab.DAMAGES -> {
                roomsRecyclerView.isVisible = false
                roomsPlaceholder.isVisible = false
                tabPlaceholder.isVisible = true
                tabPlaceholder.text = getString(R.string.damages) + " coming soon"
            }
            ProjectDetailTab.SKETCH -> {
                roomsRecyclerView.isVisible = false
                roomsPlaceholder.isVisible = false
                tabPlaceholder.isVisible = true
                tabPlaceholder.text = getString(R.string.sketch) + " coming soon"
            }
        }
    }
}
