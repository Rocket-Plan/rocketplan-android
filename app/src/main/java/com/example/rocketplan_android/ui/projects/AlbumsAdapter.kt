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

class AlbumsAdapter(
    private val onAlbumClick: (AlbumSection) -> Unit
) : ListAdapter<AlbumSection, AlbumsAdapter.AlbumViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album_card, parent, false)
        return AlbumViewHolder(view, onAlbumClick)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private object DiffCallback : DiffUtil.ItemCallback<AlbumSection>() {
        override fun areItemsTheSame(oldItem: AlbumSection, newItem: AlbumSection): Boolean =
            oldItem.albumId == newItem.albumId

        override fun areContentsTheSame(oldItem: AlbumSection, newItem: AlbumSection): Boolean =
            oldItem == newItem
    }

    class AlbumViewHolder(
        view: View,
        private val onAlbumClick: (AlbumSection) -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val thumbnail: ImageView = view.findViewById(R.id.albumThumbnail)
        private val name: TextView = view.findViewById(R.id.albumName)
        private val photoCount: TextView = view.findViewById(R.id.albumPhotoCount)

        fun bind(album: AlbumSection) {
            name.text = album.name
            photoCount.text = itemView.resources.getQuantityString(
                R.plurals.photo_count,
                album.photoCount,
                album.photoCount
            )
            thumbnail.load(album.thumbnailUrl) {
                placeholder(R.drawable.bg_room_placeholder)
                error(R.drawable.bg_room_placeholder)
                crossfade(true)
            }
            thumbnail.isVisible = true
            itemView.setOnClickListener { onAlbumClick(album) }
        }
    }
}

fun RecyclerView.configureForAlbums(adapter: AlbumsAdapter, spanCount: Int = 2) {
    layoutManager = GridLayoutManager(context, spanCount)
    this.adapter = adapter
    addItemDecoration(RoomGridSpacingDecoration(spanCount, context.resources.getDimensionPixelSize(R.dimen.room_grid_spacing)))
}
