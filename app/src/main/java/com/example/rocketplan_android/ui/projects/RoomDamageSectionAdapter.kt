package com.example.rocketplan_android.ui.projects

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

class RoomDamageSectionAdapter(
    private val onAddNote: (RoomDamageSection) -> Unit,
    private val onAddScope: (RoomDamageSection) -> Unit,
    private val onEditRoom: (RoomDamageSection) -> Unit
) : ListAdapter<RoomDamageSection, RoomDamageSectionAdapter.SectionViewHolder>(diffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room_damage_section, parent, false)
        return SectionViewHolder(view, onAddNote, onAddScope, onEditRoom)
    }

    override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SectionViewHolder(
        itemView: View,
        private val onAddNote: (RoomDamageSection) -> Unit,
        private val onAddScope: (RoomDamageSection) -> Unit,
        private val onEditRoom: (RoomDamageSection) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val title: TextView = itemView.findViewById(R.id.sectionRoomTitle)
        private val noteSummary: TextView = itemView.findViewById(R.id.sectionNoteSummary)
        private val noteCard: View = itemView.findViewById(R.id.sectionNoteCard)
        private val addScopeCard: View = itemView.findViewById(R.id.sectionAddScopeCard)
        private val scopeRecycler: RecyclerView = itemView.findViewById(R.id.sectionScopeRecycler)
        private val emptyScopes: TextView = itemView.findViewById(R.id.sectionScopeEmptyText)
        private val editButton: ImageButton = itemView.findViewById(R.id.sectionEditButton)
        private val filterAll: MaterialButton = itemView.findViewById(R.id.sectionFilterAll)
        private val filterGroup: MaterialButtonToggleGroup = itemView.findViewById(R.id.sectionFilterGroup)
        private val scopeAdapter = RoomScopeAdapter()

        init {
            scopeRecycler.layoutManager = LinearLayoutManager(itemView.context)
            scopeRecycler.adapter = scopeAdapter
            scopeRecycler.isNestedScrollingEnabled = false
        }

        fun bind(section: RoomDamageSection) {
            title.text = section.title
            noteSummary.text = section.noteSummary

            noteCard.setOnClickListener { onAddNote(section) }
            addScopeCard.setOnClickListener { onAddScope(section) }
            editButton.setOnClickListener { onEditRoom(section) }
            filterGroup.check(filterAll.id)

            scopeAdapter.submitList(section.scopeGroups)
            val hasScopes = section.scopeGroups.isNotEmpty()
            scopeRecycler.isVisible = hasScopes
            emptyScopes.isVisible = !hasScopes
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
