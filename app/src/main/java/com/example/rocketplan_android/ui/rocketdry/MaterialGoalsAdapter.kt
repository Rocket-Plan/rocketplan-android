package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class MaterialGoalsAdapter(
    private val onCardTapped: (MaterialDryingGoalItem) -> Unit,
    private val onAddLogTapped: (MaterialDryingGoalItem) -> Unit
) : ListAdapter<MaterialDryingGoalItem, MaterialGoalsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_material_goal, parent, false)
        return ViewHolder(view, onCardTapped, onAddLogTapped)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onCardTapped: (MaterialDryingGoalItem) -> Unit,
        private val onAddLogTapped: (MaterialDryingGoalItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val materialName: TextView = itemView.findViewById(R.id.goalName)
        private val materialTarget: TextView = itemView.findViewById(R.id.goalTarget)
        private val latestValue: TextView = itemView.findViewById(R.id.goalLatestValue)
        private val latestLabel: TextView = itemView.findViewById(R.id.latestLabel)
        private val materialUpdated: TextView = itemView.findViewById(R.id.goalUpdated)
        private val addLogButton: ImageView = itemView.findViewById(R.id.addLogButton)

        fun bind(item: MaterialDryingGoalItem) {
            val context = itemView.context
            materialName.text = item.name

            // Goal value — show integer if whole number, otherwise one decimal
            materialTarget.text = item.targetMoisture?.let { formatNumber(it) }
                ?: context.getString(R.string.rocketdry_material_no_data)

            // Latest value — show integer if whole number
            latestValue.text = item.latestReading?.let { formatNumber(it) }
                ?: context.getString(R.string.rocketdry_material_no_data)

            // Color the latest value + label based on drying status
            val statusColor = statusColorRes(item.dryingStatus)
            if (statusColor != null && item.latestReading != null) {
                val color = ContextCompat.getColor(context, statusColor)
                latestValue.setTextColor(color)
                latestLabel.setTextColor(color)
            } else {
                latestValue.setTextColor(ContextCompat.getColor(context, R.color.dark_text_rp))
                latestLabel.setTextColor(ContextCompat.getColor(context, R.color.light_text_rp))
            }

            // Updated timestamp
            materialUpdated.text = item.lastUpdatedLabel
                ?: context.getString(R.string.rocketdry_material_no_readings)

            itemView.setOnClickListener { onCardTapped(item) }
            addLogButton.setOnClickListener { onAddLogTapped(item) }
        }

        private fun formatNumber(value: Double): String {
            return if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                String.format("%.1f", value)
            }
        }

        private fun statusColorRes(status: DryingStatus): Int? = when (status) {
            DryingStatus.ON_TARGET -> R.color.drying_on_target
            DryingStatus.APPROACHING -> R.color.drying_approaching
            DryingStatus.IN_PROGRESS -> R.color.drying_in_progress
            DryingStatus.FAR -> R.color.drying_far
            DryingStatus.UNKNOWN -> null
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<MaterialDryingGoalItem>() {
            override fun areItemsTheSame(
                oldItem: MaterialDryingGoalItem,
                newItem: MaterialDryingGoalItem
            ): Boolean = oldItem.materialId == newItem.materialId

            override fun areContentsTheSame(
                oldItem: MaterialDryingGoalItem,
                newItem: MaterialDryingGoalItem
            ): Boolean = oldItem == newItem
        }
    }
}
