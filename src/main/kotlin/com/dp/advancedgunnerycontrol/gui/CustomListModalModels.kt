package com.dp.advancedgunnerycontrol.gui

internal enum class CustomListModalScrollTarget {
    MANAGE_TAGS,
    REVIEW_CHANGES,
}

internal data class CustomListModalScrollRegion(
    val target: CustomListModalScrollTarget,
    val left: Float,
    val right: Float,
    val bottom: Float,
    val top: Float,
    val maxOffset: Int,
) {
    fun asVerticalScrollRegion(): VerticalScrollRegion<CustomListModalScrollTarget> {
        return VerticalScrollRegion(
            key = target,
            left = left,
            right = right,
            bottom = bottom,
            top = top,
            maxOffset = maxOffset,
        )
    }
}
