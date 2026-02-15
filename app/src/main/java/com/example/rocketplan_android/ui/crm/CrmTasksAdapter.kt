package com.example.rocketplan_android.ui.crm

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.CrmTaskDto
import com.example.rocketplan_android.util.DateUtils
import java.text.SimpleDateFormat
import java.util.Locale

class CrmTasksAdapter(
    private val onCheckedChange: (CrmTaskDto, Boolean) -> Unit,
    private val onDeleteClick: (CrmTaskDto) -> Unit
) : ListAdapter<CrmTaskDto, CrmTasksAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_crm_task, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val checkbox: CheckBox = view.findViewById(R.id.taskCheckbox)
        private val titleView: TextView = view.findViewById(R.id.taskTitle)
        private val descriptionView: TextView = view.findViewById(R.id.taskDescription)
        private val dueDateView: TextView = view.findViewById(R.id.taskDueDate)
        private val deleteButton: ImageButton = view.findViewById(R.id.deleteTaskButton)

        fun bind(task: CrmTaskDto) {
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = task.isCompleted == true

            titleView.text = task.title ?: ""
            if (task.isCompleted == true) {
                titleView.paintFlags = titleView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                titleView.paintFlags = titleView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            if (!task.description.isNullOrBlank()) {
                descriptionView.text = task.description
                descriptionView.isVisible = true
            } else {
                descriptionView.isVisible = false
            }

            if (!task.dueDate.isNullOrBlank()) {
                dueDateView.text = "Due: ${formatDate(task.dueDate)}"
                dueDateView.isVisible = true
            } else {
                dueDateView.isVisible = false
            }

            checkbox.setOnCheckedChangeListener { _, isChecked ->
                onCheckedChange(task, isChecked)
            }
            deleteButton.setOnClickListener { onDeleteClick(task) }
        }

        private fun formatDate(dateStr: String?): String {
            if (dateStr.isNullOrBlank()) return ""
            val date = DateUtils.parseApiDate(dateStr) ?: return dateStr.take(10)
            return SimpleDateFormat("MMM d, yyyy", Locale.US).format(date)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CrmTaskDto>() {
            override fun areItemsTheSame(oldItem: CrmTaskDto, newItem: CrmTaskDto) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: CrmTaskDto, newItem: CrmTaskDto) = oldItem == newItem
        }
    }
}
