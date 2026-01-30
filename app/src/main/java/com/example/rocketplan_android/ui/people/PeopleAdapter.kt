package com.example.rocketplan_android.ui.people

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

class PeopleAdapter(
    private val onEditClicked: (PersonListItem) -> Unit,
    private val onDeleteClicked: (PersonListItem) -> Unit
) : ListAdapter<PersonListItem, PeopleAdapter.PersonViewHolder>(DIFF_CALLBACK) {

    var isCurrentUserAdmin: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PersonViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_person, parent, false)
        return PersonViewHolder(view, onEditClicked, onDeleteClicked)
    }

    override fun onBindViewHolder(holder: PersonViewHolder, position: Int) {
        holder.bind(getItem(position), isCurrentUserAdmin)
    }

    class PersonViewHolder(
        itemView: View,
        private val onEditClicked: (PersonListItem) -> Unit,
        private val onDeleteClicked: (PersonListItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.personName)
        private val email: TextView = itemView.findViewById(R.id.personEmail)
        private val adminBadge: TextView = itemView.findViewById(R.id.adminBadge)
        private val youBadge: TextView = itemView.findViewById(R.id.youBadge)
        private val editButton: ImageButton = itemView.findViewById(R.id.editPersonButton)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deletePersonButton)
        private var boundItem: PersonListItem? = null

        init {
            editButton.setOnClickListener {
                boundItem?.let(onEditClicked)
            }
            deleteButton.setOnClickListener {
                boundItem?.let(onDeleteClicked)
            }
        }

        fun bind(item: PersonListItem, isCurrentUserAdmin: Boolean) {
            boundItem = item
            name.text = item.name
            email.text = item.email
            adminBadge.isVisible = item.isAdmin
            youBadge.isVisible = item.isCurrentUser

            // Only show edit/delete buttons if current user is admin
            // and this is not the current user (can't delete yourself)
            val canModify = isCurrentUserAdmin && !item.isCurrentUser
            editButton.isVisible = canModify
            deleteButton.isVisible = canModify
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PersonListItem>() {
            override fun areItemsTheSame(oldItem: PersonListItem, newItem: PersonListItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: PersonListItem, newItem: PersonListItem): Boolean =
                oldItem == newItem
        }
    }
}
