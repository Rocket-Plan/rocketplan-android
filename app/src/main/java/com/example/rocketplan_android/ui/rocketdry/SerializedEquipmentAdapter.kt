package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

/** RP-FR-019 — one row in a serialized-equipment list (deployed or pool). */
data class SerializedRowUi(
    val assetId: Long,
    val title: String,
    val subtitle: String
)

/**
 * RP-FR-032: a row is a single tap target that opens the per-unit detail hub — lifecycle actions
 * (deploy/check-out/move/retire) and edit all live on [SerializedAssetDetailFragment], mirroring
 * iOS (whole-row `Button` → `SerializedAssetDetailView`). No inline row buttons.
 */
class SerializedEquipmentAdapter(
    private val onClick: (Long) -> Unit
) : ListAdapter<SerializedRowUi, SerializedEquipmentAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_serialized_equipment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<android.widget.TextView>(R.id.serializedAssetTitle)
        private val subtitle = itemView.findViewById<android.widget.TextView>(R.id.serializedAssetSubtitle)

        fun bind(item: SerializedRowUi) {
            title.text = item.title
            subtitle.text = item.subtitle
            subtitle.isVisible = item.subtitle.isNotBlank()
            itemView.setOnClickListener { onClick(item.assetId) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SerializedRowUi>() {
        override fun areItemsTheSame(oldItem: SerializedRowUi, newItem: SerializedRowUi) =
            oldItem.assetId == newItem.assetId
        override fun areContentsTheSame(oldItem: SerializedRowUi, newItem: SerializedRowUi) =
            oldItem == newItem
    }
}
