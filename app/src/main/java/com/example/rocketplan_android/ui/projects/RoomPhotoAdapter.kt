package com.example.rocketplan_android.ui.projects

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.rocketplan_android.R

sealed class RoomPhotoListItem {
    object AddPhoto : RoomPhotoListItem()
    data class Photo(val data: RoomPhotoItem) : RoomPhotoListItem()
}

class RoomPhotoAdapter(
    private val onAddPhoto: () -> Unit,
    private val onPhotoSelected: (RoomPhotoItem) -> Unit
) : ListAdapter<RoomPhotoListItem, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        RoomPhotoListItem.AddPhoto -> VIEW_TYPE_ADD
        is RoomPhotoListItem.Photo -> VIEW_TYPE_PHOTO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ADD -> AddPhotoViewHolder(
                inflater.inflate(R.layout.item_room_photo_add, parent, false),
                onAddPhoto
            )
            else -> RoomPhotoViewHolder(
                inflater.inflate(R.layout.item_room_photo, parent, false),
                onPhotoSelected
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AddPhotoViewHolder -> holder.bind()
            is RoomPhotoViewHolder -> holder.bind((getItem(position) as RoomPhotoListItem.Photo).data)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RoomPhotoListItem>() {
        override fun areItemsTheSame(oldItem: RoomPhotoListItem, newItem: RoomPhotoListItem): Boolean =
            when {
                oldItem is RoomPhotoListItem.AddPhoto && newItem is RoomPhotoListItem.AddPhoto -> true
                oldItem is RoomPhotoListItem.Photo && newItem is RoomPhotoListItem.Photo ->
                    oldItem.data.id == newItem.data.id
                else -> false
            }

        override fun areContentsTheSame(oldItem: RoomPhotoListItem, newItem: RoomPhotoListItem): Boolean =
            oldItem == newItem
    }

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

    class RoomPhotoViewHolder(
        view: View,
        private val onPhotoSelected: (RoomPhotoItem) -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val preview: ImageView = view.findViewById(R.id.roomPhotoPreview)
        private val dateLabel: TextView = view.findViewById(R.id.roomPhotoDate)

        fun bind(photo: RoomPhotoItem) {
            preview.load(photo.thumbnailUrl) {
                placeholder(R.drawable.bg_room_placeholder)
                error(R.drawable.bg_room_placeholder)
                crossfade(true)
            }
            dateLabel.text = photo.capturedOn ?: ""
            itemView.setOnClickListener { onPhotoSelected(photo) }
        }
    }

    companion object {
        private const val VIEW_TYPE_ADD = 0
        private const val VIEW_TYPE_PHOTO = 1
    }
}
