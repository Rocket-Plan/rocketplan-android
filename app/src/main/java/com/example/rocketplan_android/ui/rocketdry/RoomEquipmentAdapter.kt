package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton

class RoomEquipmentAdapter(
    private val onIncrease: (RoomEquipmentItem) -> Unit,
    private val onDecrease: (RoomEquipmentItem) -> Unit,
    private val onStartDateClick: (RoomEquipmentItem) -> Unit,
    private val onEndDateClick: (RoomEquipmentItem) -> Unit,
    private val onDelete: (RoomEquipmentItem) -> Unit,
    private val dateFormatter: (RoomEquipmentItem) -> FormattedEquipmentDates
) : ListAdapter<RoomEquipmentItem, RoomEquipmentAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room_equipment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.equipmentIcon)
        private val title: TextView = itemView.findViewById(R.id.equipmentTypeTitle)
        private val quantity: TextView = itemView.findViewById(R.id.equipmentQuantity)
        private val minusButton: ImageButton = itemView.findViewById(R.id.quantityMinusButton)
        private val plusButton: ImageButton = itemView.findViewById(R.id.quantityPlusButton)
        private val startDate: MaterialButton = itemView.findViewById(R.id.startDateButton)
        private val endDate: MaterialButton = itemView.findViewById(R.id.endDateButton)
        private val dayCount: TextView = itemView.findViewById(R.id.equipmentDayCount)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteEquipmentButton)

        fun bind(item: RoomEquipmentItem) {
            val dates = dateFormatter(item)
            icon.setImageResource(item.iconRes)
            title.text = item.typeLabel
            quantity.text = item.quantity.toString()
            startDate.text = dates.startLabel
            endDate.text = dates.endLabel
            dayCount.text = item.dayCount.toString()

            minusButton.setOnClickListener { onDecrease(item) }
            plusButton.setOnClickListener { onIncrease(item) }
            startDate.setOnClickListener { onStartDateClick(item) }
            endDate.setOnClickListener { onEndDateClick(item) }
            deleteButton.setOnClickListener { onDelete(item) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<RoomEquipmentItem>() {
        override fun areItemsTheSame(oldItem: RoomEquipmentItem, newItem: RoomEquipmentItem): Boolean {
            return oldItem.uuid == newItem.uuid
        }

        override fun areContentsTheSame(oldItem: RoomEquipmentItem, newItem: RoomEquipmentItem): Boolean {
            return oldItem == newItem
        }
    }
}

data class FormattedEquipmentDates(
    val startLabel: String,
    val endLabel: String
)
