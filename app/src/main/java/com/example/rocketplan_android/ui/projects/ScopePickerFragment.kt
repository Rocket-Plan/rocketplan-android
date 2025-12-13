package com.example.rocketplan_android.ui.projects

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen Scope Picker. Reuses the catalog loading and add logic from RoomDetailViewModel,
 * but presents it as a dedicated screen instead of a modal sheet.
 */
class ScopePickerFragment : Fragment(R.layout.fragment_scope_picker) {

    private val args: ScopePickerFragmentArgs by navArgs()
    private val viewModel: RoomDetailViewModel by viewModels {
        RoomDetailViewModel.provideFactory(requireActivity().application, args.projectId, args.roomId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.scopePickerToolbar)
        val searchInput = view.findViewById<TextInputEditText>(R.id.fullScopeSearchInput)
        val chipGroup = view.findViewById<ChipGroup>(R.id.fullScopeChipGroup)
        val recycler = view.findViewById<RecyclerView>(R.id.fullScopeRecycler)
        val loading = view.findViewById<ProgressBar>(R.id.fullScopeLoading)
        val empty = view.findViewById<TextView>(R.id.fullScopeEmpty)
        val cancel = view.findViewById<MaterialButton>(R.id.fullScopeCancel)
        val custom = view.findViewById<MaterialButton>(R.id.fullScopeCustom)
        val add = view.findViewById<MaterialButton>(R.id.fullScopeAdd)

        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        recycler.layoutManager = LinearLayoutManager(requireContext())

        val selectedQuantities = mutableMapOf<Long, Double>()
        var allItems: List<ScopeCatalogItem> = emptyList()
        var displayedCategory = "All"
        val expandedGroups = mutableSetOf<String>()
        var rebuildListFn: ((String, String) -> Unit)? = null
        val categoryPriority = listOf("All", "AH CAT 1", "AH CAT 2", "AH CAT 3", "CAT1", "CAT2", "CAT3")
            .mapIndexed { index, value -> value.lowercase(Locale.US) to index }
            .toMap()
        val categoryComparator = Comparator<String> { a, b ->
            val aKey = a.lowercase(Locale.US)
            val bKey = b.lowercase(Locale.US)
            val aPriority = categoryPriority[aKey] ?: Int.MAX_VALUE
            val bPriority = categoryPriority[bKey] ?: Int.MAX_VALUE
            if (aPriority != bPriority) aPriority - bPriority else aKey.compareTo(bKey)
        }

        data class Row(val header: String? = null, val item: ScopeCatalogItem? = null)

        fun computeMeta(item: ScopeCatalogItem): String {
            val parts = mutableListOf<String>()
            item.unit.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            item.category.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            item.rate?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            return parts.joinToString(" • ")
        }

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            private val rows = mutableListOf<Row>()
            private val TYPE_HEADER = 0
            private val TYPE_ITEM = 1

            fun submitRows(newRows: List<Row>) {
                rows.clear()
                rows.addAll(newRows)
                notifyDataSetChanged()
            }

            override fun getItemViewType(position: Int): Int =
                if (rows[position].header != null) TYPE_HEADER else TYPE_ITEM

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val inflater = LayoutInflater.from(parent.context)
                return if (viewType == TYPE_HEADER) {
                    val view = inflater.inflate(R.layout.item_scope_picker_header, parent, false)
                    object : RecyclerView.ViewHolder(view) {}
                } else {
                    val view = inflater.inflate(R.layout.item_scope_picker_full_row, parent, false)
                    object : RecyclerView.ViewHolder(view) {}
                }
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = rows[position]
                if (row.header != null) {
                    val title = holder.itemView.findViewById<TextView>(R.id.scopeHeaderTitle)
                    val chevron = holder.itemView.findViewById<ImageView>(R.id.scopeHeaderChevron)
                    val expanded = expandedGroups.contains(row.header)
                    title.text = row.header
                    chevron.rotation = if (expanded) 90f else 0f
                    holder.itemView.setOnClickListener {
                        if (expanded) expandedGroups.remove(row.header) else expandedGroups.add(row.header)
                        rebuildListFn?.invoke(displayedCategory, searchInput.text?.toString().orEmpty())
                    }
                } else {
                    val item = row.item ?: return
                    val checkBox = holder.itemView.findViewById<MaterialCheckBox>(R.id.fullScopeCheckBox)
                    val title = holder.itemView.findViewById<TextView>(R.id.fullScopeTitle)
                    val meta = holder.itemView.findViewById<TextView>(R.id.fullScopeMeta)
                    val minus = holder.itemView.findViewById<MaterialButton>(R.id.fullScopeMinus)
                    val plus = holder.itemView.findViewById<MaterialButton>(R.id.fullScopePlus)
                    val qtyView = holder.itemView.findViewById<TextView>(R.id.fullScopeQuantity)

                    title.text = item.description
                    meta.text = computeMeta(item)
                    val qty = selectedQuantities[item.id] ?: 0.0
                    checkBox.isChecked = qty > 0
                    qtyView.text = if (qty > 0 && qty % 1.0 == 0.0) qty.toInt().toString() else qty.toString()

                    fun setQty(newQty: Double) {
                        val clamped = if (newQty < 0.0) 0.0 else newQty
                        if (clamped == 0.0) {
                            selectedQuantities.remove(item.id)
                        } else {
                            selectedQuantities[item.id] = clamped
                        }
                        notifyItemChanged(holder.bindingAdapterPosition)
                    }

                    holder.itemView.setOnClickListener {
                        if (selectedQuantities.containsKey(item.id)) {
                            selectedQuantities.remove(item.id)
                        } else {
                            selectedQuantities[item.id] = if (qty > 0) qty else 1.0
                        }
                        notifyItemChanged(holder.bindingAdapterPosition)
                    }

                    checkBox.setOnClickListener {
                        if (checkBox.isChecked) {
                            selectedQuantities[item.id] = if (qty > 0) qty else 1.0
                        } else {
                            selectedQuantities.remove(item.id)
                        }
                        notifyItemChanged(holder.bindingAdapterPosition)
                    }

                    minus.setOnClickListener { setQty((selectedQuantities[item.id] ?: 1.0) - 1.0) }
                    plus.setOnClickListener { setQty((selectedQuantities[item.id] ?: 0.0) + 1.0) }
                }
            }

            override fun getItemCount(): Int = rows.size
        }

        recycler.adapter = adapter

        fun sheetKey(item: ScopeCatalogItem): String =
            when {
                item.tabName.isNotBlank() -> item.tabName
                item.category.isNotBlank() -> item.category
                else -> "Other"
            }

        fun groupingKey(item: ScopeCatalogItem): String =
            when {
                item.category.isNotBlank() -> item.category
                item.tabName.isNotBlank() -> item.tabName
                else -> "Other"
            }

        fun buildCategories(items: List<ScopeCatalogItem>): List<String> =
            listOf("All") + items.map(::sheetKey).distinct().sortedWith(categoryComparator)

        fun rebuildList(category: String, query: String) {
            val categoryChanged = displayedCategory != category
            displayedCategory = category
            val q = query.trim().lowercase(Locale.US)
            if (categoryChanged && q.isBlank()) {
                expandedGroups.clear()
            }
            val filtered = allItems.filter { item ->
                val matchesCategory = category == "All" || sheetKey(item) == category
                if (!matchesCategory) return@filter false
                if (q.isBlank()) return@filter true
                val haystack = listOf(
                    item.description,
                    item.codePart1,
                    item.codePart2,
                    item.category,
                    item.tabName
                ).joinToString(" ").lowercase(Locale.US)
                haystack.contains(q)
            }

            val rows = mutableListOf<Row>()
            val grouped = filtered.groupBy(::groupingKey)
            val autoExpand = q.isNotBlank()

            grouped.keys.sortedWith(categoryComparator).forEach { group ->
                val items = grouped[group].orEmpty()
                rows.add(Row(header = group))
                val expanded = autoExpand || expandedGroups.contains(group)
                if (expanded) {
                    items.forEach { item ->
                        rows.add(Row(item = item))
                    }
                }
            }

            loading.isVisible = false
            empty.isVisible = rows.isEmpty()
            recycler.isVisible = rows.isNotEmpty()
            adapter.submitRows(rows)
        }
        rebuildListFn = ::rebuildList

        fun refreshChips(categories: List<String>) {
            chipGroup.removeAllViews()
            categories.forEachIndexed { index, cat ->
                val chip = Chip(requireContext()).apply {
                    text = cat
                    isCheckable = true
                    isChecked = index == 0
                    setOnClickListener {
                        chipGroup.check(id)
                        rebuildList(cat, searchInput.text?.toString().orEmpty())
                    }
                }
                chipGroup.addView(chip)
                if (index == 0) chipGroup.check(chip.id)
            }
        }

        searchInput.addTextChangedListener { text ->
            rebuildList(displayedCategory, text?.toString().orEmpty())
        }

        cancel.setOnClickListener { findNavController().navigateUp() }
        custom.setOnClickListener { showAddScopeDialog() }
        add.setOnClickListener {
            val selections = selectedQuantities.mapNotNull { (id, qty) ->
                val item = allItems.firstOrNull { it.id == id } ?: return@mapNotNull null
                ScopeCatalogSelection(item, qty)
            }
            if (selections.isEmpty()) {
                findNavController().navigateUp()
                return@setOnClickListener
            }
            add.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val success = runCatching { viewModel.addCatalogItems(selections) }
                    .onFailure { Log.e(TAG, "❌ Failed to add catalog scope items", it) }
                    .getOrDefault(false)
                add.isEnabled = true
                if (success) {
                    findNavController().navigateUp()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.scope_picker_add_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        loading.isVisible = true
        recycler.isVisible = false
        empty.isVisible = false
        add.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val catalog = viewModel.loadScopeCatalog()
            allItems = catalog
            if (catalog.isEmpty()) {
                loading.isVisible = false
                empty.isVisible = true
                recycler.isVisible = false
                add.isEnabled = false
            } else {
                loading.isVisible = false
                add.isEnabled = true
                val cats = buildCategories(catalog)
                expandedGroups.clear()
                refreshChips(cats)
                rebuildList("All", searchInput.text?.toString().orEmpty())
            }
        }
    }

    private fun showAddScopeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_scope_sheet, null)
        val titleLayout = dialogView.findViewById<TextInputLayout>(R.id.scopeTitleLayout)
        val titleInput = dialogView.findViewById<TextInputEditText>(R.id.scopeTitleInput)
        val descriptionInput = dialogView.findViewById<TextInputEditText>(R.id.scopeDescriptionInput)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.add_scope_sheet))
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val title = titleInput.text?.toString().orEmpty().trim()
                if (title.isBlank()) {
                    titleLayout.error = getString(R.string.add_scope_sheet_title_required)
                    return@setOnClickListener
                }
                titleLayout.error = null
                val description = descriptionInput.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }
                viewModel.addScopeItem(title, description)
                dialog.dismiss()
                findNavController().navigateUp()
            }
        }

        dialog.show()
    }

    companion object {
        private const val TAG = "ScopePickerFragment"
    }
}
