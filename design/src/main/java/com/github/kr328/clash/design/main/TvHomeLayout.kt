package com.github.kr328.clash.design.main

import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.tvFocusRingInside

/*
 * TV-only arrangement of the main screen's pages. A 1080p TV is ~960x540dp, so the phone column
 * (power orb above the card) pushed the Profile and Node rows off the screen. On TV the orb and
 * the card sit side by side instead, and the D-pad moves between them predictably. Phones never
 * call any of this.
 */

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Home: the power orb on the left, the routing card on the right, the announcement under both. */
internal fun DesignMainBinding.applyTvHomeLayout() {
    val content = mainHomeActiveContent
    val power = powerStatusGroup
    val card = mainRoutingCard
    if (power.parent !== content || card.parent !== content) return

    // The announcement card sits between the orb and the card on the phone; on TV it stays a
    // full-width strip under the two columns, so it moves up one level, right after them.
    val announcement = mainAnnouncementCard
    val outer = content.parent as? ViewGroup
    if (announcement.parent === content && outer != null) {
        content.removeView(announcement)
        val index = outer.indexOfChild(content) + 1
        if (outer is LinearLayout) {
            outer.addView(announcement, index, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = root.dp(12) })
        } else {
            content.addView(announcement)
        }
    }

    content.orientation = LinearLayout.HORIZONTAL
    content.gravity = Gravity.CENTER_VERTICAL
    mainHomePage.isFillViewport = true

    power.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f).apply {
        gravity = Gravity.CENTER_VERTICAL
    }
    power.gravity = Gravity.CENTER

    (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { old ->
        card.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = root.dp(8)
            marginEnd = old.marginEnd
            topMargin = 0
        }
    }

    // The rows fill the card edge to edge, so their focus ring goes inside them (outside, it
    // crossed the card's outline).
    listOf(mainCompanionRow, mainRoutingRow, mainActiveProfileRow, mainActiveNodeRow)
        .forEach { it.tvFocusRingInside() }

    // LEFT from any card row (or the Rule half of the mode toggle) goes back to the orb.
    listOf(mainCompanionRow, mainRoutingRow, mainActiveProfileRow, mainActiveNodeRow, mainModeRule)
        .forEach { it.nextFocusLeftId = mainPowerCard.id }
    // DOWN from the orb and from the card's last row: the announcement strip when there is one,
    // otherwise stay put (nothing else sits below on TV now that the nav is a rail on the left).
    // The page's NestedScrollView resolves DOWN inside itself, so the key is taken here.
    mainPowerCard.setOnKeyListener { _, keyCode, event ->
        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> focusAnnouncementOrStay()
            KeyEvent.KEYCODE_DPAD_UP -> mainModeRule.isShown && mainModeRule.requestFocus()
            else -> false
        }
    }
    // RIGHT from the card's right edge stays put instead of jumping up to the "+" button. The
    // profile's own action buttons are linked explicitly both ways.
    listOf(mainCompanionRow, mainRoutingRow, mainActiveProfileRow, mainActiveNodeRow, mainModeGlobal)
        .forEach { it.nextFocusRightId = it.id }
    mainActiveProfileSupport.nextFocusRightId = mainActiveProfileSupport.id
    mainActiveProfileUpdate.nextFocusLeftId = mainPowerCard.id
    mainActiveProfileActions.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val support = mainActiveProfileSupport
        mainActiveProfileUpdate.nextFocusRightId =
            if (support.visibility == View.VISIBLE) support.id else mainActiveProfileUpdate.id
    }
}

private fun DesignMainBinding.focusAnnouncementOrStay(): Boolean {
    val announcement = mainAnnouncementCard
    if (announcement.isShown && announcement.isFocusable) announcement.requestFocus()
    return true
}

/**
 * TV empty Home (no subscription yet): the welcome sits in the middle of the screen, with "Add
 * subscription" and "Remote control" as two large buttons side by side (the remote is how a fresh
 * TV usually gets its first subscription).
 */
internal fun DesignMainBinding.applyTvEmptyState() {
    mainHomePage.isFillViewport = true
    val state = mainHomeEmptyState
    (state.layoutParams as? FrameLayout.LayoutParams)?.let {
        it.gravity = Gravity.CENTER
        state.layoutParams = it
    }

    val add = mainEmptyAddButton
    val remote = mainCompanionEmptyButton
    val index = state.indexOfChild(add)
    if (index < 0 || remote.parent !== state) return
    state.removeView(add)
    state.removeView(remote)
    val res = root.resources
    val width = res.getDimensionPixelSize(R.dimen.tv_empty_button_width)
    val height = res.getDimensionPixelSize(R.dimen.tv_empty_button_height)
    val row = LinearLayout(root.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
    }
    row.addView(add, LinearLayout.LayoutParams(width, height))
    row.addView(remote, LinearLayout.LayoutParams(width, height).apply { marginStart = root.dp(20) })
    state.addView(
        row,
        index,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = root.dp(32)
        },
    )
    listOf(add, remote).forEach {
        it.minWidth = 0
        it.cornerRadius = height / 2
        it.iconSize = root.dp(24)
        it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }
    remote.setIconResource(R.drawable.ic_baseline_tv)
    remote.nextFocusRightId = remote.id
}

/**
 * The tab pages keep the focused row clear of the screen's bottom edge (the overscan) instead of
 * parking it flush against it, and DOWN on a page's last row stays put instead of the default
 * search sliding sideways into the rail.
 */
internal fun DesignMainBinding.applyTvPageFocusMargins() {
    val bottomSpace = root.resources.getDimensionPixelSize(R.dimen.tv_focus_edge_margin)
    listOf(mainRoutingPage, mainOperatorPage, mainSettingsPage).forEach { page ->
        page.bottomFocusInset = bottomSpace
        page.focusDownExit = { page.findFocus() }
    }
}

/**
 * Lands the D-pad on the power orb once Home shows its active state. The first focus request at
 * startup runs before the profiles load (the empty state is still up), so on TV it is repeated
 * when the active content appears and nothing else holds focus yet.
 */
internal fun DesignMainBinding.focusPowerOrbIfIdle() {
    if (mainHomeActiveContent.visibility != View.VISIBLE) return
    val focused = root.findFocus()
    // A container (the pager's list, the page itself) holding focus is not a place the user chose.
    val idle = focused == null || focused === mainEmptyAddButton || focused is ViewGroup &&
        (focused is RecyclerView || focused === mainHomePage || focused === mainPager)
    if (!idle) return
    mainPowerCard.post { mainPowerCard.requestFocus() }
}
