package com.example.rocketplan_android.ui.projects

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

class ProjectsAdapter(
    private val onProjectClick: (ProjectListItem) -> Unit
) : ListAdapter<ProjectListItem, ProjectsAdapter.ProjectViewHolder>(ProjectDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_project, parent, false)
        return ProjectViewHolder(view, onProjectClick)
    }

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ProjectViewHolder(
        itemView: View,
        private val onProjectClick: (ProjectListItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val projectTitle: TextView = itemView.findViewById(R.id.projectTitle)
        private val projectCode: TextView = itemView.findViewById(R.id.projectNumber)
        private val projectAlias: TextView = itemView.findViewById(R.id.projectSubtitle)

        fun bind(project: ProjectListItem) {
            projectTitle.text = project.title
            projectCode.text = project.projectCode

            if (project.alias.isNullOrBlank()) {
                projectAlias.visibility = View.GONE
            } else {
                projectAlias.visibility = View.VISIBLE
                projectAlias.text = project.alias
            }

            itemView.setOnClickListener {
                onProjectClick(project)
            }
        }
    }

    private class ProjectDiffCallback : DiffUtil.ItemCallback<ProjectListItem>() {
        override fun areItemsTheSame(oldItem: ProjectListItem, newItem: ProjectListItem): Boolean {
            return oldItem.projectId == newItem.projectId
        }

        override fun areContentsTheSame(oldItem: ProjectListItem, newItem: ProjectListItem): Boolean {
            return oldItem == newItem
        }
    }
}
