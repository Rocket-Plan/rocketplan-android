package com.example.rocketplan_android.ui.projects.lossinfo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class ClaimsListAdapter :
    ListAdapter<ClaimListItem, ClaimsListAdapter.ClaimViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClaimViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_claim_info, parent, false)
        return ClaimViewHolder(view)
    }

    override fun onBindViewHolder(holder: ClaimViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ClaimViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val policyHolder: TextView = itemView.findViewById(R.id.claimPolicyHolder)
        private val provider: TextView = itemView.findViewById(R.id.claimProvider)
        private val numbers: TextView = itemView.findViewById(R.id.claimNumbers)
        private val location: TextView = itemView.findViewById(R.id.claimLocation)

        fun bind(item: ClaimListItem) {
            policyHolder.text = item.claim.policyHolder.orEmpty().ifBlank {
                itemView.context.getString(R.string.loss_info_value_not_available)
            }
            provider.text = listOfNotNull(
                item.claim.provider,
                item.claim.representative
            ).firstOrNull().orEmpty().ifBlank {
                itemView.context.getString(R.string.loss_info_value_not_available)
            }
            val policyNumber = item.claim.policyNumber.orEmpty()
            val claimNumber = item.claim.claimNumber.orEmpty()
            numbers.text = listOf(policyNumber, claimNumber)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
                .ifBlank { itemView.context.getString(R.string.loss_info_value_not_available) }

            if (item.locationName.isNullOrBlank()) {
                location.visibility = View.GONE
            } else {
                location.visibility = View.VISIBLE
                location.text = item.locationName
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ClaimListItem>() {
        override fun areItemsTheSame(oldItem: ClaimListItem, newItem: ClaimListItem): Boolean =
            oldItem.claim.id == newItem.claim.id && oldItem.locationName == newItem.locationName

        override fun areContentsTheSame(oldItem: ClaimListItem, newItem: ClaimListItem): Boolean =
            oldItem == newItem
    }
}
