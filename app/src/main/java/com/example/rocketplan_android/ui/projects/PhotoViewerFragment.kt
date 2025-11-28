package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.util.Log
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
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.example.rocketplan_android.R
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import java.io.File

class PhotoViewerFragment : Fragment() {

    private val args: PhotoViewerFragmentArgs by navArgs()
    private val viewModel: PhotoViewerViewModel by viewModels {
        PhotoViewerViewModel.provideFactory(
            requireActivity().application,
            args.photoIds.toList()
        )
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var photoPager: ViewPager2
    private lateinit var photoCounter: TextView
    private lateinit var pagerAdapter: PhotoPagerAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_photo_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.photoToolbar)
        photoPager = view.findViewById(R.id.photoPager)
        photoCounter = view.findViewById(R.id.photoCounter)

        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        setupPager()
        observeViewModel()
    }

    private fun setupPager() {
        pagerAdapter = PhotoPagerAdapter()
        photoPager.adapter = pagerAdapter

        photoPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                viewModel.onPageSelected(position)
            }
        })
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.photos.collect { photos ->
                        pagerAdapter.submitList(photos)

                        // Set initial position after photos load
                        if (photos.isNotEmpty() && photoPager.currentItem == 0 && args.startIndex > 0) {
                            photoPager.setCurrentItem(args.startIndex, false)
                        }
                    }
                }

                launch {
                    viewModel.currentPhotoInfo.collect { info ->
                        if (info != null) {
                            toolbar.title = info.title
                            toolbar.subtitle = info.subtitle
                            photoCounter.text = "${info.currentIndex + 1} / ${info.totalCount}"
                            photoCounter.isVisible = info.totalCount > 1
                        }
                    }
                }
            }
        }
    }
}

// Adapter for ViewPager2 with DiffUtil for efficient updates
class PhotoPagerAdapter : RecyclerView.Adapter<PhotoPagerAdapter.PhotoPageViewHolder>() {

    private val asyncDiffer = AsyncListDiffer(this, DIFF_CALLBACK)

    init {
        setHasStableIds(true)
    }

    fun submitList(newPhotos: List<PhotoPageItem>) {
        asyncDiffer.submitList(newPhotos)
    }

    override fun getItemId(position: Int): Long = asyncDiffer.currentList[position].photoId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoPageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo_page, parent, false)
        return PhotoPageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoPageViewHolder, position: Int) {
        holder.bind(asyncDiffer.currentList[position])
    }

    override fun getItemCount(): Int = asyncDiffer.currentList.size

    class PhotoPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val photoView: PhotoView = itemView.findViewById(R.id.photoView)
        private val loadingIndicator: ProgressBar = itemView.findViewById(R.id.loadingIndicator)
        private val errorLabel: TextView = itemView.findViewById(R.id.errorLabel)

        private var currentPhotoId: Long? = null

        fun bind(item: PhotoPageItem) {
            // Skip rebinding if same photo
            if (currentPhotoId == item.photoId) return
            currentPhotoId = item.photoId

            loadingIndicator.isVisible = true
            errorLabel.isVisible = false

            val displaySource = resolveDisplaySource(item)
            if (displaySource == null) {
                loadingIndicator.isVisible = false
                errorLabel.isVisible = true
                return
            }

            // Use photoId as stable cache key to avoid reloads from URL changes
            val cacheKey = "photo_${item.photoId}"

            photoView.load(displaySource) {
                memoryCacheKey(cacheKey)
                placeholderMemoryCacheKey(cacheKey)
                listener(
                    onSuccess = { _, _ ->
                        loadingIndicator.isVisible = false
                    },
                    onError = { _, _ ->
                        loadingIndicator.isVisible = false
                        errorLabel.isVisible = true
                        Log.e("PhotoPager", "Failed to load photo ${item.photoId}")
                    }
                )
            }
        }

        private fun resolveDisplaySource(item: PhotoPageItem): Any? {
            item.localPath?.let { path ->
                val file = File(path)
                if (file.exists()) return file
            }

            item.cachedOriginalPath?.let { path ->
                val file = File(path)
                if (file.exists()) return file
            }

            item.cachedThumbnailPath?.let { path ->
                val file = File(path)
                if (file.exists()) return file
            }

            if (!item.remoteUrl.isNullOrBlank()) return item.remoteUrl
            if (!item.thumbnailUrl.isNullOrBlank()) return item.thumbnailUrl

            return null
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PhotoPageItem>() {
            override fun areItemsTheSame(oldItem: PhotoPageItem, newItem: PhotoPageItem): Boolean =
                oldItem.photoId == newItem.photoId

            override fun areContentsTheSame(oldItem: PhotoPageItem, newItem: PhotoPageItem): Boolean =
                oldItem == newItem
        }
    }
}
