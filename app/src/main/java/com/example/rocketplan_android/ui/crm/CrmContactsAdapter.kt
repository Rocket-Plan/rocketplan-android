package com.example.rocketplan_android.ui.crm

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

class CrmContactsAdapter(
    private val onContactClicked: (CrmContactListItem) -> Unit,
    private val onContactLongClicked: ((CrmContactListItem) -> Unit)? = null,
    private val onRemoveClicked: ((CrmContactListItem) -> Unit)? = null
) : ListAdapter<CrmContactListItem, CrmContactsAdapter.ContactViewHolder>(DIFF_CALLBACK) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crm_contact, parent, false)
        return ContactViewHolder(view, onContactClicked, onContactLongClicked, onRemoveClicked)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ContactViewHolder(
        itemView: View,
        private val onContactClicked: (CrmContactListItem) -> Unit,
        private val onContactLongClicked: ((CrmContactListItem) -> Unit)? = null,
        private val onRemoveClicked: ((CrmContactListItem) -> Unit)? = null
    ) : RecyclerView.ViewHolder(itemView) {
        private val initialsView: TextView = itemView.findViewById(R.id.contactInitials)
        private val nameView: TextView = itemView.findViewById(R.id.contactName)
        private val phoneView: TextView = itemView.findViewById(R.id.contactPhone)
        private val emailView: TextView = itemView.findViewById(R.id.contactEmail)
        private val companyView: TextView = itemView.findViewById(R.id.contactCompany)
        private val typeBadge: TextView = itemView.findViewById(R.id.contactTypeBadge)
        private val removeButton: ImageButton = itemView.findViewById(R.id.removeButton)
        private var boundItem: CrmContactListItem? = null

        init {
            itemView.setOnClickListener {
                boundItem?.let(onContactClicked)
            }
            if (onContactLongClicked != null) {
                itemView.setOnLongClickListener {
                    boundItem?.let(onContactLongClicked)
                    true
                }
            }
            removeButton.setOnClickListener {
                boundItem?.let { onRemoveClicked?.invoke(it) }
            }
        }

        fun bind(item: CrmContactListItem) {
            boundItem = item
            initialsView.text = item.initials
            nameView.text = item.displayName

            phoneView.text = item.phone
            phoneView.isVisible = !item.phone.isNullOrBlank()

            emailView.text = item.email
            emailView.isVisible = !item.email.isNullOrBlank()

            companyView.text = item.companyName
            companyView.isVisible = !item.companyName.isNullOrBlank()

            typeBadge.text = item.type?.replaceFirstChar { it.uppercaseChar() }
            typeBadge.isVisible = !item.type.isNullOrBlank()

            removeButton.isVisible = onRemoveClicked != null
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CrmContactListItem>() {
            override fun areItemsTheSame(oldItem: CrmContactListItem, newItem: CrmContactListItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: CrmContactListItem, newItem: CrmContactListItem): Boolean =
                oldItem == newItem
        }
    }
}
