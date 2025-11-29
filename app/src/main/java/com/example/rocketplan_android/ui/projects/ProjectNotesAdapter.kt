package com.example.rocketplan_android.ui.projects

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R

data class NoteListItem(
    val id: String,
    val content: String,
    val meta: String,
    val status: String
)

class ProjectNotesAdapter : ListAdapter<NoteListItem, ProjectNotesAdapter.NoteViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_project_note, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val content: TextView = itemView.findViewById(R.id.noteContent)
        private val meta: TextView = itemView.findViewById(R.id.noteMeta)
        private val status: TextView = itemView.findViewById(R.id.noteSyncStatus)

        fun bind(item: NoteListItem) {
            content.text = item.content
            meta.text = item.meta
            status.text = item.status
            status.visibility = if (item.status.isBlank()) View.GONE else View.VISIBLE
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<NoteListItem>() {
            override fun areItemsTheSame(oldItem: NoteListItem, newItem: NoteListItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: NoteListItem, newItem: NoteListItem): Boolean =
                oldItem == newItem
        }
    }
}
