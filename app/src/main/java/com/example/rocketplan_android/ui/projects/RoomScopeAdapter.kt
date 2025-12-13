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
import java.text.NumberFormat
import java.util.Locale

class RoomScopeAdapter(
    private val onLineItemClick: (RoomScopeItem) -> Unit
) : ListAdapter<RoomScopeGroup, RoomScopeAdapter.ScopeGroupViewHolder>(diffCallback) {

    private val expandedIds = mutableSetOf<String>()
    private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScopeGroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room_scope, parent, false)
        return ScopeGroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: ScopeGroupViewHolder, position: Int) {
        val item = getItem(position)
        val isExpanded = expandedIds.contains(item.id)
        holder.bind(item, isExpanded)
    }

    override fun submitList(list: List<RoomScopeGroup>?) {
        val currentIds = list?.map { it.id }?.toSet().orEmpty()
        expandedIds.retainAll(currentIds)
        super.submitList(list)
    }

    inner class ScopeGroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.scopeGroupTitle)
        private val total: TextView = itemView.findViewById(R.id.scopeGroupTotal)
        private val count: TextView = itemView.findViewById(R.id.scopeGroupCount)
        private val chevron: ImageView = itemView.findViewById(R.id.scopeGroupChevron)
        private val itemsRecycler: RecyclerView = itemView.findViewById(R.id.scopeLineItemsRecycler)
        private val lineItemAdapter = RoomScopeLineItemAdapter(currencyFormatter, onLineItemClick)

        init {
            itemsRecycler.layoutManager = LinearLayoutManager(itemView.context)
            itemsRecycler.adapter = lineItemAdapter
            itemsRecycler.isNestedScrollingEnabled = false

            itemView.setOnClickListener {
                val adapterPosition = bindingAdapterPosition
                if (adapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = getItem(adapterPosition)
                if (expandedIds.contains(item.id)) {
                    expandedIds.remove(item.id)
                } else {
                    expandedIds.add(item.id)
                }
                notifyItemChanged(adapterPosition)
            }
        }

        fun bind(group: RoomScopeGroup, expanded: Boolean) {
            title.text = group.title
            val totalText = group.total?.let { currencyFormatter.format(it) }.orEmpty()
            total.text = totalText
            total.isVisible = totalText.isNotEmpty()

            count.text = itemView.resources.getQuantityString(
                R.plurals.scope_group_line_items,
                group.itemCount,
                group.itemCount
            )
            itemsRecycler.isVisible = expanded
            chevron.rotation = if (expanded) 90f else 0f
            lineItemAdapter.submitList(group.items)
        }
    }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<RoomScopeGroup>() {
            override fun areItemsTheSame(oldItem: RoomScopeGroup, newItem: RoomScopeGroup): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: RoomScopeGroup, newItem: RoomScopeGroup): Boolean {
                return oldItem == newItem
            }
        }
    }
}

private class RoomScopeLineItemAdapter(
    private val currencyFormatter: NumberFormat,
    private val onClick: (RoomScopeItem) -> Unit
) : ListAdapter<RoomScopeItem, RoomScopeLineItemAdapter.LineItemViewHolder>(lineDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room_scope_line_item, parent, false)
        return LineItemViewHolder(view, currencyFormatter, onClick)
    }

    override fun onBindViewHolder(holder: LineItemViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LineItemViewHolder(
        itemView: View,
        private val currencyFormatter: NumberFormat,
        private val onClick: (RoomScopeItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.scopeLineTitle)
        private val description: TextView = itemView.findViewById(R.id.scopeLineDescription)
        private val meta: TextView = itemView.findViewById(R.id.scopeLineMeta)
        private val total: TextView = itemView.findViewById(R.id.scopeLineTotal)

        fun bind(item: RoomScopeItem) {
            val prefix = item.code?.takeIf { it.isNotBlank() }
            title.text = listOfNotNull(prefix, item.title.takeIf { it.isNotBlank() })
                .joinToString(separator = " \u2013 ")

            description.text = item.description.orEmpty()
            description.isVisible = description.text.isNotBlank()

            val metaParts = mutableListOf<String>()
            val quantityText = item.quantity?.let { formatQuantity(it) }
            val unitText = item.unit?.takeIf { it.isNotBlank() }
            if (quantityText != null || unitText != null) {
                metaParts.add(listOfNotNull(quantityText, unitText).joinToString(" "))
            }
            item.rate?.let { metaParts.add(formatCurrency(it)) }
            item.updatedOn?.takeIf { it.isNotBlank() }?.let { metaParts.add(it) }

            meta.text = metaParts.joinToString(" \u2022 ")
            meta.isVisible = meta.text.isNotBlank()

            val lineTotal = item.lineTotal ?: item.rate
            val totalText = lineTotal?.let { formatCurrency(it) }.orEmpty()
            total.text = totalText
            total.isVisible = totalText.isNotEmpty()

            itemView.setOnClickListener { onClick(item) }
        }

        private fun formatQuantity(value: Double): String {
            return if (value % 1.0 == 0.0) {
                value.toInt().toString()
            } else {
                String.format(Locale.US, "%.2f", value)
            }
        }

        private fun formatCurrency(value: Double): String = currencyFormatter.format(value)
    }

    companion object {
        private val lineDiff = object : DiffUtil.ItemCallback<RoomScopeItem>() {
            override fun areItemsTheSame(oldItem: RoomScopeItem, newItem: RoomScopeItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: RoomScopeItem, newItem: RoomScopeItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
