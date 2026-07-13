package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import java.text.SimpleDateFormat
import java.util.Locale

class MovementHistoryAdapter : ListAdapter<MovementHistoryItem, MovementHistoryAdapter.ViewHolder>(DiffCallback) {

    private val dateFormatter = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    private val dateOnlyFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_equipment_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val quantityText: TextView = itemView.findViewById(R.id.historyQuantity)
        private val dateText: TextView = itemView.findViewById(R.id.historyDate)
        private val noteText: TextView = itemView.findViewById(R.id.historyNote)
        private val roomsText: TextView = itemView.findViewById(R.id.historyRooms)

        fun bind(item: MovementHistoryItem) {
            val context = itemView.context
            val qty = item.quantity ?: 1
            quantityText.text = context.getString(R.string.equipment_room_history_units, qty)

            val dateStr = item.movedAt?.let {
                try {
                    dateFormatter.format(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(it))
                } catch (e: Exception) {
                    it.take(19).replace("T", " ")
                }
            } ?: ""
            dateText.text = dateStr

            if (item.note.isNullOrBlank()) {
                noteText.visibility = View.GONE
            } else {
                noteText.visibility = View.VISIBLE
                noteText.text = item.note
            }

            val fromRoom = item.fromRoomId ?: "?"
            val toRoom = item.toRoomId ?: "?"
            roomsText.text = "Room $fromRoom → Room $toRoom"
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<MovementHistoryItem>() {
        override fun areItemsTheSame(oldItem: MovementHistoryItem, newItem: MovementHistoryItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MovementHistoryItem, newItem: MovementHistoryItem): Boolean {
            return oldItem == newItem
        }
    }
}