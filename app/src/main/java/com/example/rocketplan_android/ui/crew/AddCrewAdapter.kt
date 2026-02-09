package com.example.rocketplan_android.ui.crew

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class AddCrewAdapter(
    private val onToggle: (Long) -> Unit
) : ListAdapter<CompanyEmployee, AddCrewAdapter.AddCrewViewHolder>(AddCrewDiffCallback()) {

    var selectedIds: Set<Long> = emptySet()
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AddCrewViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_add_crew, parent, false)
        return AddCrewViewHolder(view)
    }

    override fun onBindViewHolder(holder: AddCrewViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AddCrewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkbox: CheckBox = itemView.findViewById(R.id.addCrewCheckbox)
        private val nameText: TextView = itemView.findViewById(R.id.addCrewName)
        private val emailText: TextView = itemView.findViewById(R.id.addCrewEmail)

        fun bind(employee: CompanyEmployee) {
            nameText.text = employee.name
            emailText.text = employee.email
            checkbox.isChecked = selectedIds.contains(employee.userId)

            checkbox.setOnClickListener { onToggle(employee.userId) }
            itemView.setOnClickListener { onToggle(employee.userId) }
        }
    }

    class AddCrewDiffCallback : DiffUtil.ItemCallback<CompanyEmployee>() {
        override fun areItemsTheSame(oldItem: CompanyEmployee, newItem: CompanyEmployee): Boolean =
            oldItem.userId == newItem.userId

        override fun areContentsTheSame(oldItem: CompanyEmployee, newItem: CompanyEmployee): Boolean =
            oldItem == newItem
    }
}
