package com.example.rocketplan_android.ui.projects

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton

class RoomPhotoPagingAdapter(
    private val onPhotoSelected: (RoomPhotoItem) -> Unit
) : PagingDataAdapter<RoomPhotoItem, RoomPhotoPagingAdapter.RoomPhotoViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomPhotoViewHolder {
        val view = LayoutInflater
            .from(parent.context)
            .inflate(R.layout.item_room_photo, parent, false)
        return RoomPhotoViewHolder(view, onPhotoSelected)
    }

    override fun onBindViewHolder(holder: RoomPhotoViewHolder, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item)
    }

    private object DiffCallback : DiffUtil.ItemCallback<RoomPhotoItem>() {
        override fun areItemsTheSame(oldItem: RoomPhotoItem, newItem: RoomPhotoItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: RoomPhotoItem, newItem: RoomPhotoItem): Boolean =
            oldItem == newItem
    }

    class RoomPhotoViewHolder(
        view: View,
        private val onPhotoSelected: (RoomPhotoItem) -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val preview: ImageView = view.findViewById(R.id.roomPhotoPreview)
        private val dateLabel: TextView = view.findViewById(R.id.roomPhotoDate)

        fun bind(photo: RoomPhotoItem) {
            val previousPhoto = preview.getTag(R.id.tag_room_photo_id) as? RoomPhotoItem
            val needsReload = previousPhoto?.let { hasVisualDifferences(it, photo) } ?: true

            preview.setTag(R.id.tag_room_photo_id, photo)

            if (needsReload) {
                Log.d(TAG, "🔄 Loading image for photo id=${photo.id}, prev=${previousPhoto?.id}")
                preview.load(photo.thumbnailUrl) {
                    placeholder(R.drawable.bg_room_placeholder)
                    error(R.drawable.bg_room_placeholder)
                    crossfade(previousPhoto == null)
                }
            } else {
                Log.v(TAG, "✅ Skipping reload for photo id=${photo.id} (same as previous)")
            }

            dateLabel.text = photo.capturedOn ?: ""
            itemView.setOnClickListener { onPhotoSelected(photo) }
        }

        private fun hasVisualDifferences(old: RoomPhotoItem, new: RoomPhotoItem): Boolean {
            return old.id != new.id ||
                old.thumbnailUrl != new.thumbnailUrl ||
                old.imageUrl != new.imageUrl
        }

        companion object {
            private const val TAG = "RoomPhotoViewHolder"
        }
    }
}

class RoomPhotoAddAdapter(
    private val onAddPhoto: () -> Unit
) : RecyclerView.Adapter<RoomPhotoAddAdapter.AddPhotoViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AddPhotoViewHolder {
        val view = LayoutInflater
            .from(parent.context)
            .inflate(R.layout.item_room_photo_add, parent, false)
        return AddPhotoViewHolder(view, onAddPhoto)
    }

    override fun onBindViewHolder(holder: AddPhotoViewHolder, position: Int) {
        holder.bind()
    }

    override fun getItemCount(): Int = 1

    class AddPhotoViewHolder(
        view: View,
        private val onAddPhoto: () -> Unit
    ) : RecyclerView.ViewHolder(view) {

        init {
            view.setOnClickListener { onAddPhoto() }
        }

        fun bind() {
            // no-op
        }
    }
}

class RoomPhotoLoadStateAdapter(
    private val onRetry: () -> Unit
) : LoadStateAdapter<RoomPhotoLoadStateAdapter.LoadStateViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): LoadStateViewHolder {
        val view = LayoutInflater
            .from(parent.context)
            .inflate(R.layout.item_room_photo_load_state, parent, false)
        return LoadStateViewHolder(view, onRetry)
    }

    override fun onBindViewHolder(holder: LoadStateViewHolder, loadState: LoadState) {
        holder.bind(loadState)
    }

    class LoadStateViewHolder(
        view: View,
        private val onRetry: () -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val progress: ProgressBar = view.findViewById(R.id.roomPhotoLoadProgress)
        private val errorText: TextView = view.findViewById(R.id.roomPhotoErrorText)
        private val retryButton: MaterialButton = view.findViewById(R.id.roomPhotoRetryButton)

        fun bind(loadState: LoadState) {
            progress.isVisible = loadState is LoadState.Loading
            errorText.isVisible = loadState is LoadState.Error
            retryButton.isVisible = loadState is LoadState.Error

            if (loadState is LoadState.Error) {
                errorText.text = loadState.error.localizedMessage
                    ?.takeIf { it.isNotBlank() }
                    ?: itemView.context.getString(R.string.room_photos_load_error)
                retryButton.setOnClickListener { onRetry() }
            } else {
                retryButton.setOnClickListener(null)
            }
        }
    }
}
