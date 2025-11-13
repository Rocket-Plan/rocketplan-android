package com.example.rocketplan_android.ui.projects.addroom

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.databinding.ItemRoomTypeHeaderBinding
import com.example.rocketplan_android.databinding.ItemRoomTypeOptionBinding

sealed class RoomTypeListItem {
    data class Header(val title: String) : RoomTypeListItem()
    data class Option(val option: RoomTypeUiModel) : RoomTypeListItem()
}

class RoomTypeAdapter(
    private val onOptionClick: (RoomTypeUiModel) -> Unit
) : ListAdapter<RoomTypeListItem, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is RoomTypeListItem.Header -> VIEW_TYPE_HEADER
        is RoomTypeListItem.Option -> VIEW_TYPE_OPTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                ItemRoomTypeHeaderBinding.inflate(inflater, parent, false)
            )
            else -> OptionViewHolder(
                ItemRoomTypeOptionBinding.inflate(inflater, parent, false),
                onOptionClick
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind(getItem(position) as RoomTypeListItem.Header)
            is OptionViewHolder -> holder.bind(getItem(position) as RoomTypeListItem.Option)
        }
    }

    private class HeaderViewHolder(
        private val binding: ItemRoomTypeHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(header: RoomTypeListItem.Header) {
            binding.roomTypeHeaderText.text = header.title
        }
    }

    private class OptionViewHolder(
        private val binding: ItemRoomTypeOptionBinding,
        private val onOptionClick: (RoomTypeUiModel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RoomTypeListItem.Option) {
            val option = item.option
            binding.roomTypeTitle.text = option.displayName
            binding.roomTypeIcon.setImageResource(option.iconRes)
            binding.roomTypeIcon.contentDescription = option.displayName
            binding.root.setOnClickListener { onOptionClick(option) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RoomTypeListItem>() {
        override fun areItemsTheSame(oldItem: RoomTypeListItem, newItem: RoomTypeListItem): Boolean {
            return when {
                oldItem is RoomTypeListItem.Header && newItem is RoomTypeListItem.Header ->
                    oldItem.title == newItem.title
                oldItem is RoomTypeListItem.Option && newItem is RoomTypeListItem.Option ->
                    oldItem.option.id == newItem.option.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: RoomTypeListItem, newItem: RoomTypeListItem): Boolean = oldItem == newItem
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_OPTION = 1
    }
}
