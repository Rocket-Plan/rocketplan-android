package com.example.rocketplan_android.ui.crm

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.model.CrmCustomFieldDefinitionDto
import com.example.rocketplan_android.data.model.CrmCustomFieldValueDto

/**
 * Renders custom field definitions with their saved values into a LinearLayout container.
 */
object CrmCustomFieldRenderer {

    fun render(
        context: Context,
        container: LinearLayout,
        definitions: List<CrmCustomFieldDefinitionDto>,
        savedValues: List<CrmCustomFieldValueDto>?
    ) {
        container.removeAllViews()

        if (definitions.isEmpty()) {
            container.visibility = android.view.View.GONE
            return
        }

        container.visibility = android.view.View.VISIBLE
        val valuesById = savedValues?.associateBy { it.id } ?: emptyMap()
        val density = context.resources.displayMetrics.density

        for (definition in definitions) {
            val fieldName = definition.name ?: continue
            val fieldValue = valuesById[definition.id]?.fieldValue
                ?.takeIf { it.isNotBlank() } ?: "\u2014"

            val labelView = TextView(context).apply {
                text = fieldName
                setTextColor(ContextCompat.getColor(context, R.color.light_text_rp))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (16 * density).toInt()
                }
            }

            val valueView = TextView(context).apply {
                text = fieldValue
                setTextColor(ContextCompat.getColor(context, R.color.dark_text_rp))
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                }
            }

            container.addView(labelView)
            container.addView(valueView)
        }
    }
}
