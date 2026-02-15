package com.example.rocketplan_android.ui.crm

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.CrmNoteDto
import com.example.rocketplan_android.util.DateUtils
import java.text.SimpleDateFormat
import java.util.Locale

class CrmNotesAdapter(
    private val onEditClick: (CrmNoteDto) -> Unit,
    private val onDeleteClick: (CrmNoteDto) -> Unit
) : ListAdapter<CrmNoteDto, CrmNotesAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_crm_note, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val bodyView: TextView = view.findViewById(R.id.noteBody)
        private val dateView: TextView = view.findViewById(R.id.noteDate)
        private val editButton: ImageButton = view.findViewById(R.id.editNoteButton)
        private val deleteButton: ImageButton = view.findViewById(R.id.deleteNoteButton)

        fun bind(note: CrmNoteDto) {
            bodyView.text = note.bodyText?.takeIf { it.isNotBlank() } ?: note.body ?: ""
            dateView.text = formatDate(note.dateAdded)

            editButton.setOnClickListener { onEditClick(note) }
            deleteButton.setOnClickListener { onDeleteClick(note) }
        }

        private fun formatDate(dateStr: String?): String {
            if (dateStr.isNullOrBlank()) return ""
            val date = DateUtils.parseApiDate(dateStr) ?: return dateStr.take(10)
            return SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.US).format(date)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CrmNoteDto>() {
            override fun areItemsTheSame(oldItem: CrmNoteDto, newItem: CrmNoteDto) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: CrmNoteDto, newItem: CrmNoteDto) = oldItem == newItem
        }
    }
}
