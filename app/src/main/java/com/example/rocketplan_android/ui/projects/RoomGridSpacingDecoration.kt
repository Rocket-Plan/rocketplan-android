package com.example.rocketplan_android.ui.projects

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class RoomGridSpacingDecoration(
    private val spanCount: Int,
    private val spacing: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        val layoutManager = parent.layoutManager as? GridLayoutManager ?: return
        val spanSize = layoutManager.spanSizeLookup.getSpanSize(position)
        if (spanSize == spanCount) {
            outRect.left = 0
            outRect.right = 0
            outRect.top = spacing
            return
        }

        val column = layoutManager.spanSizeLookup.getSpanIndex(position, spanCount)
        outRect.left = spacing / 2
        outRect.right = spacing / 2
        if (column == 0) {
            outRect.left = spacing
        } else if (column == spanCount - 1) {
            outRect.right = spacing
        }
        outRect.top = spacing
    }
}
