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
        private val projectNumber: TextView = itemView.findViewById(R.id.projectNumber)
        private val projectSubtitle: TextView = itemView.findViewById(R.id.projectSubtitle)

        fun bind(project: ProjectListItem) {
            projectTitle.text = project.title
            projectNumber.text = project.projectNumber

            if (project.subtitle.isNullOrBlank()) {
                projectSubtitle.visibility = View.GONE
            } else {
                projectSubtitle.visibility = View.VISIBLE
                projectSubtitle.text = project.subtitle
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
