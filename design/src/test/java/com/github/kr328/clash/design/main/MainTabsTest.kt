package com.github.kr328.clash.design.main

import com.github.kr328.clash.design.MainDesign.MainTab
import kotlin.test.Test
import kotlin.test.assertEquals

class MainTabsTest {
    private val home = MainTab.Home
    private val profiles = MainTab.Profiles
    private val routing = MainTab.Routing
    private val operator = MainTab.Operator
    private val settings = MainTab.Settings

    @Test
    fun phoneWithoutBrandKeepsTheFourDefaultTabs() {
        assertEquals(
            listOf(home, profiles, routing, settings),
            mainTabsFor(brandActive = false, showOperatorTab = true, hideRouting = true, tvHidesRouting = false),
        )
    }

    @Test
    fun brandAddsOperatorAndCanReplaceRouting() {
        assertEquals(
            listOf(home, profiles, routing, operator, settings),
            mainTabsFor(brandActive = true, showOperatorTab = true, hideRouting = false, tvHidesRouting = false),
        )
        assertEquals(
            listOf(home, profiles, operator, settings),
            mainTabsFor(brandActive = true, showOperatorTab = true, hideRouting = true, tvHidesRouting = false),
        )
        assertEquals(
            listOf(home, profiles, settings),
            mainTabsFor(brandActive = true, showOperatorTab = false, hideRouting = true, tvHidesRouting = false),
        )
    }

    @Test
    fun tvWithoutAdvancedToolsDropsRoutingAndKeepsOperator() {
        assertEquals(
            listOf(home, profiles, settings),
            mainTabsFor(brandActive = false, showOperatorTab = false, hideRouting = false, tvHidesRouting = true),
        )
        assertEquals(
            listOf(home, profiles, operator, settings),
            mainTabsFor(brandActive = true, showOperatorTab = true, hideRouting = false, tvHidesRouting = true),
        )
    }
}
