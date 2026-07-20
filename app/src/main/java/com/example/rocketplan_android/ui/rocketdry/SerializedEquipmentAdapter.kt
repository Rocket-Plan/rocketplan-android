package com.example.rocketplan_android.ui.rocketdry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton

/** RP-FR-019 — one row in a serialized-equipment list (deployed or pool). */
data class SerializedRowUi(
    val assetId: Long,
    val title: String,
    val subtitle: String,
    val primaryLabel: String,
    val secondaryLabel: String? = null,
    val onEdit: (Long) -> Unit = {}
)

class SerializedEquipmentAdapter(
    private val onPrimary: (Long) -> Unit,
    private val onSecondary: (Long) -> Unit = {},
    private val onEdit: (Long) -> Unit = {}
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
        private val primary = itemView.findViewById<MaterialButton>(R.id.serializedPrimaryButton)
        private val secondary = itemView.findViewById<MaterialButton>(R.id.serializedSecondaryButton)
        private val titleContainer = itemView.findViewById<View>(R.id.serializedAssetTitleContainer)

        fun bind(item: SerializedRowUi) {
            title.text = item.title
            subtitle.text = item.subtitle
            subtitle.isVisible = item.subtitle.isNotBlank()
            primary.text = item.primaryLabel
            primary.setOnClickListener { onPrimary(item.assetId) }
            secondary.isVisible = item.secondaryLabel != null
            secondary.text = item.secondaryLabel
            secondary.setOnClickListener { onSecondary(item.assetId) }
            titleContainer.setOnClickListener { onEdit(item.assetId) }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SerializedRowUi>() {
        override fun areItemsTheSame(oldItem: SerializedRowUi, newItem: SerializedRowUi) =
            oldItem.assetId == newItem.assetId
        override fun areContentsTheSame(oldItem: SerializedRowUi, newItem: SerializedRowUi) =
            oldItem == newItem
    }
}
