package com.example.rocketplan_android.ui.esignature

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.PdfFormSubmissionDto
import java.text.SimpleDateFormat
import java.util.Locale

class ESignatureAdapter(
    private val onSubmissionClick: (PdfFormSubmissionDto) -> Unit,
    private val onSubmissionLongClick: (PdfFormSubmissionDto) -> Unit = {},
    private val onDeleteClick: (PdfFormSubmissionDto) -> Unit = {}
) : ListAdapter<PdfFormSubmissionDto, ESignatureAdapter.SubmissionViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubmissionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_form_submission, parent, false)
        return SubmissionViewHolder(view, onSubmissionClick, onSubmissionLongClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: SubmissionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SubmissionViewHolder(
        itemView: View,
        private val onSubmissionClick: (PdfFormSubmissionDto) -> Unit,
        private val onSubmissionLongClick: (PdfFormSubmissionDto) -> Unit,
        private val onDeleteClick: (PdfFormSubmissionDto) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val templateName: TextView = itemView.findViewById(R.id.templateName)
        private val statusBadge: TextView = itemView.findViewById(R.id.statusBadge)
        private val clientName: TextView = itemView.findViewById(R.id.clientName)
        private val createdDate: TextView = itemView.findViewById(R.id.createdDate)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)

        fun bind(submission: PdfFormSubmissionDto) {
            val context = itemView.context
            templateName.text = submission.template?.name ?: context.getString(R.string.esignature_default_template)
            clientName.text = submission.clientName ?: ""
            clientName.visibility = if (submission.clientName.isNullOrBlank()) View.GONE else View.VISIBLE

            val dateStr = submission.createdAt?.let { formatDate(it) } ?: ""
            createdDate.text = if (dateStr.isNotBlank()) context.getString(R.string.esignature_created_date, dateStr) else ""
            createdDate.visibility = if (dateStr.isBlank()) View.GONE else View.VISIBLE

            when (submission.status?.lowercase()) {
                "signed" -> {
                    statusBadge.text = context.getString(R.string.esignature_status_signed)
                    statusBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_green_text))
                    statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.badge_green_bg)
                    )
                }
                "signing" -> {
                    statusBadge.text = context.getString(R.string.esignature_status_signing)
                    statusBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_blue_text))
                    statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.badge_blue_bg)
                    )
                }
                else -> {
                    statusBadge.text = context.getString(R.string.esignature_status_pending)
                    statusBadge.setTextColor(ContextCompat.getColor(context, R.color.team_member_tag_dark))
                    statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.team_member_tag_bg)
                    )
                }
            }

            itemView.setOnClickListener { onSubmissionClick(submission) }
            itemView.setOnLongClickListener {
                onSubmissionLongClick(submission)
                true
            }
            deleteButton.setOnClickListener { onDeleteClick(submission) }
        }

        private fun formatDate(isoDate: String): String {
            return try {
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                val date = parser.parse(isoDate.take(19)) ?: return isoDate
                val formatter = SimpleDateFormat("MMM d, yyyy", Locale.US)
                formatter.format(date)
            } catch (_: Exception) {
                isoDate.take(10)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<PdfFormSubmissionDto>() {
        override fun areItemsTheSame(oldItem: PdfFormSubmissionDto, newItem: PdfFormSubmissionDto): Boolean {
            // Prefer uuid as stable key since id can be null for freshly-created submissions
            return if (oldItem.uuid != null && newItem.uuid != null) {
                oldItem.uuid == newItem.uuid
            } else {
                oldItem.id == newItem.id
            }
        }

        override fun areContentsTheSame(oldItem: PdfFormSubmissionDto, newItem: PdfFormSubmissionDto): Boolean {
            return oldItem == newItem
        }
    }
}
