package com.qtalk.recyclerviewfastscroller

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

class RecyclerViewFastScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    interface OnPopupTextUpdate {
        fun onChange(position: Int): String
    }

    init {
        clipChildren = true
    }

    fun updateColors(color: Int) {
        // The original widget changes the fast-scroll thumb/bubble tint here.
        // This local implementation intentionally keeps RecyclerView behavior intact.
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onViewAdded(child: android.view.View) {
        super.onViewAdded(child)
        if (child is RecyclerView) return
    }
}
