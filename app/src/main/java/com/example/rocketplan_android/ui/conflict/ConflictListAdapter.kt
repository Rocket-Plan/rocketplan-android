package com.example.rocketplan_android.ui.conflict

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.repository.ConflictItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ConflictListAdapter(
    private val onItemClick: (ConflictItem) -> Unit
) : ListAdapter<ConflictItem, ConflictListAdapter.ConflictViewHolder>(ConflictDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConflictViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conflict, parent, false)
        return ConflictViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: ConflictViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ConflictViewHolder(
        itemView: View,
        private val onItemClick: (ConflictItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val entityIcon: ImageView = itemView.findViewById(R.id.entityIcon)
        private val entityName: TextView = itemView.findViewById(R.id.entityName)
        private val projectName: TextView = itemView.findViewById(R.id.projectName)
        private val conflictTimestamp: TextView = itemView.findViewById(R.id.conflictTimestamp)
        private val entityTypeBadge: TextView = itemView.findViewById(R.id.entityTypeBadge)

        fun bind(item: ConflictItem) {
            entityName.text = item.entityName
            projectName.text = item.projectName ?: itemView.context.getString(R.string.conflict_unknown_project)
            conflictTimestamp.text = formatRelativeTime(item.detectedAt)
            entityTypeBadge.text = item.entityType.uppercase()

            // Set icon based on entity type
            val iconRes = when (item.entityType) {
                "room" -> android.R.drawable.ic_menu_gallery
                "location" -> android.R.drawable.ic_menu_mapmode
                else -> android.R.drawable.ic_dialog_alert
            }
            entityIcon.setImageResource(iconRes)

            itemView.setOnClickListener { onItemClick(item) }
        }

        private fun formatRelativeTime(date: Date): String {
            val now = System.currentTimeMillis()
            val diff = now - date.time

            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> itemView.context.getString(R.string.conflict_time_just_now)
                diff < TimeUnit.HOURS.toMillis(1) -> {
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff).toInt()
                    itemView.context.resources.getQuantityString(
                        R.plurals.conflict_time_minutes_ago,
                        minutes,
                        minutes
                    )
                }
                diff < TimeUnit.DAYS.toMillis(1) -> {
                    val hours = TimeUnit.MILLISECONDS.toHours(diff).toInt()
                    itemView.context.resources.getQuantityString(
                        R.plurals.conflict_time_hours_ago,
                        hours,
                        hours
                    )
                }
                diff < TimeUnit.DAYS.toMillis(7) -> {
                    val days = TimeUnit.MILLISECONDS.toDays(diff).toInt()
                    itemView.context.resources.getQuantityString(
                        R.plurals.conflict_time_days_ago,
                        days,
                        days
                    )
                }
                else -> {
                    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                    itemView.context.getString(R.string.conflict_time_detected_on, dateFormat.format(date))
                }
            }
        }
    }

    class ConflictDiffCallback : DiffUtil.ItemCallback<ConflictItem>() {
        override fun areItemsTheSame(oldItem: ConflictItem, newItem: ConflictItem): Boolean {
            return oldItem.conflictId == newItem.conflictId
        }

        override fun areContentsTheSame(oldItem: ConflictItem, newItem: ConflictItem): Boolean {
            return oldItem == newItem
        }
    }
}
