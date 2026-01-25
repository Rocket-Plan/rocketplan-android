package com.example.rocketplan_android.ui.projects

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButtonToggleGroup

class RoomDamageSectionAdapter(
    private val onAddNote: (RoomDamageSection) -> Unit,
    private val onAddScope: (RoomDamageSection) -> Unit,
    private val onScopeLineItemClick: (RoomScopeItem) -> Unit
) : ListAdapter<RoomDamageSection, RoomDamageSectionAdapter.SectionViewHolder>(diffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room_damage_section, parent, false)
        return SectionViewHolder(view, onAddNote, onAddScope, onScopeLineItemClick)
    }

    override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SectionViewHolder(
        itemView: View,
        private val onAddNote: (RoomDamageSection) -> Unit,
        private val onAddScope: (RoomDamageSection) -> Unit,
        private val onScopeLineItemClick: (RoomScopeItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val title: TextView = itemView.findViewById(R.id.sectionRoomTitle)
        private val noteSummary: TextView = itemView.findViewById(R.id.sectionNoteSummary)
        private val noteCard: View = itemView.findViewById(R.id.sectionNoteCard)
        private val addScopeCard: View = itemView.findViewById(R.id.sectionAddScopeCard)
        private val scopeRecycler: RecyclerView = itemView.findViewById(R.id.sectionScopeRecycler)
        private val emptyScopes: TextView = itemView.findViewById(R.id.sectionScopeEmptyText)
        private val roomIcon: ImageView = itemView.findViewById(R.id.sectionRoomIcon)
        private val filterGroup: MaterialButtonToggleGroup = itemView.findViewById(R.id.sectionFilterGroup)
        private val scopeAdapter = RoomScopeAdapter { item -> onScopeLineItemClick(item) }

        init {
            scopeRecycler.layoutManager = LinearLayoutManager(itemView.context)
            scopeRecycler.adapter = scopeAdapter
            scopeRecycler.isNestedScrollingEnabled = false
        }

        fun bind(section: RoomDamageSection) {
            title.text = section.title
            noteSummary.text = section.noteSummary
            roomIcon.setImageResource(section.iconRes)

            noteCard.setOnClickListener { onAddNote(section) }
            addScopeCard.setOnClickListener { onAddScope(section) }

            // Hide per-section filter - filtering is done at the room level
            filterGroup.isVisible = false

            // Show all scopes for this section
            scopeAdapter.submitList(section.scopeGroups)

            val hasScopes = section.scopeGroups.isNotEmpty()
            scopeRecycler.isVisible = hasScopes
            emptyScopes.isVisible = false
        }
    }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<RoomDamageSection>() {
            override fun areItemsTheSame(oldItem: RoomDamageSection, newItem: RoomDamageSection): Boolean {
                return oldItem.roomId == newItem.roomId
            }

            override fun areContentsTheSame(oldItem: RoomDamageSection, newItem: RoomDamageSection): Boolean {
                return oldItem == newItem
            }
        }
    }
}
