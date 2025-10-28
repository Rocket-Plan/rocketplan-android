package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import coil.load
import com.example.rocketplan_android.R
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import java.io.File

class PhotoViewerFragment : Fragment() {

    private val args: PhotoViewerFragmentArgs by navArgs()
    private val viewModel: PhotoViewerViewModel by viewModels {
        PhotoViewerViewModel.provideFactory(requireActivity().application, args.photoId)
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var photoView: PhotoView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorLabel: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_photo_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.photoToolbar)
        photoView = view.findViewById(R.id.photoView)
        loadingIndicator = view.findViewById(R.id.loadingIndicator)
        errorLabel = view.findViewById(R.id.errorLabel)

        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        PhotoViewerUiState.Loading -> showLoading()
                        is PhotoViewerUiState.Ready -> renderPhoto(state.content)
                        is PhotoViewerUiState.Error -> showError(state.message)
                    }
                }
            }
        }
    }

    private fun showLoading() {
        loadingIndicator.isVisible = true
        errorLabel.isVisible = false
    }

    private fun showError(message: String) {
        loadingIndicator.isVisible = false
        errorLabel.isVisible = true
        errorLabel.text = message
    }

    private fun renderPhoto(content: PhotoViewerContent) {
        loadingIndicator.isVisible = true
        errorLabel.isVisible = false

        toolbar.title = content.title
        toolbar.subtitle = content.capturedLabel

        val displaySource = resolveDisplaySource(content)
        if (displaySource == null) {
            viewModel.reportError("No display source available for photo ${content.photoId}")
            showError(getString(R.string.rocket_scan_photo_preview_placeholder))
            return
        }

        photoView.load(displaySource) {
            placeholder(R.drawable.bg_room_placeholder)
            error(R.drawable.bg_room_placeholder)
            listener(
                onSuccess = { _, _ -> loadingIndicator.isVisible = false },
                onError = { _, _ ->
                    loadingIndicator.isVisible = false
                    viewModel.reportError("Failed to load photo ${content.photoId}")
                    showError(getString(R.string.rocket_scan_photo_preview_placeholder))
                }
            )
        }
    }

    private fun resolveDisplaySource(content: PhotoViewerContent): Any? {
        val localOriginal = content.localOriginalPath?.let(::File)?.takeIf { it.exists() }
        if (localOriginal != null) return localOriginal

        val localThumb = content.localThumbnailPath?.let(::File)?.takeIf { it.exists() }
        if (localThumb != null) return localThumb

        if (!content.remoteUrl.isNullOrBlank()) return content.remoteUrl
        if (!content.thumbnailUrl.isNullOrBlank()) return content.thumbnailUrl
        return null
    }
}
