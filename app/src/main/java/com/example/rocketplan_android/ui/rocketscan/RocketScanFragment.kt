package com.example.rocketplan_android.ui.rocketscan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentRocketScanBinding
import kotlinx.coroutines.launch

class RocketScanFragment : Fragment() {

    private var _binding: FragmentRocketScanBinding? = null
    private val binding get() = _binding!!

    private val args: RocketScanFragmentArgs by navArgs()
    private val viewModel: RocketScanViewModel by viewModels()

    private lateinit var photoAdapter: RocketScanPhotoAdapter
    private var hasShownError = false

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

    private fun setupRecyclerView() {
        photoAdapter = RocketScanPhotoAdapter {
            Toast.makeText(
                requireContext(),
                R.string.rocket_scan_photo_preview_placeholder,
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.photoRecyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = photoAdapter
            setHasFixedSize(true)
        }
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
        binding.photoRecyclerView.isVisible = false
    }

    private fun renderEmpty() {
        binding.progressBar.isVisible = false
        binding.photoRecyclerView.isVisible = false
        binding.emptyView.isVisible = true
        binding.emptyView.setText(R.string.rocket_scan_empty_state)
        photoAdapter.submitList(emptyList())
    }

    private fun renderContent(content: RocketScanUiState.Content) {
        binding.progressBar.isVisible = false
        binding.emptyView.isVisible = false
        binding.photoRecyclerView.isVisible = true
        photoAdapter.submitList(content.photos)
    }

    private fun renderError(error: RocketScanUiState.Error) {
        binding.progressBar.isVisible = false
        binding.photoRecyclerView.isVisible = false
        binding.emptyView.isVisible = true
        binding.emptyView.text = error.message

        if (!hasShownError) {
            Toast.makeText(requireContext(), error.message, Toast.LENGTH_SHORT).show()
            hasShownError = true
        }
    }

    override fun onDestroyView() {
        binding.photoRecyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
