package com.example.rocketplan_android.ui.rocketscan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.ItemRocketScanPhotoBinding

class RocketScanPhotoAdapter(
    private val onPhotoClick: (RocketScanPhotoUiModel) -> Unit = {}
) : ListAdapter<RocketScanPhotoUiModel, RocketScanPhotoAdapter.PhotoViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemRocketScanPhotoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PhotoViewHolder(
        private val binding: ItemRocketScanPhotoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RocketScanPhotoUiModel) {
            binding.photoName.text = item.fileName
            binding.photoThumbnail.load(item.displaySource) {
                crossfade(true)
                placeholder(R.drawable.ic_photo)
                error(R.drawable.ic_photo)
            }

            binding.root.setOnClickListener {
                onPhotoClick(item)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RocketScanPhotoUiModel>() {
            override fun areItemsTheSame(
                oldItem: RocketScanPhotoUiModel,
                newItem: RocketScanPhotoUiModel
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: RocketScanPhotoUiModel,
                newItem: RocketScanPhotoUiModel
            ): Boolean = oldItem == newItem
        }
    }
}
