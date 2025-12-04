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
        private val claimType: TextView = itemView.findViewById(R.id.claimType)
        private val provider: TextView = itemView.findViewById(R.id.claimProvider)
        private val numbers: TextView = itemView.findViewById(R.id.claimNumbers)
        private val adjuster: TextView = itemView.findViewById(R.id.claimAdjuster)
        private val deductible: TextView = itemView.findViewById(R.id.claimDeductible)
        private val location: TextView = itemView.findViewById(R.id.claimLocation)

        fun bind(item: ClaimListItem) {
            policyHolder.text = item.claim.policyHolder.orEmpty().ifBlank {
                itemView.context.getString(R.string.loss_info_value_not_available)
            }
            claimType.text = buildClaimTypeText(item)
            provider.text = listOfNotNull(
                item.claim.provider,
                item.claim.representative
            ).firstOrNull().orEmpty().ifBlank {
                itemView.context.getString(R.string.loss_info_value_not_available)
            }
            val policyNumber = item.claim.policyNumber.orEmpty()
            val claimNumber = item.claim.claimNumber.orEmpty()
            val numbersLabel = itemView.context.getString(R.string.loss_info_claim_numbers_label)
            numbers.text = buildString {
                append(numbersLabel).append(": ")
                append(
                    listOf(
                        policyNumber.takeIf { it.isNotBlank() }?.let {
                            itemView.context.getString(R.string.loss_info_claim_policy_number, it)
                        },
                        claimNumber.takeIf { it.isNotBlank() }?.let {
                            itemView.context.getString(R.string.loss_info_claim_claim_number, it)
                        }
                    ).filterNotNull()
                        .joinToString(" • ")
                        .ifBlank { itemView.context.getString(R.string.loss_info_value_not_available) }
                )
            }

            val adjusterLabel = itemView.context.getString(R.string.loss_info_claim_adjuster_label)
            val adjusterValue = listOfNotNull(
                item.claim.adjuster,
                item.claim.adjusterPhone,
                item.claim.adjusterEmail
            ).joinToString(" • ").ifBlank {
                itemView.context.getString(R.string.loss_info_value_not_available)
            }
            adjuster.text = itemView.context.getString(
                R.string.loss_info_claim_labeled_value,
                adjusterLabel,
                adjusterValue
            )

            val deductibleLabel = itemView.context.getString(R.string.loss_info_claim_deductible_label)
            val deductibleValue = item.claim.insuranceDeductible.orEmpty().ifBlank {
                itemView.context.getString(R.string.loss_info_value_not_available)
            }
            deductible.text = itemView.context.getString(
                R.string.loss_info_claim_labeled_value,
                deductibleLabel,
                deductibleValue
            )

            if (item.locationName.isNullOrBlank()) {
                location.text = itemView.context.getString(R.string.loss_info_claim_project_tag)
            } else {
                location.text = item.locationName
            }
        }

        private fun buildClaimTypeText(item: ClaimListItem): String {
            val fallback = item.locationName?.let {
                itemView.context.getString(R.string.loss_info_claim_location_claim_label, it)
            } ?: itemView.context.getString(R.string.loss_info_claim_project_claim_label)
            return item.claim.claimType?.name?.takeIf { it.isNotBlank() } ?: fallback
        }
    }

    private object Diff : DiffUtil.ItemCallback<ClaimListItem>() {
        override fun areItemsTheSame(oldItem: ClaimListItem, newItem: ClaimListItem): Boolean =
            oldItem.claim.id == newItem.claim.id && oldItem.locationName == newItem.locationName

        override fun areContentsTheSame(oldItem: ClaimListItem, newItem: ClaimListItem): Boolean =
            oldItem == newItem
    }
}
