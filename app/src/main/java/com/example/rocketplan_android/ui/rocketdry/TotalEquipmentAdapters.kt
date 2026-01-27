package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Adapter for the "Location Total" equipment type summary rows.
 */
class LocationTotalEquipmentAdapter :
    ListAdapter<EquipmentTypeSummary, LocationTotalEquipmentAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_location_total_equipment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.equipmentTypeIcon)
        private val name: TextView = itemView.findViewById(R.id.equipmentTypeName)
        private val count: TextView = itemView.findViewById(R.id.equipmentTypeCount)

        fun bind(item: EquipmentTypeSummary) {
            icon.setImageResource(item.iconRes)
            name.text = item.label
            count.text = item.count.toString()
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<EquipmentTypeSummary>() {
        override fun areItemsTheSame(old: EquipmentTypeSummary, new: EquipmentTypeSummary) =
            old.type == new.type

        override fun areContentsTheSame(old: EquipmentTypeSummary, new: EquipmentTypeSummary) =
            old == new
    }
}

/**
 * Adapter for the "Breakdown per Room" section.
 * Shows room headers with nested equipment lists.
 */
class RoomBreakdownAdapter(
    private val onIncrease: (RoomEquipmentItem) -> Unit,
    private val onDecrease: (RoomEquipmentItem) -> Unit,
    private val onStartDateClick: (RoomEquipmentItem) -> Unit,
    private val onEndDateClick: (RoomEquipmentItem) -> Unit,
    private val onDelete: (RoomEquipmentItem) -> Unit,
    private val dateFormatter: SimpleDateFormat
) : ListAdapter<RoomEquipmentBreakdown, RoomBreakdownAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room_equipment_breakdown, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val roomIcon: ImageView = itemView.findViewById(R.id.roomIcon)
        private val roomName: TextView = itemView.findViewById(R.id.roomName)
        private val equipmentRecyclerView: RecyclerView =
            itemView.findViewById(R.id.roomEquipmentRecyclerView)

        fun bind(item: RoomEquipmentBreakdown) {
            roomIcon.setImageResource(item.roomIconRes)
            roomName.text = item.roomName

            // Setup nested RecyclerView for equipment items
            val adapter = RoomEquipmentInlineAdapter(
                onIncrease = onIncrease,
                onDecrease = onDecrease,
                onStartDateClick = onStartDateClick,
                onEndDateClick = onEndDateClick,
                onDelete = onDelete,
                dateFormatter = dateFormatter
            )
            equipmentRecyclerView.layoutManager = LinearLayoutManager(itemView.context)
            equipmentRecyclerView.adapter = adapter
            adapter.submitList(item.equipment)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RoomEquipmentBreakdown>() {
        override fun areItemsTheSame(old: RoomEquipmentBreakdown, new: RoomEquipmentBreakdown) =
            old.roomId == new.roomId

        override fun areContentsTheSame(old: RoomEquipmentBreakdown, new: RoomEquipmentBreakdown) =
            old == new
    }
}

/**
 * Inline adapter for equipment items within a room breakdown.
 * Uses the same item_room_equipment.xml layout as EquipmentRoomFragment.
 */
class RoomEquipmentInlineAdapter(
    private val onIncrease: (RoomEquipmentItem) -> Unit,
    private val onDecrease: (RoomEquipmentItem) -> Unit,
    private val onStartDateClick: (RoomEquipmentItem) -> Unit,
    private val onEndDateClick: (RoomEquipmentItem) -> Unit,
    private val onDelete: (RoomEquipmentItem) -> Unit,
    private val dateFormatter: SimpleDateFormat
) : ListAdapter<RoomEquipmentItem, RoomEquipmentInlineAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room_equipment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val equipmentIcon: ImageView = itemView.findViewById(R.id.equipmentIcon)
        private val typeTitle: TextView = itemView.findViewById(R.id.equipmentTypeTitle)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteEquipmentButton)
        private val quantityMinusButton: ImageButton = itemView.findViewById(R.id.quantityMinusButton)
        private val quantityText: TextView = itemView.findViewById(R.id.equipmentQuantity)
        private val quantityPlusButton: ImageButton = itemView.findViewById(R.id.quantityPlusButton)
        private val startDateButton: MaterialButton = itemView.findViewById(R.id.startDateButton)
        private val endDateButton: MaterialButton = itemView.findViewById(R.id.endDateButton)
        private val dayCount: TextView = itemView.findViewById(R.id.equipmentDayCount)

        fun bind(item: RoomEquipmentItem) {
            equipmentIcon.setImageResource(item.iconRes)
            typeTitle.text = item.typeLabel
            quantityText.text = item.quantity.toString()
            dayCount.text = item.dayCount.toString()

            val placeholder = itemView.context.getString(R.string.equipment_room_date_placeholder)
            startDateButton.text = item.startDate?.let { dateFormatter.format(it) } ?: placeholder
            endDateButton.text = item.endDate?.let { dateFormatter.format(it) } ?: placeholder

            deleteButton.setOnClickListener { onDelete(item) }
            quantityMinusButton.setOnClickListener { onDecrease(item) }
            quantityPlusButton.setOnClickListener { onIncrease(item) }
            startDateButton.setOnClickListener { onStartDateClick(item) }
            endDateButton.setOnClickListener { onEndDateClick(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RoomEquipmentItem>() {
        override fun areItemsTheSame(old: RoomEquipmentItem, new: RoomEquipmentItem) =
            old.uuid == new.uuid

        override fun areContentsTheSame(old: RoomEquipmentItem, new: RoomEquipmentItem) =
            old == new
    }
}
