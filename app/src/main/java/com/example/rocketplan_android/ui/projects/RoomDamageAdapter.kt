package com.example.rocketplan_android.ui.projects

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.chip.Chip
import java.util.Locale

class RoomDamageAdapter :
    ListAdapter<RoomDamageItem, RoomDamageAdapter.RoomDamageViewHolder>(diffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomDamageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room_damage, parent, false)
        return RoomDamageViewHolder(view)
    }

    override fun onBindViewHolder(holder: RoomDamageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class RoomDamageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val severityChip: Chip = itemView.findViewById(R.id.damageSeverityChip)
        private val updatedLabel: TextView = itemView.findViewById(R.id.damageUpdatedLabel)
        private val title: TextView = itemView.findViewById(R.id.damageTitle)
        private val description: TextView = itemView.findViewById(R.id.damageDescription)

        fun bind(item: RoomDamageItem) {
            val context = itemView.context
            val severityStyle = resolveSeverityStyle(context, item.severity)
            severityChip.text = severityStyle.label
            severityChip.chipBackgroundColor = ColorStateList.valueOf(
                ContextCompat.getColor(context, severityStyle.backgroundColorRes)
            )
            severityChip.setTextColor(ContextCompat.getColor(context, severityStyle.textColorRes))

            title.text = item.title
            val descriptionText = item.description?.trim().orEmpty()
            description.text = descriptionText
            description.visibility = if (descriptionText.isBlank()) View.GONE else View.VISIBLE

            val updatedText = item.updatedOn
            if (updatedText.isNullOrBlank()) {
                updatedLabel.visibility = View.GONE
            } else {
                updatedLabel.text = context.getString(R.string.room_damage_updated_format, updatedText)
                updatedLabel.visibility = View.VISIBLE
            }
        }

        private fun resolveSeverityStyle(context: android.content.Context, severity: String?): SeverityStyle {
            val normalized = severity?.trim()?.lowercase(Locale.US)
            return when (normalized) {
                "low", "minor" -> SeverityStyle(
                    label = context.getString(R.string.severity_low),
                    backgroundColorRes = R.color.severity_low_bg,
                    textColorRes = R.color.dark_green
                )
                "medium", "moderate" -> SeverityStyle(
                    label = context.getString(R.string.severity_medium),
                    backgroundColorRes = R.color.severity_medium_bg,
                    textColorRes = R.color.secondary_yellow
                )
                "high", "major" -> SeverityStyle(
                    label = context.getString(R.string.severity_high),
                    backgroundColorRes = R.color.error_fill,
                    textColorRes = R.color.dark_red
                )
                else -> SeverityStyle(
                    label = severity?.replaceFirstChar { it.titlecase(Locale.US) }
                        ?: context.getString(R.string.room_damage_severity_unknown),
                    backgroundColorRes = R.color.light_background,
                    textColorRes = R.color.light_text_rp
                )
            }
        }
    }

    data class SeverityStyle(
        val label: String,
        val backgroundColorRes: Int,
        val textColorRes: Int
    )

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<RoomDamageItem>() {
            override fun areItemsTheSame(oldItem: RoomDamageItem, newItem: RoomDamageItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: RoomDamageItem, newItem: RoomDamageItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
