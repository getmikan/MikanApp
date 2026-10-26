package com.github.kr328.clash.design.adapter

import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.Profile

/** Renders every selectable group and its nodes directly on Home. */
internal fun ProfileAdapter.renderInlineProxyList(
    list: RecyclerView,
    profile: Profile?,
): Int {
    val profileChanged = inlineProxyProfile?.uuid != profile?.uuid
    inlineProxyList = list
    inlineProxyProfile = profile
    if (profileChanged) inlineProxyAdapter = null

    if (profile == null || !profile.imported) {
        (list.adapter as? ProxyNodeAdapter)?.submitList(emptyList())
        list.adapter = null
        return 0
    }

    val adapter = inlineProxyAdapter ?: ProxyNodeAdapter(
        owner = this,
        profile = profile,
        onSelectionChanged = {
            val currentList = inlineProxyList
            val currentProfile = inlineProxyProfile
            if (currentList != null && currentProfile != null) {
                renderInlineProxyList(currentList, currentProfile)
            }
        },
        onNodeActions = { anchor: View, row: ProxyPickerRow ->
            onProxyYamlDetail(profile, row.groupName, row.proxy.name)
        },
    ).also {
        inlineProxyAdapter = it
        list.layoutManager = LinearLayoutManager(list.context)
        list.adapter = it
        list.itemAnimator = null
        if (list.itemDecorationCount == 0) {
            ContextCompat.getDrawable(list.context, R.drawable.divider_node_hairline)?.let { divider ->
                list.addItemDecoration(NodeDividerDecoration(divider))
            }
        }
    }

    val groupNames = effectiveGroupsForProfile(profile)
    if (groupNames.isEmpty()) {
        adapter.submitList(emptyList())
        return 0
    }

    val rows = buildProxyPickerRows(profile, groupNames, 0)
    val expandedGroups = inlineExpandedGroups.getOrPut(profile.uuid) {
        groupNames.toMutableSet()
    }
    expandedGroups.retainAll(groupNames.toSet())
    val items = buildList {
        groupNames.forEachIndexed { index, groupName ->
            val groupRows = rows.filter { it.groupIndex == index }
            val expanded = groupName in expandedGroups
            add(
                PickerItem.Header(
                    groupIndex = index,
                    groupName = groupName,
                    summary = groupSelectionSummary(profile, groupName),
                    count = groupRows.size,
                    expanded = expanded,
                    pinging = pingingUuid == profile.uuid && pingingGroupByUuid[profile.uuid] == groupName,
                ),
            )
            if (expanded) groupRows.forEach { add(PickerItem.Node(it)) }
        }
    }
    adapter.showGroupInSubtitle = false
    adapter.onHeaderToggle = { header ->
        if (!expandedGroups.add(header.groupName)) {
            expandedGroups.remove(header.groupName)
        }
        val currentList = inlineProxyList
        val currentProfile = inlineProxyProfile
        if (currentList != null && currentProfile != null) {
            renderInlineProxyList(currentList, currentProfile)
        }
    }
    adapter.onHeaderPing = { header ->
        val names = rows.filter { it.groupIndex == header.groupIndex }.map { it.proxy.name }
        if (names.isNotEmpty()) {
            lastPingAllAt[profile.uuid] = System.currentTimeMillis()
            pingingGroupByUuid[profile.uuid] = header.groupName
            onPingAll(profile, header.groupName, names, "")
        }
    }
    adapter.submitList(items)
    list.layoutParams = list.layoutParams.apply {
        height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    }
    list.isNestedScrollingEnabled = false
    return items.size
}
