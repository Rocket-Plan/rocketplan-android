package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.combine
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

    private lateinit var photoPager: ViewPager2
    private lateinit var photoCounter: TextView
    private lateinit var addNoteFab: FloatingActionButton
    private lateinit var deletePhotoFab: FloatingActionButton
    private lateinit var pagerAdapter: PhotoPagerAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_photo_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        photoPager = view.findViewById(R.id.photoPager)
        photoCounter = view.findViewById(R.id.photoCounter)
        addNoteFab = view.findViewById(R.id.addPhotoNoteFab)
        deletePhotoFab = view.findViewById(R.id.deletePhotoFab)

        addNoteFab.setOnClickListener { showAddNoteDialog() }
        deletePhotoFab.setOnClickListener { confirmDeleteCurrentPhoto() }

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
                            updateActionBar(info)
                            photoCounter.text = "${info.currentIndex + 1} / ${info.totalCount}"
                            photoCounter.isVisible = info.totalCount > 1
                        } else {
                            updateActionBar(null)
                            photoCounter.text = ""
                            photoCounter.isVisible = false
                        }
                    }
                }

                launch {
                    combine(
                        viewModel.currentPhoto,
                        viewModel.isSavingNote,
                        viewModel.isDeletingPhoto
                    ) { current, saving, deleting ->
                        Triple(current != null, saving, deleting)
                    }.collect { (hasPhoto, savingNote, deletingPhoto) ->
                        val actionsEnabled = hasPhoto && !savingNote && !deletingPhoto
                        addNoteFab.isVisible = hasPhoto
                        deletePhotoFab.isVisible = hasPhoto
                        addNoteFab.isEnabled = actionsEnabled
                        deletePhotoFab.isEnabled = actionsEnabled
                        val enabledAlpha = 1f
                        val disabledAlpha = 0.5f
                        addNoteFab.alpha = if (addNoteFab.isEnabled) enabledAlpha else disabledAlpha
                        deletePhotoFab.alpha = if (deletePhotoFab.isEnabled) enabledAlpha else disabledAlpha
                    }
                }

                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is PhotoViewerEvent.NoteSaved -> {
                                Snackbar.make(
                                    requireView(),
                                    "Note added to photo",
                                    Snackbar.LENGTH_SHORT
                                ).show()
                            }
                            is PhotoViewerEvent.PhotoDeleted -> {
                                val messageRes = if (event.pendingSync) {
                                    R.string.photo_delete_pending_sync
                                } else {
                                    R.string.photo_deleted
                                }
                                Snackbar.make(
                                    requireView(),
                                    getString(messageRes),
                                    Snackbar.LENGTH_SHORT
                                ).show()

                                if (event.remainingCount == 0) {
                                    findNavController().navigateUp()
                                } else {
                                    val targetIndex = event.newIndex?.coerceAtLeast(0) ?: 0
                                    if (photoPager.currentItem != targetIndex) {
                                        photoPager.setCurrentItem(targetIndex, false)
                                    }
                                }
                            }
                            is PhotoViewerEvent.Error -> {
                                Snackbar.make(
                                    requireView(),
                                    event.message,
                                    Snackbar.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showAddNoteDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_note, null)
        val noteInput = dialogView.findViewById<TextInputEditText>(R.id.noteInput)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_note)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val text = noteInput.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    viewModel.addNoteForCurrentPhoto(text)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteCurrentPhoto() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_photo)
            .setMessage(R.string.delete_photo_confirmation)
            .setPositiveButton(R.string.delete) { dialog, _ ->
                viewModel.deleteCurrentPhoto()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateActionBar(info: CurrentPhotoInfo?) {
        val actionBar = (activity as? AppCompatActivity)?.supportActionBar ?: return
        actionBar.title = info?.title ?: getString(R.string.photo_viewer_title)
        actionBar.subtitle = info?.subtitle
        actionBar.show()
    }

    override fun onDestroyView() {
        (activity as? AppCompatActivity)?.supportActionBar?.subtitle = null
        super.onDestroyView()
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

        fun bind(item: PhotoPageItem) {
            // Use tag to track what's currently displayed and detect visual changes
            val previousItem = photoView.getTag(R.id.tag_room_photo_id) as? PhotoPageItem
            val needsReload = previousItem?.let { hasVisualDifferences(it, item) } ?: true

            photoView.setTag(R.id.tag_room_photo_id, item)

            val displaySource = resolveDisplaySource(item)
            if (displaySource == null) {
                loadingIndicator.isVisible = false
                photoView.setImageResource(R.drawable.bg_room_placeholder)
                errorLabel.isVisible = true
                return
            }

            if (needsReload) {
                loadingIndicator.isVisible = true
                errorLabel.isVisible = false

                // Include source path/URL hash in cache key so content updates get fresh load
                val cacheKey = "photo_${item.photoId}_${displaySource.hashCode()}"

                photoView.load(displaySource) {
                    memoryCacheKey(cacheKey)
                    placeholder(R.drawable.bg_room_placeholder)
                    error(R.drawable.bg_room_placeholder)
                    crossfade(previousItem == null)
                    listener(
                        onSuccess = { _, _ ->
                            loadingIndicator.isVisible = false
                            errorLabel.isVisible = false
                        },
                        onError = { _, _ ->
                            loadingIndicator.isVisible = false
                            errorLabel.isVisible = true
                            Log.e("PhotoPager", "Failed to load photo ${item.photoId}")
                        }
                    )
                }
            } else {
                // Same visual content, just ensure UI state is correct
                loadingIndicator.isVisible = false
                errorLabel.isVisible = false
            }
        }

        private fun hasVisualDifferences(old: PhotoPageItem, new: PhotoPageItem): Boolean {
            return old.photoId != new.photoId ||
                old.localPath != new.localPath ||
                old.cachedOriginalPath != new.cachedOriginalPath ||
                old.cachedThumbnailPath != new.cachedThumbnailPath ||
                old.remoteUrl != new.remoteUrl ||
                old.thumbnailUrl != new.thumbnailUrl
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
