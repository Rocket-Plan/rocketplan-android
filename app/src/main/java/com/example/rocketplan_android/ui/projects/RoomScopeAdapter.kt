package com.example.rocketplan_android.ui.projects

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class RoomScopeAdapter :
    ListAdapter<RoomScopeItem, RoomScopeAdapter.RoomScopeViewHolder>(diffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomScopeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room_scope, parent, false)
        return RoomScopeViewHolder(view)
    }

    override fun onBindViewHolder(holder: RoomScopeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class RoomScopeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.scopeTitle)
        private val description: TextView = itemView.findViewById(R.id.scopeDescription)
        private val updatedLabel: TextView = itemView.findViewById(R.id.scopeUpdatedLabel)

        fun bind(item: RoomScopeItem) {
            title.text = item.title

            val descriptionText = item.description?.trim().orEmpty()
            description.text = descriptionText
            description.visibility = if (descriptionText.isBlank()) View.GONE else View.VISIBLE

            val updatedText = item.updatedOn
            if (updatedText.isNullOrBlank()) {
                updatedLabel.visibility = View.GONE
            } else {
                updatedLabel.text = itemView.context.getString(
                    R.string.room_scope_updated_format,
                    updatedText
                )
                updatedLabel.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<RoomScopeItem>() {
            override fun areItemsTheSame(oldItem: RoomScopeItem, newItem: RoomScopeItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: RoomScopeItem, newItem: RoomScopeItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
