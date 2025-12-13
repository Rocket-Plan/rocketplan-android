package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class MaterialGoalsAdapter(
    private val onAddLog: (MaterialDryingGoalItem) -> Unit
) : ListAdapter<MaterialDryingGoalItem, MaterialGoalsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_material_goal, parent, false)
        return ViewHolder(view, onAddLog)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onAddLog: (MaterialDryingGoalItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val materialName: TextView = itemView.findViewById(R.id.goalName)
        private val materialTarget: TextView = itemView.findViewById(R.id.goalTarget)
        private val materialUpdated: TextView = itemView.findViewById(R.id.goalUpdated)
        private val materialLogs: TextView = itemView.findViewById(R.id.goalLogsCount)
        private val materialIcon: ImageView = itemView.findViewById(R.id.goalIcon)

        fun bind(item: MaterialDryingGoalItem) {
            val context = itemView.context
            materialName.text = item.name
            materialTarget.text = item.targetMoisture?.let {
                context.getString(R.string.rocketdry_goal_target_value, it)
            } ?: context.getString(R.string.rocketdry_material_target_missing)
            materialUpdated.text = if (item.latestReading != null && item.lastUpdatedLabel != null) {
                context.getString(
                    R.string.rocketdry_material_latest_reading,
                    item.latestReading,
                    item.lastUpdatedLabel
                )
            } else {
                context.getString(R.string.rocketdry_material_no_readings)
            }
            materialLogs.text = context.resources.getQuantityString(
                R.plurals.rocketdry_goal_logs_count,
                item.logsCount,
                item.logsCount
            )
            materialIcon.contentDescription = item.name
            itemView.setOnClickListener { onAddLog(item) }
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
