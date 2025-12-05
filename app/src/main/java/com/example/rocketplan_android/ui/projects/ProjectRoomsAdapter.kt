package com.example.rocketplan_android.ui.projects

import android.util.Log
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

    enum class RoomStatMode { PHOTOS, DAMAGES }

    var statMode: RoomStatMode = RoomStatMode.PHOTOS
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

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

    inner class RoomViewHolder(
        view: View,
        private val onRoomClick: (RoomCard) -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val thumbnail: ImageView = view.findViewById(R.id.roomThumbnail)
        private val title: TextView = view.findViewById(R.id.roomTitle)
        private val photoCount: TextView = view.findViewById(R.id.photoCount)
        private val spinner: View = view.findViewById(R.id.loadingSpinner)
        private val roomTypeIcon: ImageView = view.findViewById(R.id.roomTypeIcon)

        fun bind(room: RoomCard) {
            val mode = this@ProjectRoomsAdapter.statMode
            val previousRoom = thumbnail.getTag(R.id.tag_room_photo_id) as? RoomCard
            val previousMode = thumbnail.getTag(R.id.tag_room_card_mode) as? RoomStatMode
            val hasModeChanged = previousMode != mode

            thumbnail.setTag(R.id.tag_room_photo_id, room)
            thumbnail.setTag(R.id.tag_room_card_mode, mode)

            title.text = room.title
            roomTypeIcon.setImageResource(room.iconRes)
            if (mode == RoomStatMode.DAMAGES) {
                roomTypeIcon.isVisible = true
                roomTypeIcon.setBackgroundResource(R.drawable.bg_icon_circle_white)
                val count = room.damageCount
                photoCount.text = itemView.resources.getQuantityString(
                    R.plurals.damage_count,
                    count,
                    count
                )
                spinner.isVisible = false
                val shouldResetPlaceholder = previousRoom == null ||
                    previousRoom.roomId != room.roomId ||
                    previousRoom.iconRes != room.iconRes ||
                    hasModeChanged
                if (shouldResetPlaceholder) {
                    Log.d(TAG, "🔄 Resetting thumbnail for damages view room id=${room.roomId}")
                    thumbnail.load(R.drawable.bg_room_placeholder) {
                        crossfade(false)
                    }
                }
                thumbnail.isVisible = true
                itemView.setOnClickListener { onRoomClick(room) }
                return
            }

            roomTypeIcon.isVisible = false
            photoCount.text = itemView.resources.getQuantityString(
                R.plurals.photo_count,
                room.photoCount,
                room.photoCount
            )
            spinner.isVisible = room.isLoadingPhotos

            val needsReload = previousRoom?.let { hasVisualDifferences(it, room) || hasModeChanged } ?: true
            if (needsReload) {
                Log.d(TAG, "🔄 Loading thumbnail for room id=${room.roomId}, prev=${previousRoom?.roomId}")
                // Include URL in cache key so image updates get fresh load
                val cacheKey = "room_card_${room.roomId}_${room.thumbnailUrl.hashCode()}"
                thumbnail.load(room.thumbnailUrl) {
                    memoryCacheKey(cacheKey)
                    placeholder(R.drawable.bg_room_placeholder)
                    error(R.drawable.bg_room_placeholder)
                    crossfade(previousRoom == null || hasModeChanged)
                }
            } else {
                Log.v(TAG, "✅ Skipping reload for room id=${room.roomId} (same as previous)")
            }
            thumbnail.isVisible = true
            itemView.setOnClickListener { onRoomClick(room) }
        }

        private fun hasVisualDifferences(old: RoomCard, new: RoomCard): Boolean {
            return old.roomId != new.roomId ||
                old.thumbnailUrl != new.thumbnailUrl ||
                old.iconRes != new.iconRes
        }
    }

    companion object {
        private const val TAG = "ProjectRoomsAdapter"
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
