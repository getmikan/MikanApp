package com.github.kr328.clash.design.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.github.kr328.clash.common.util.MikanGroups
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.toBytesString
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.R as MaterialR

/*
 * Server groups of a mikan subscription (MikanGroups): the groups with traffic of their own on
 * the Home card and in the subscription sheet, a badge on each server of a group in the node
 * picker, and a notice there when a group ran out and its servers left the profile.
 */

/** At or below this share left a group is drawn in the error colour: it is about to run out. */
private const val LOW_SHARE_LEFT = 0.1f

/** How many groups the Home card lists before "More groups: N". */
internal const val HOME_GROUPS_MAX = 3

/** What is left of the group, short: "11.8 GiB left", "Unlimited · 3.2 GiB", "Used up"; "" for none. */
internal fun MikanGroups.Group.leftLabel(context: Context): String {
    if (exhausted) return context.getString(R.string.server_group_exhausted)
    return when (mode) {
        MikanGroups.Mode.Own -> left?.let { context.getString(R.string.server_group_left, it.toBytesString(context)) }.orEmpty()
        MikanGroups.Mode.Unlimited ->
            if (used > 0L) context.getString(R.string.server_group_unlimited_used, used.toBytesString(context))
            else context.getString(R.string.server_group_unlimited)
        MikanGroups.Mode.Main -> ""
    }
}

/** The group ran out or nearly did. */
internal val MikanGroups.Group.low: Boolean
    get() = exhausted || usedFraction?.let { 1f - it <= LOW_SHARE_LEFT } == true

private fun MikanGroups.Group.detailLine(context: Context): String? {
    if (exhausted) return context.getString(R.string.server_group_hidden)
    return when (mode) {
        MikanGroups.Mode.Own -> total?.let { all ->
            val usedOf = context.getString(R.string.server_group_used_of, used.toBytesString(context), all.toBytesString(context))
            if (extra > 0L) usedOf + " · " + context.getString(R.string.server_group_packages_left, extra.toBytesString(context)) else usedOf
        }
        MikanGroups.Mode.Unlimited -> context.getString(R.string.server_group_apart)
        MikanGroups.Mode.Main -> null
    }
}

private fun Context.groupAccent(low: Boolean): Int =
    resolveThemedColor(if (low) MaterialR.attr.colorError else MaterialR.attr.colorPrimary)

/**
 * A row per group in [container] (item_server_group), reusing the rows already there: the Home
 * card re-renders on every refresh. [compact] (Home) leaves out the line about the group and
 * "Buy more traffic"; [onBuy] is null without a page to buy on.
 */
internal fun bindServerGroupRows(
    container: LinearLayout,
    groups: List<MikanGroups.Group>,
    compact: Boolean,
    onBuy: (() -> Unit)?,
) {
    val inflater = container.context.layoutInflater
    while (container.childCount > groups.size) container.removeViewAt(container.childCount - 1)
    while (container.childCount < groups.size) {
        container.addView(inflater.inflate(R.layout.item_server_group, container, false))
    }
    groups.forEachIndexed { i, g -> bindServerGroupRow(container.getChildAt(i), g, compact, onBuy) }
}

private fun bindServerGroupRow(row: View, g: MikanGroups.Group, compact: Boolean, onBuy: (() -> Unit)?) {
    val context = row.context
    val accent = context.groupAccent(g.low)
    row.findViewById<ImageView>(R.id.server_group_icon).imageTintList = ColorStateList.valueOf(accent)
    row.findViewById<TextView>(R.id.server_group_name).text = g.name
    val value = g.leftLabel(context)
    row.findViewById<TextView>(R.id.server_group_value).apply {
        text = value
        setTextColor(if (g.low) accent else context.resolveThemedColor(MaterialR.attr.colorOnSurfaceVariant))
    }
    val fraction = g.usedFraction
    row.findViewById<LinearProgressIndicator>(R.id.server_group_progress).apply {
        visibility = if (fraction != null) View.VISIBLE else View.GONE
        if (fraction != null) {
            setIndicatorColor(accent)
            setProgressCompat((fraction * max).toInt(), false)
        }
    }
    val detail = if (compact) null else g.detailLine(context)
    row.findViewById<TextView>(R.id.server_group_detail).apply {
        visibility = if (detail != null) View.VISIBLE else View.GONE
        text = detail.orEmpty()
    }
    val buy = !compact && g.exhausted && onBuy != null
    row.findViewById<MaterialButton>(R.id.server_group_buy).apply {
        visibility = if (buy) View.VISIBLE else View.GONE
        setOnClickListener(if (buy) View.OnClickListener { onBuy?.invoke() } else null)
    }
    row.contentDescription = listOfNotNull(g.name, value.takeIf { it.isNotBlank() }, detail).joinToString(". ")
    // On Home the rows are part of the profile row, one focus stop on a TV remote and for
    // TalkBack; in the sheet each group is an item of its own.
    row.isFocusable = !compact
}

/** The badge of a node of [group] in the picker: the group, and what is left of its traffic. */
internal fun bindServerGroupBadge(badge: TextView, group: MikanGroups.Group?) {
    if (group == null) {
        badge.visibility = View.GONE
        return
    }
    val context = badge.context
    val accent = context.groupAccent(group.low)
    val left = group.leftLabel(context)
    badge.text = if (left.isBlank()) group.name else "${group.name} · $left"
    badge.setTextColor(accent)
    badge.backgroundTintList = ColorStateList.valueOf((accent and 0x00FFFFFF) or (0x24 shl 24)) // ~14% like the protocol chips
    TextViewCompat.setCompoundDrawableTintList(badge, ColorStateList.valueOf(accent))
    badge.contentDescription = context.getString(R.string.server_groups_title) + ": " + badge.text
    badge.visibility = View.VISIBLE
}

/**
 * The picker's notice of the groups that ran out ([notice] holds [text] and [buy]): their
 * servers left the profile, which the list alone cannot tell.
 */
internal fun bindServerGroupNotice(notice: View, text: TextView, buy: MaterialButton, groups: MikanGroups?, pageUrl: String?) {
    val out = groups?.withTraffic?.filter { it.exhausted }.orEmpty()
    if (out.isEmpty()) {
        notice.visibility = View.GONE
        return
    }
    val context = notice.context
    text.text = if (out.size == 1) {
        context.getString(R.string.server_group_notice_one, out.single().name)
    } else {
        context.getString(R.string.server_group_notice_many, out.joinToString(", ") { context.getString(R.string.server_group_quoted, it.name) })
    }
    buy.visibility = if (pageUrl != null) View.VISIBLE else View.GONE
    buy.setOnClickListener(if (pageUrl != null) View.OnClickListener { context.openServerGroupPage(pageUrl) } else null)
    notice.visibility = View.VISIBLE
}

/** Opens the subscription's page on the panel, where traffic for a group is bought. */
internal fun Context.openServerGroupPage(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
