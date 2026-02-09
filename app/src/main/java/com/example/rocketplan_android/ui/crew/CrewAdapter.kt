package com.example.rocketplan_android.ui.crew

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class CrewAdapter(
    private val onRemoveClick: (CrewMember) -> Unit
) : ListAdapter<CrewMember, CrewAdapter.CrewViewHolder>(CrewDiffCallback()) {

    var isCurrentUserAdmin: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CrewViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crew, parent, false)
        return CrewViewHolder(view)
    }

    override fun onBindViewHolder(holder: CrewViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CrewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.crewMemberName)
        private val emailText: TextView = itemView.findViewById(R.id.crewMemberEmail)
        private val adminBadge: TextView = itemView.findViewById(R.id.crewAdminBadge)
        private val removeButton: ImageButton = itemView.findViewById(R.id.crewRemoveButton)

        fun bind(member: CrewMember) {
            nameText.text = member.name
            emailText.text = member.email
            adminBadge.isVisible = member.isAdmin

            // Show remove button if current user is admin and this is not their own row
            removeButton.isVisible = isCurrentUserAdmin && !member.isCurrentUser
            removeButton.setOnClickListener { onRemoveClick(member) }
        }
    }

    class CrewDiffCallback : DiffUtil.ItemCallback<CrewMember>() {
        override fun areItemsTheSame(oldItem: CrewMember, newItem: CrewMember): Boolean =
            oldItem.userId == newItem.userId

        override fun areContentsTheSame(oldItem: CrewMember, newItem: CrewMember): Boolean =
            oldItem == newItem
    }
}
