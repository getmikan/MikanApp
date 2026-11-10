package com.github.kr328.clash.design.adapter

import android.graphics.Rect
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterProfileBinding
import com.github.kr328.clash.design.databinding.AdapterSubscriptionBinding

/**
 * TV: a profile or subscription card is one focusable view with its own buttons inside it (update,
 * support, the menu with Edit, "Choose server"). The D-pad's geometric search only looks past the
 * focused view's edges, so from the card it skipped all of them (RIGHT went to the next card or the
 * header, DOWN to the next row) and a profile could not be edited or updated from its card. RIGHT
 * now enters the card's header buttons, DOWN the controls under the header, and LEFT from the first
 * header button comes back to the card. Phones never call this.
 */
internal fun wireTvProfileCardFocus(binding: AdapterProfileBinding) {
    val card = binding.rootView
    val header = card.getChildAt(0) as? ViewGroup ?: return
    wireTvCardFocus(
        card,
        header,
        listOf(binding.forceUpdateSlot, binding.pingAllView, binding.supportView, binding.menuView),
    )
}

/** The Profiles tab's subscription card: its header holds the menu, "Choose server" sits below. */
internal fun wireTvSubscriptionCardFocus(binding: AdapterSubscriptionBinding) {
    val card = binding.subscriptionCard
    val header = binding.subscriptionMenu.parent as? ViewGroup ?: return
    wireTvCardFocus(card, header, listOf(binding.subscriptionMenu))
}

private fun wireTvCardFocus(card: ViewGroup, header: ViewGroup, headerButtons: List<View>) {
    val finder = FocusFinder.getInstance()
    card.setOnKeyListener { _, keyCode, event ->
        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
        // The header's bounds in the card's coordinates (it may sit inside a padded column).
        val bounds = Rect(0, 0, header.width, header.height)
        card.offsetDescendantRectToMyCoords(header, bounds)
        val target = when (keyCode) {
            // From the header's left edge: its first button.
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                finder.findNextFocusFromRect(card, Rect(bounds.left, bounds.top, bounds.left, bounds.bottom), View.FOCUS_RIGHT)
            // From the header's bottom edge: the first control under it, if the card has one.
            KeyEvent.KEYCODE_DPAD_DOWN ->
                finder.findNextFocusFromRect(card, Rect(0, bounds.bottom, card.width, bounds.bottom), View.FOCUS_DOWN)
            else -> null
        }
        target?.requestFocus() == true
    }
    val headerKeys = View.OnKeyListener { v, keyCode, event ->
        if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT ->
                (finder.findNextFocus(header, v, View.FOCUS_LEFT) ?: card).requestFocus()
            // Past the last button: the card beside this one, searched from the card itself. A card
            // in the rightmost column has none, so the button stays put.
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                (finder.findNextFocus(header, v, View.FOCUS_RIGHT) ?: neighbourRightOf(card))?.requestFocus() ?: true
            else -> false
        }
    }
    headerButtons.forEach { it.setOnKeyListener(headerKeys) }
}

private fun neighbourRightOf(card: View): View? {
    var list: View? = card.parent as? View
    while (list != null && list !is RecyclerView) list = list.parent as? View
    val root = list as? ViewGroup ?: return null
    val next = FocusFinder.getInstance().findNextFocus(root, card, View.FOCUS_RIGHT) ?: return null
    var p: View? = next
    while (p != null) {
        if (p === card) return null
        p = p.parent as? View
    }
    return next
}
