package com.github.kr328.clash.design.adapter

import com.github.kr328.clash.core.model.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyPickerServerGroupFilterTest {
    private fun row(groupIndex: Int, name: String, configIndex: Int) = ProxyPickerRow(
        groupName = "group$groupIndex",
        groupIndex = groupIndex,
        proxy = Proxy(name, name, "", Proxy.Type.Vless, -1),
        configIndex = configIndex,
        delayMs = -1,
        selected = false,
        provider = null,
    )

    @Test
    fun aServerGroupKeepsItsServersOfTheGroupShown() {
        // A server is a member of several proxy groups: the filter must not list it once per group.
        val rows = listOf(row(0, "🇺🇸 VLESS", 0), row(0, "🇳🇱 VLESS", 1), row(1, "🇺🇸 VLESS", 2), row(0, "🇺🇸 Hysteria2", 3))
        val premium = ProxyPickerFilter.ServerGroup("Premium", setOf("🇺🇸 VLESS", "🇺🇸 Hysteria2"))

        val shown = applyProxyPickerControls(rows, "", ProxyPickerSort.Config, premium, currentGroupIndex = 0, manualOrders = emptyMap())
        assertEquals(listOf("🇺🇸 VLESS", "🇺🇸 Hysteria2"), shown.map { it.proxy.name })
        assertEquals(listOf(0, 0), shown.map { it.groupIndex })

        val other = applyProxyPickerControls(rows, "", ProxyPickerSort.Config, premium, currentGroupIndex = 1, manualOrders = emptyMap())
        assertEquals(listOf(2), other.map { it.configIndex })
    }
}
