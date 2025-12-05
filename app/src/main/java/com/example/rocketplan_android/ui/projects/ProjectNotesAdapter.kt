package com.example.rocketplan_android.ui.projects

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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

class ProjectNotesAdapter(
    private val onDeleteClicked: (NoteListItem) -> Unit
) : ListAdapter<NoteListItem, ProjectNotesAdapter.NoteViewHolder>(DIFF_CALLBACK) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_project_note, parent, false)
        return NoteViewHolder(view, onDeleteClicked)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class NoteViewHolder(
        itemView: View,
        private val onDeleteClicked: (NoteListItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val content: TextView = itemView.findViewById(R.id.noteContent)
        private val meta: TextView = itemView.findViewById(R.id.noteMeta)
        private val status: TextView = itemView.findViewById(R.id.noteSyncStatus)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.noteDeleteButton)
        private var boundItem: NoteListItem? = null

        fun bind(item: NoteListItem) {
            boundItem = item
            content.text = item.content
            meta.text = item.meta
            status.text = item.status
            status.visibility = if (item.status.isBlank()) View.GONE else View.VISIBLE
        }

        init {
            deleteButton.setOnClickListener {
                boundItem?.let(onDeleteClicked)
            }
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
