package com.example.rocketplan_android.ui.components

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.card.MaterialCardView

/**
 * A MaterialCardView that forces a 1:1 aspect ratio (square),
 * using the measured width as both width and height.
 * Matches iOS room card behavior.
 */
class SquareMaterialCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
