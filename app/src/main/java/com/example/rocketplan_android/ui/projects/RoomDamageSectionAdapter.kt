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
import com.google.android.material.button.MaterialButton
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
        private val filterAll: MaterialButton = itemView.findViewById(R.id.sectionFilterAll)
        private val filterGroup: MaterialButtonToggleGroup = itemView.findViewById(R.id.sectionFilterGroup)
        private val scopeAdapter = RoomScopeAdapter { item -> onScopeLineItemClick(item) }

        private var currentSection: RoomDamageSection? = null
        private var selectedCategory: String? = null
        private val filterCat1: MaterialButton = itemView.findViewById(R.id.sectionFilterCat1)
        private val filterCat2: MaterialButton = itemView.findViewById(R.id.sectionFilterCat2)
        private val filterCat3: MaterialButton = itemView.findViewById(R.id.sectionFilterCat3)

        init {
            scopeRecycler.layoutManager = LinearLayoutManager(itemView.context)
            scopeRecycler.adapter = scopeAdapter
            scopeRecycler.isNestedScrollingEnabled = false
        }

        fun bind(section: RoomDamageSection) {
            currentSection = section
            selectedCategory = null

            title.text = section.title
            noteSummary.text = section.noteSummary
            roomIcon.setImageResource(section.iconRes)

            noteCard.setOnClickListener { onAddNote(section) }
            addScopeCard.setOnClickListener { onAddScope(section) }

            setupCategoryButtons(section)
            updateScopeList()

            val hasScopes = section.scopeGroups.isNotEmpty()
            scopeRecycler.isVisible = hasScopes
            emptyScopes.isVisible = false
        }

        private fun setupCategoryButtons(section: RoomDamageSection) {
            // Get all unique categories from scope items (not group titles)
            val allCategories = section.scopeGroups
                .flatMap { it.items }
                .mapNotNull { it.category?.uppercase() }
                .distinct()

            // Show filter group if there are any scopes
            val hasScopes = section.scopeGroups.isNotEmpty()
            filterGroup.isVisible = hasScopes

            if (!hasScopes) return

            // Show all category buttons and set up click listeners
            filterAll.isVisible = true
            filterCat1.isVisible = true
            filterCat2.isVisible = true
            filterCat3.isVisible = true

            // Setup click listeners for category filtering
            filterAll.setOnClickListener {
                selectedCategory = null
                filterGroup.check(filterAll.id)
                updateButtonStyles()
                updateScopeList()
            }

            filterCat1.setOnClickListener {
                selectedCategory = "CAT 1"
                filterGroup.check(filterCat1.id)
                updateButtonStyles()
                updateScopeList()
            }

            filterCat2.setOnClickListener {
                selectedCategory = "CAT 2"
                filterGroup.check(filterCat2.id)
                updateButtonStyles()
                updateScopeList()
            }

            filterCat3.setOnClickListener {
                selectedCategory = "CAT 3"
                filterGroup.check(filterCat3.id)
                updateButtonStyles()
                updateScopeList()
            }

            // Select "All" by default
            filterGroup.check(filterAll.id)
            updateButtonStyles()
        }

        private fun updateButtonStyles() {
            val context = itemView.context
            val selectedBg = androidx.core.content.ContextCompat.getColorStateList(context, R.color.main_purple)
            val unselectedBg = androidx.core.content.ContextCompat.getColorStateList(context, android.R.color.white)
            val selectedText = androidx.core.content.ContextCompat.getColor(context, android.R.color.white)
            val unselectedText = androidx.core.content.ContextCompat.getColor(context, R.color.main_purple)
            val strokeColor = androidx.core.content.ContextCompat.getColorStateList(context, R.color.main_purple)

            listOf(
                filterAll to (selectedCategory == null),
                filterCat1 to (selectedCategory == "CAT 1"),
                filterCat2 to (selectedCategory == "CAT 2"),
                filterCat3 to (selectedCategory == "CAT 3")
            ).forEach { (button, isSelected) ->
                button.backgroundTintList = if (isSelected) selectedBg else unselectedBg
                button.setTextColor(if (isSelected) selectedText else unselectedText)
                button.strokeColor = strokeColor
            }
        }

        private fun updateScopeList() {
            val section = currentSection ?: return
            val filteredGroups = if (selectedCategory == null) {
                section.scopeGroups
            } else {
                // Filter scope items within each group by their category field
                section.scopeGroups.mapNotNull { group ->
                    val filteredItems = group.items.filter { item ->
                        item.category?.uppercase() == selectedCategory
                    }
                    if (filteredItems.isEmpty()) {
                        null
                    } else {
                        // Recalculate total for filtered items
                        val newTotal = filteredItems.mapNotNull { it.lineTotal }.takeIf { it.isNotEmpty() }?.sum()
                        group.copy(
                            items = filteredItems,
                            itemCount = filteredItems.size,
                            total = newTotal
                        )
                    }
                }
            }
            scopeAdapter.submitList(filteredGroups)
            scopeRecycler.isVisible = filteredGroups.isNotEmpty()
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
