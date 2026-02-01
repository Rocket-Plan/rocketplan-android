package com.example.rocketplan_android.ui.timecard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TimecardAdapter(
    private val onItemClick: (TimecardUiItem) -> Unit
) : ListAdapter<TimecardUiItem, TimecardAdapter.TimecardViewHolder>(TimecardDiffCallback()) {

    private val dateFormatter = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimecardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_timecard, parent, false)
        return TimecardViewHolder(view)
    }

    override fun onBindViewHolder(holder: TimecardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TimecardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val statusIndicator: View = itemView.findViewById(R.id.timecardStatusIndicator)
        private val dateText: TextView = itemView.findViewById(R.id.timecardItemDate)
        private val timeInText: TextView = itemView.findViewById(R.id.timecardItemTimeIn)
        private val timeOutText: TextView = itemView.findViewById(R.id.timecardItemTimeOut)
        private val typeText: TextView = itemView.findViewById(R.id.timecardItemType)
        private val notesText: TextView = itemView.findViewById(R.id.timecardItemNotes)
        private val durationText: TextView = itemView.findViewById(R.id.timecardItemDuration)

        fun bind(item: TimecardUiItem) {
            // Status indicator color
            val indicatorColor = if (item.isActive) {
                ContextCompat.getColor(itemView.context, R.color.dark_green)
            } else {
                ContextCompat.getColor(itemView.context, R.color.main_purple)
            }
            statusIndicator.setBackgroundColor(indicatorColor)

            // Date - show "Today" or "Yesterday" if applicable
            dateText.text = formatDate(item.timeIn)

            // Time range
            timeInText.text = timeFormatter.format(item.timeIn)
            timeOutText.text = if (item.timeOut != null) {
                timeFormatter.format(item.timeOut)
            } else {
                itemView.context.getString(R.string.timecard_in_progress)
            }

            // Type
            typeText.text = item.timecardTypeName

            // Notes
            notesText.isVisible = !item.notes.isNullOrBlank()
            notesText.text = item.notes

            // Duration
            val durationSeconds = item.elapsed ?: if (item.timeOut != null) {
                (item.timeOut.time - item.timeIn.time) / 1000
            } else {
                (System.currentTimeMillis() - item.timeIn.time) / 1000
            }
            durationText.text = formatDuration(durationSeconds)

            itemView.setOnClickListener { onItemClick(item) }
        }

        private fun formatDate(date: Date): String {
            val calendar = Calendar.getInstance()
            val today = calendar.clone() as Calendar
            today.set(Calendar.HOUR_OF_DAY, 0)
            today.set(Calendar.MINUTE, 0)
            today.set(Calendar.SECOND, 0)
            today.set(Calendar.MILLISECOND, 0)

            val yesterday = today.clone() as Calendar
            yesterday.add(Calendar.DAY_OF_YEAR, -1)

            calendar.time = date

            return when {
                calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                        calendar.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ->
                    itemView.context.getString(R.string.timecard_today)
                calendar.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                        calendar.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) ->
                    itemView.context.getString(R.string.timecard_yesterday)
                else -> dateFormatter.format(date)
            }
        }

        private fun formatDuration(seconds: Long): String {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            return String.format(Locale.getDefault(), "%d:%02d", hours, minutes)
        }
    }

    class TimecardDiffCallback : DiffUtil.ItemCallback<TimecardUiItem>() {
        override fun areItemsTheSame(oldItem: TimecardUiItem, newItem: TimecardUiItem): Boolean =
            oldItem.timecardId == newItem.timecardId

        override fun areContentsTheSame(oldItem: TimecardUiItem, newItem: TimecardUiItem): Boolean =
            oldItem == newItem
    }
}
