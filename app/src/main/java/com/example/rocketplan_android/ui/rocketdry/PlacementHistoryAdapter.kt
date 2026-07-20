package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton

/**
 * RP-FR-028 — list of a unit's placement spans. RP-FR-030 / RP-FR-031 add edit-dates + delete
 * affordances, shown ONLY on CLOSED spans (the open placement offers neither — end it via
 * check-out). Mirrors [SerializedEquipmentAdapter].
 */
class PlacementHistoryAdapter(
    private val onEditDates: (PlacementRowUi) -> Unit = {},
    private val onDelete: (PlacementRowUi) -> Unit = {}
) : ListAdapter<PlacementRowUi, PlacementHistoryAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_placement_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val room = itemView.findViewById<TextView>(R.id.placementRoom)
        private val project = itemView.findViewById<TextView>(R.id.placementProject)
        private val dateRange = itemView.findViewById<TextView>(R.id.placementDateRange)
        private val note = itemView.findViewById<TextView>(R.id.placementNote)
        private val actions = itemView.findViewById<LinearLayout>(R.id.placementActions)
        private val editDates = itemView.findViewById<MaterialButton>(R.id.placementEditDates)
        private val delete = itemView.findViewById<MaterialButton>(R.id.placementDelete)

        fun bind(item: PlacementRowUi) {
            val context = itemView.context
            room.text = item.roomName?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.serialized_history_unknown_room)
            project.text = item.projectName?.takeIf { it.isNotBlank() }.orEmpty()
            project.isVisible = !item.projectName.isNullOrBlank()
            dateRange.text = item.dateRange
            note.text = item.note.orEmpty()
            note.isVisible = !item.note.isNullOrBlank()
            // Edit/delete only on CLOSED placements — the open one is ended via check-out.
            actions.isVisible = !item.isOpen
            editDates.setOnClickListener { onEditDates(item) }
            delete.setOnClickListener { onDelete(item) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<PlacementRowUi>() {
        override fun areItemsTheSame(oldItem: PlacementRowUi, newItem: PlacementRowUi) =
            oldItem.placementId == newItem.placementId
        override fun areContentsTheSame(oldItem: PlacementRowUi, newItem: PlacementRowUi) =
            oldItem == newItem
    }
}
