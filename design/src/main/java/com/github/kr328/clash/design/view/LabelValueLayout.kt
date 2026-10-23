package com.github.kr328.clash.design.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A label and its value, its two children: the value at the end of the label's line, on the
 * same baseline, while both fit; otherwise on a line of its own below, lined up with the label's
 * text (past a TextView's start drawable). Neither is ever cut short, and the label wraps only
 * when it alone is wider than the row. Children are measured at wrap_content, their margins are
 * not read, and a GONE value leaves the label alone.
 *
 * Use case: a server group's name and what is left of its traffic on the Home card, a column
 * narrow enough that with a larger font the name was cut to a few letters.
 */
class LabelValueLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val gap = (8 * resources.displayMetrics.density).roundToInt()
    private val lineGap = (2 * resources.displayMetrics.density).roundToInt()
    private var apart = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        check(childCount == 2) { "LabelValueLayout holds a label and its value" }
    }

    override fun shouldDelayChildPressedState(): Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val label = getChildAt(0)
        val value = getChildAt(1).takeIf { it.visibility != View.GONE }
        val room = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) null
        else max(0, MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        label.measure(widthWithin(room), ANY)
        value?.measure(widthWithin(room), ANY)
        apart = value != null && valueApart(room, label.measuredWidth, value.measuredWidth, gap)
        // Apart, the value starts under the label's text: that much less room for it.
        if (apart) value?.measure(widthWithin(room?.let { max(0, it - indent()) }), ANY)
        val wanted = placeLabelValue(label.measured(), value?.measured(), apart, 0, indent(), gap, lineGap)
        setMeasuredDimension(
            resolveSize(wanted.width + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(wanted.height + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val label = getChildAt(0)
        val value = getChildAt(1).takeIf { it.visibility != View.GONE }
        val width = r - l - paddingLeft - paddingRight
        val at = placeLabelValue(label.measured(), value?.measured(), apart, width, indent(), gap, lineGap)
        place(label, 0, at.labelTop, width)
        if (value != null) place(value, at.valueStart, at.valueTop, width)
    }

    /** Lays [child] out [start] from the content's start edge, mirrored right to left. */
    private fun place(child: View, start: Int, top: Int, width: Int) {
        val left = paddingLeft + if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - child.measuredWidth else start
        child.layout(left, paddingTop + top, left + child.measuredWidth, paddingTop + top + child.measuredHeight)
    }

    /** Where the label's text starts: past its start drawable. */
    private fun indent(): Int = (getChildAt(0) as? TextView)?.compoundPaddingStart ?: 0

    private fun View.measured() = Measured(measuredWidth, measuredHeight, baseline)

    private fun widthWithin(room: Int?): Int =
        if (room == null) ANY else MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST)

    private companion object {
        val ANY: Int = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    }
}

/** A child as measured: its size and its first baseline, -1 without one. */
internal data class Measured(val width: Int, val height: Int, val baseline: Int)

/** Where the label and the value go, from the content's start edge and top, and the size they take. */
internal data class LabelValuePlacement(
    val labelTop: Int,
    val valueStart: Int,
    val valueTop: Int,
    val width: Int,
    val height: Int,
)

/** The value takes a line of its own when the two do not fit side by side in [room] (null: no bound). */
internal fun valueApart(room: Int?, labelWidth: Int, valueWidth: Int, gap: Int): Boolean =
    room != null && labelWidth + gap + valueWidth > room

/**
 * Side by side, the value sits at the end of [width] (the content's, 0 while measuring) on the
 * label's baseline, never over the label; apart, it starts at [indent] below the label.
 */
internal fun placeLabelValue(
    label: Measured,
    value: Measured?,
    apart: Boolean,
    width: Int,
    indent: Int,
    gap: Int,
    lineGap: Int,
): LabelValuePlacement {
    if (value == null) return LabelValuePlacement(0, 0, 0, label.width, label.height)
    if (apart) {
        val valueTop = label.height + lineGap
        return LabelValuePlacement(0, indent, valueTop, max(label.width, indent + value.width), valueTop + value.height)
    }
    // Without a baseline on either, the two are centred on each other instead.
    val shift = if (label.baseline >= 0 && value.baseline >= 0) label.baseline - value.baseline
    else (label.height - value.height) / 2
    val labelTop = max(0, -shift)
    val valueTop = max(0, shift)
    return LabelValuePlacement(
        labelTop = labelTop,
        valueStart = max(label.width + gap, width - value.width),
        valueTop = valueTop,
        width = label.width + gap + value.width,
        height = max(labelTop + label.height, valueTop + value.height),
    )
}
