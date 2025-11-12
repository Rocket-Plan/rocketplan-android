package com.example.rocketplan_android.ui.projects

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.rocketplan_android.R

sealed class RoomListItem {
    data class Header(val title: String) : RoomListItem()
    data class Room(val data: RoomCard) : RoomListItem()
}

class ProjectRoomsAdapter(
    private val onRoomClick: (RoomCard) -> Unit
) : ListAdapter<RoomListItem, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is RoomListItem.Header -> VIEW_TYPE_HEADER
        is RoomListItem.Room -> VIEW_TYPE_ROOM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_room_level_header, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_room_card, parent, false)
                RoomViewHolder(view, onRoomClick)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind(getItem(position) as RoomListItem.Header)
            is RoomViewHolder -> holder.bind((getItem(position) as RoomListItem.Room).data)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RoomListItem>() {
        override fun areItemsTheSame(oldItem: RoomListItem, newItem: RoomListItem): Boolean =
            when {
                oldItem is RoomListItem.Header && newItem is RoomListItem.Header ->
                    oldItem.title == newItem.title
                oldItem is RoomListItem.Room && newItem is RoomListItem.Room ->
                    oldItem.data.roomId == newItem.data.roomId
                else -> false
            }

        override fun areContentsTheSame(oldItem: RoomListItem, newItem: RoomListItem): Boolean =
            oldItem == newItem
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view as TextView
        fun bind(item: RoomListItem.Header) {
            title.text = item.title
        }
    }

    class RoomViewHolder(
        view: View,
        private val onRoomClick: (RoomCard) -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val thumbnail: ImageView = view.findViewById(R.id.roomThumbnail)
        private val title: TextView = view.findViewById(R.id.roomTitle)
        private val photoCount: TextView = view.findViewById(R.id.photoCount)
        private val spinner: View = view.findViewById(R.id.loadingSpinner)

        fun bind(room: RoomCard) {
            title.text = room.title
            photoCount.text = itemView.resources.getQuantityString(
                R.plurals.photo_count,
                room.photoCount,
                room.photoCount
            )
            spinner.isVisible = room.isLoadingPhotos

            thumbnail.load(room.thumbnailUrl) {
                placeholder(R.drawable.bg_room_placeholder)
                error(R.drawable.bg_room_placeholder)
                crossfade(true)
            }
            thumbnail.isVisible = true
            itemView.setOnClickListener { onRoomClick(room) }
        }
    }

    companion object {
        const val VIEW_TYPE_HEADER = 1
        const val VIEW_TYPE_ROOM = 2
    }
}

fun RecyclerView.configureForProjectRooms(adapter: ProjectRoomsAdapter, spanCount: Int = 2) {
    layoutManager = GridLayoutManager(context, spanCount).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == ProjectRoomsAdapter.VIEW_TYPE_HEADER) {
                    spanCount
                } else {
                    1
                }
            }
        }
    }
    this.adapter = adapter
    addItemDecoration(RoomGridSpacingDecoration(spanCount, context.resources.getDimensionPixelSize(R.dimen.room_grid_spacing)))
}
