package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class EquipmentSummaryAdapter : RecyclerView.Adapter<EquipmentSummaryAdapter.ViewHolder>() {

    private val items: MutableList<EquipmentTypeSummary> = mutableListOf()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_equipment_summary, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun submitItems(data: List<EquipmentTypeSummary>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.equipmentTypeIcon)
        private val count: TextView = itemView.findViewById(R.id.equipmentTypeCount)
        private val label: TextView = itemView.findViewById(R.id.equipmentTypeLabel)

        fun bind(item: EquipmentTypeSummary) {
            icon.setImageResource(item.iconRes)
            count.text = item.count.toString()
            label.text = formatLabel(item.label, item.count)
        }

        private fun formatLabel(label: String, count: Int): String {
            val base = label.trim().ifBlank { itemView.context.getString(R.string.equipment) }
            val alreadyPlural = base.endsWith("s", ignoreCase = true)
            return if (count == 1 || alreadyPlural) base else "$base" + "s"
        }
    }
}

class EquipmentLevelAdapter : RecyclerView.Adapter<EquipmentLevelAdapter.ViewHolder>() {

    private val levels: MutableList<EquipmentLevel> = mutableListOf()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_equipment_level, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(levels[position])
    }

    override fun getItemCount(): Int = levels.size

    fun submitLevels(items: List<EquipmentLevel>) {
        levels.clear()
        levels.addAll(items)
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val levelName: TextView = itemView.findViewById(R.id.equipmentLevelName)
        private val roomsRecyclerView: RecyclerView = itemView.findViewById(R.id.equipmentRoomsRecyclerView)
        private val roomAdapter = EquipmentRoomAdapter()

        fun bind(level: EquipmentLevel) {
            levelName.text = level.levelName
            roomsRecyclerView.layoutManager = LinearLayoutManager(itemView.context)
            roomsRecyclerView.adapter = roomAdapter
            roomAdapter.submitRooms(level.rooms)
        }
    }
}

private class EquipmentRoomAdapter : RecyclerView.Adapter<EquipmentRoomAdapter.ViewHolder>() {

    private val rooms: MutableList<EquipmentRoomSummary> = mutableListOf()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_equipment_room, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(rooms[position])
    }

    override fun getItemCount(): Int = rooms.size

    fun submitRooms(items: List<EquipmentRoomSummary>) {
        rooms.clear()
        rooms.addAll(items)
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val roomName: TextView = itemView.findViewById(R.id.equipmentRoomName)
        private val summary: TextView = itemView.findViewById(R.id.equipmentRoomSummary)

        fun bind(item: EquipmentRoomSummary) {
            roomName.text = item.roomName
            summary.text = item.summary
        }
    }
}
