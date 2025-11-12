package com.example.rocketplan_android.ui.rocketscan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentRocketScanBinding
import com.example.rocketplan_android.ui.projects.ProjectRoomsAdapter
import com.example.rocketplan_android.ui.projects.configureForProjectRooms
import kotlinx.coroutines.launch

class RocketScanFragment : Fragment() {

    private var _binding: FragmentRocketScanBinding? = null
    private val binding get() = _binding!!

    private val args: RocketScanFragmentArgs by navArgs()
    private val viewModel: RocketScanViewModel by viewModels()

    private lateinit var roomsAdapter: ProjectRoomsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRocketScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        observeUiState()
        viewModel.loadProject(args.projectId)
    }

    override fun onResume() {
        super.onResume()
        // Ensure project sync runs so rooms populate immediately
        viewModel.resumeBackgroundSync()
    }

    override fun onStop() {
        super.onStop()
        // Let other screens take over syncing when user leaves RocketScan
        viewModel.pauseBackgroundSync()
    }

    private fun setupRecyclerView() {
        roomsAdapter = ProjectRoomsAdapter { room ->
            val action = RocketScanFragmentDirections
                .actionRocketScanFragmentToRoomDetailFragment(projectId = args.projectId, roomId = room.roomId)
            findNavController().navigate(action)
        }
        binding.roomsRecyclerView.configureForProjectRooms(roomsAdapter)
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is RocketScanUiState.Loading -> renderLoading()
                    is RocketScanUiState.Empty -> renderEmpty()
                    is RocketScanUiState.Content -> renderContent(state)
                    is RocketScanUiState.Error -> renderError(state)
                }
            }
        }
    }

    private fun renderLoading() {
        binding.progressBar.isVisible = true
        binding.emptyView.isVisible = false
        binding.roomsRecyclerView.isVisible = false
    }

    private fun renderEmpty() {
        binding.progressBar.isVisible = false
        binding.roomsRecyclerView.isVisible = false
        binding.emptyView.isVisible = true
        binding.emptyView.setText(R.string.rocket_scan_empty_state)
        roomsAdapter.submitList(emptyList())
    }

    private fun renderContent(content: RocketScanUiState.Content) {
        binding.progressBar.isVisible = false
        binding.emptyView.isVisible = false
        binding.roomsRecyclerView.isVisible = true
        roomsAdapter.submitList(content.items)
    }

    private fun renderError(error: RocketScanUiState.Error) {
        binding.progressBar.isVisible = false
        binding.roomsRecyclerView.isVisible = false
        binding.emptyView.isVisible = true
        binding.emptyView.text = error.message
    }

    override fun onDestroyView() {
        binding.roomsRecyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
