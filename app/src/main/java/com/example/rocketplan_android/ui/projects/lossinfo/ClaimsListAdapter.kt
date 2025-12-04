package com.example.rocketplan_android.ui.projects.lossinfo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class ClaimsListAdapter(
    private val onEditClick: (ClaimListItem) -> Unit
) : ListAdapter<ClaimListItem, ClaimsListAdapter.ClaimViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClaimViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_claim_info, parent, false)
        return ClaimViewHolder(view)
    }

    override fun onBindViewHolder(holder: ClaimViewHolder, position: Int) {
        holder.bind(getItem(position), onEditClick)
    }

    class ClaimViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val claimTitle: TextView = itemView.findViewById(R.id.claimTitle)
        private val policyHolder: TextView = itemView.findViewById(R.id.claimPolicyHolder)
        private val ownershipStatus: TextView = itemView.findViewById(R.id.claimOwnershipStatus)
        private val phone: TextView = itemView.findViewById(R.id.claimPhone)
        private val email: TextView = itemView.findViewById(R.id.claimEmail)
        private val representative: TextView = itemView.findViewById(R.id.claimRepresentative)
        private val provider: TextView = itemView.findViewById(R.id.claimProvider)
        private val deductible: TextView = itemView.findViewById(R.id.claimDeductible)
        private val policyNumber: TextView = itemView.findViewById(R.id.claimPolicyNumber)
        private val claimNumber: TextView = itemView.findViewById(R.id.claimClaimNumber)
        private val adjuster: TextView = itemView.findViewById(R.id.claimAdjuster)
        private val adjusterPhone: TextView = itemView.findViewById(R.id.claimAdjusterPhone)
        private val adjusterEmail: TextView = itemView.findViewById(R.id.claimAdjusterEmail)
        private val editButton: ImageButton = itemView.findViewById(R.id.claimEditButton)

        fun bind(item: ClaimListItem, onEditClick: (ClaimListItem) -> Unit) {
            val placeholder = itemView.context.getString(R.string.loss_info_value_not_available)

            claimTitle.text = buildClaimTitle(item, placeholder)
            policyHolder.text = valueOrPlaceholder(item.claim.policyHolder, placeholder)
            ownershipStatus.text = valueOrPlaceholder(item.claim.ownershipStatus, placeholder)
            phone.text = valueOrPlaceholder(item.claim.policyHolderPhone, placeholder)
            email.text = valueOrPlaceholder(item.claim.policyHolderEmail, placeholder)
            representative.text = valueOrPlaceholder(item.claim.representative, placeholder)
            provider.text = valueOrPlaceholder(item.claim.provider, placeholder)
            deductible.text = valueOrPlaceholder(item.claim.insuranceDeductible, placeholder)
            policyNumber.text = valueOrPlaceholder(item.claim.policyNumber, placeholder)
            claimNumber.text = valueOrPlaceholder(item.claim.claimNumber, placeholder)
            adjuster.text = valueOrPlaceholder(item.claim.adjuster, placeholder)
            adjusterPhone.text = valueOrPlaceholder(item.claim.adjusterPhone, placeholder)
            adjusterEmail.text = valueOrPlaceholder(item.claim.adjusterEmail, placeholder)

            editButton.setOnClickListener { onEditClick(item) }
        }

        private fun buildClaimTitle(item: ClaimListItem, placeholder: String): String =
            item.locationName?.takeIf { it.isNotBlank() }
                ?: item.claim.claimType?.name?.takeIf { it.isNotBlank() }
                ?: itemView.context.getString(R.string.loss_info_claim_project_tag)
                    .takeIf { it.isNotBlank() }
                ?: placeholder

        private fun valueOrPlaceholder(value: String?, placeholder: String): String =
            value?.takeIf { it.isNotBlank() } ?: placeholder
    }

    private object Diff : DiffUtil.ItemCallback<ClaimListItem>() {
        override fun areItemsTheSame(oldItem: ClaimListItem, newItem: ClaimListItem): Boolean =
            oldItem.claim.id == newItem.claim.id && oldItem.locationName == newItem.locationName

        override fun areContentsTheSame(oldItem: ClaimListItem, newItem: ClaimListItem): Boolean =
            oldItem == newItem
    }
}
