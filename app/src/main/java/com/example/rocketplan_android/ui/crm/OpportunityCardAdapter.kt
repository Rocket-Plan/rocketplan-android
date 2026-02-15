package com.example.rocketplan_android.ui.crm

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rocketplan_android.R
import java.text.NumberFormat
import java.util.Locale

class OpportunityCardAdapter(
    private val onCardClick: ((OpportunityCard) -> Unit)? = null,
    private val onDeleteClick: ((OpportunityCard) -> Unit)? = null
) :
    ListAdapter<OpportunityCard, OpportunityCardAdapter.CardViewHolder>(DIFF_CALLBACK) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_opportunity_card, parent, false)
        return CardViewHolder(view, onCardClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class CardViewHolder(
        itemView: View,
        private val onCardClick: ((OpportunityCard) -> Unit)?,
        private val onDeleteClick: ((OpportunityCard) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.opportunityName)
        private val contactView: TextView = itemView.findViewById(R.id.opportunityContact)
        private val valueView: TextView = itemView.findViewById(R.id.opportunityValue)
        private val statusView: TextView = itemView.findViewById(R.id.opportunityStatus)
        private val customFieldsContainer: LinearLayout = itemView.findViewById(R.id.customFieldsContainer)
        private val menuButton: ImageView = itemView.findViewById(R.id.menuButton)

        fun bind(item: OpportunityCard) {
            itemView.setOnClickListener { onCardClick?.invoke(item) }
            menuButton.setOnClickListener { anchor ->
                val popup = PopupMenu(anchor.context, anchor)
                popup.menu.add(0, 1, 0, anchor.context.getString(R.string.delete))
                popup.setOnMenuItemClickListener { menuItem ->
                    if (menuItem.itemId == 1) {
                        onDeleteClick?.invoke(item)
                        true
                    } else false
                }
                popup.show()
            }
            itemView.setOnLongClickListener { v ->
                // Lift animation before starting drag
                val scaleUp = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.05f),
                        ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.05f),
                        ObjectAnimator.ofFloat(v, "elevation", v.elevation, 16f)
                    )
                    duration = 150
                }
                scaleUp.start()

                val clipData = ClipData.newPlainText("opportunity_id", item.id)
                val shadow = View.DragShadowBuilder(v)
                v.startDragAndDrop(clipData, shadow, v, 0)

                // Fade out the original card while dragging
                v.alpha = 0.3f
                true
            }
            // Contact name is the primary bold title; fall back to opportunity name
            contactView.text = item.contactName?.takeIf { it.isNotBlank() } ?: item.name

            // Opportunity name shown as secondary text (hide if same as contact or blank)
            val showOppName = !item.name.isNullOrBlank() && item.name != contactView.text.toString()
            nameView.text = item.name
            nameView.isVisible = showOppName

            if (item.monetaryValue != null && item.monetaryValue > 0) {
                val formatted = NumberFormat.getCurrencyInstance(Locale.US).format(item.monetaryValue)
                valueView.text = "Opportunity Value:  $formatted"
                valueView.isVisible = true
            } else {
                valueView.isVisible = false
            }

            statusView.text = item.status?.replaceFirstChar { it.uppercaseChar() }
            statusView.isVisible = !item.status.isNullOrBlank()

            // Custom fields
            customFieldsContainer.removeAllViews()
            if (item.customFields.isNotEmpty()) {
                customFieldsContainer.isVisible = true
                val density = itemView.resources.displayMetrics.density
                for ((fieldName, fieldValue) in item.customFields) {
                    val fieldView = TextView(itemView.context).apply {
                        text = "$fieldName: $fieldValue"
                        setTextColor(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.light_text_rp))
                        textSize = 11f
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        params.topMargin = (2 * density).toInt()
                        layoutParams = params
                    }
                    customFieldsContainer.addView(fieldView)
                }
            } else {
                customFieldsContainer.isVisible = false
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<OpportunityCard>() {
            override fun areItemsTheSame(oldItem: OpportunityCard, newItem: OpportunityCard): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: OpportunityCard, newItem: OpportunityCard): Boolean =
                oldItem == newItem
        }
    }
}
