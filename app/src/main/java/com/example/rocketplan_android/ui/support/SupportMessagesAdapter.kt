package com.example.rocketplan_android.ui.support

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.local.SyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SupportMessageItem(
    val id: Long,
    val body: String,
    val senderType: String,
    val createdAt: Date,
    val syncStatus: SyncStatus
)

class SupportMessagesAdapter : ListAdapter<SupportMessageItem, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).senderType == "user") VIEW_TYPE_USER else VIEW_TYPE_ADMIN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_support_message_user, parent, false)
            UserMessageViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_support_message_admin, parent, false)
            AdminMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is UserMessageViewHolder -> holder.bind(item)
            is AdminMessageViewHolder -> holder.bind(item)
        }
    }

    inner class UserMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageBody: TextView = itemView.findViewById(R.id.messageBody)
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        private val syncIndicator: ProgressBar = itemView.findViewById(R.id.syncIndicator)

        fun bind(item: SupportMessageItem) {
            messageBody.text = item.body
            timestampText.text = formatTime(item.createdAt)
            syncIndicator.visibility = when (item.syncStatus) {
                SyncStatus.PENDING -> View.VISIBLE
                else -> View.GONE
            }
        }
    }

    inner class AdminMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageBody: TextView = itemView.findViewById(R.id.messageBody)
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)

        fun bind(item: SupportMessageItem) {
            messageBody.text = item.body
            timestampText.text = formatTime(item.createdAt)
        }
    }

    private fun formatTime(date: Date): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SupportMessageItem>() {
        override fun areItemsTheSame(oldItem: SupportMessageItem, newItem: SupportMessageItem) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SupportMessageItem, newItem: SupportMessageItem) =
            oldItem == newItem

        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_ADMIN = 1
    }
}
