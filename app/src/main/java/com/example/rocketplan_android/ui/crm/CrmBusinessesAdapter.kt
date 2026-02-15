package com.example.rocketplan_android.ui.crm

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class CrmBusinessesAdapter(
    private val onBusinessClicked: (CrmBusinessListItem) -> Unit
) : ListAdapter<CrmBusinessListItem, CrmBusinessesAdapter.BusinessViewHolder>(DIFF_CALLBACK) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BusinessViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crm_contact, parent, false)
        return BusinessViewHolder(view, onBusinessClicked)
    }

    override fun onBindViewHolder(holder: BusinessViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class BusinessViewHolder(
        itemView: View,
        private val onBusinessClicked: (CrmBusinessListItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val initialsView: TextView = itemView.findViewById(R.id.contactInitials)
        private val nameView: TextView = itemView.findViewById(R.id.contactName)
        private val phoneView: TextView = itemView.findViewById(R.id.contactPhone)
        private val emailView: TextView = itemView.findViewById(R.id.contactEmail)
        private val typeBadge: TextView = itemView.findViewById(R.id.contactTypeBadge)
        private var boundItem: CrmBusinessListItem? = null

        init {
            itemView.setOnClickListener {
                boundItem?.let(onBusinessClicked)
            }
        }

        fun bind(item: CrmBusinessListItem) {
            boundItem = item
            initialsView.text = item.initials
            nameView.text = item.name

            phoneView.text = item.phone
            phoneView.isVisible = !item.phone.isNullOrBlank()

            emailView.text = item.email
            emailView.isVisible = !item.email.isNullOrBlank()

            typeBadge.isVisible = false
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CrmBusinessListItem>() {
            override fun areItemsTheSame(oldItem: CrmBusinessListItem, newItem: CrmBusinessListItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: CrmBusinessListItem, newItem: CrmBusinessListItem): Boolean =
                oldItem == newItem
        }
    }
}
