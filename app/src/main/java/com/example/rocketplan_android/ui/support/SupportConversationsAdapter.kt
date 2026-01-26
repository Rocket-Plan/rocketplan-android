package com.example.rocketplan_android.ui.support

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.local.SyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SupportConversationItem(
    val id: Long,
    val subject: String,
    val categoryName: String?,
    val status: String,
    val unreadCount: Int,
    val lastMessageAt: Date?,
    val syncStatus: SyncStatus
)

class SupportConversationsAdapter(
    private val onConversationClicked: (SupportConversationItem) -> Unit
) : ListAdapter<SupportConversationItem, SupportConversationsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_support_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val subjectText: TextView = itemView.findViewById(R.id.subjectText)
        private val categoryText: TextView = itemView.findViewById(R.id.categoryText)
        private val statusBadge: TextView = itemView.findViewById(R.id.statusBadge)
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        private val unreadBadge: TextView = itemView.findViewById(R.id.unreadBadge)
        private val syncIndicator: ProgressBar = itemView.findViewById(R.id.syncIndicator)

        fun bind(item: SupportConversationItem) {
            subjectText.text = item.subject
            categoryText.text = item.categoryName ?: ""

            // Status badge
            statusBadge.text = when (item.status) {
                "open" -> itemView.context.getString(R.string.support_status_open)
                "closed" -> itemView.context.getString(R.string.support_status_closed)
                else -> item.status.replaceFirstChar { it.uppercase() }
            }
            val statusColor = when (item.status) {
                "open" -> R.color.success_rp
                "closed" -> R.color.light_text_rp
                else -> R.color.light_text_rp
            }
            statusBadge.background.setTint(ContextCompat.getColor(itemView.context, statusColor))

            // Timestamp
            timestampText.text = formatRelativeTime(item.lastMessageAt)

            // Unread badge
            if (item.unreadCount > 0) {
                unreadBadge.visibility = View.VISIBLE
                unreadBadge.text = item.unreadCount.toString()
            } else {
                unreadBadge.visibility = View.GONE
            }

            // Sync indicator
            syncIndicator.visibility = when (item.syncStatus) {
                SyncStatus.PENDING -> View.VISIBLE
                else -> View.GONE
            }

            itemView.setOnClickListener { onConversationClicked(item) }
        }

        private fun formatRelativeTime(date: Date?): String {
            if (date == null) return ""
            val now = System.currentTimeMillis()
            val diff = now - date.time

            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> itemView.context.getString(R.string.just_now)
                diff < TimeUnit.HOURS.toMillis(1) -> {
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
                    itemView.context.getString(R.string.minutes_ago, minutes)
                }
                diff < TimeUnit.DAYS.toMillis(1) -> {
                    val hours = TimeUnit.MILLISECONDS.toHours(diff)
                    itemView.context.getString(R.string.hours_ago, hours)
                }
                diff < TimeUnit.DAYS.toMillis(7) -> {
                    val days = TimeUnit.MILLISECONDS.toDays(diff)
                    itemView.context.getString(R.string.days_ago, days)
                }
                else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SupportConversationItem>() {
        override fun areItemsTheSame(oldItem: SupportConversationItem, newItem: SupportConversationItem) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SupportConversationItem, newItem: SupportConversationItem) =
            oldItem == newItem
    }
}
