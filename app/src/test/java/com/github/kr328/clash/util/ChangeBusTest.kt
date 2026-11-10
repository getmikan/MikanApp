package com.github.kr328.clash.util

import com.github.kr328.clash.core.model.TunnelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChangeBusTest {
    @Test
    fun listenersHearWhoCausedTheChangeUntilRemoved() {
        val heard = mutableListOf<Pair<ChangeBus.Change, Boolean>>()
        val listener = ChangeBus.Listener { change, remote -> heard += change to remote }
        ChangeBus.addListener(listener)
        ChangeBus.addListener(listener)
        try {
            ChangeBus.emit(ChangeBus.Change.Mode, remote = true)
            ChangeBus.emit(ChangeBus.Change.Proxies)
        } finally {
            ChangeBus.removeListener(listener)
        }
        ChangeBus.emit(ChangeBus.Change.Power, remote = true)
        assertEquals(listOf(ChangeBus.Change.Mode to true, ChangeBus.Change.Proxies to false), heard)
    }

    @Test
    fun aFailingListenerDoesNotStopTheOthers() {
        var reached = false
        val failing = ChangeBus.Listener { _, _ -> throw IllegalStateException("boom") }
        val next = ChangeBus.Listener { _, _ -> reached = true }
        ChangeBus.addListener(failing)
        ChangeBus.addListener(next)
        try {
            ChangeBus.emit(ChangeBus.Change.Settings)
        } finally {
            ChangeBus.removeListener(failing)
            ChangeBus.removeListener(next)
        }
        assertEquals(true, reached)
    }

    @Test
    fun theAppOffersRuleAndGlobalOnly() {
        assertEquals(TunnelState.Mode.Rule, TunnelModeApplier.normalize(TunnelState.Mode.Direct))
        assertEquals(TunnelState.Mode.Global, TunnelModeApplier.normalize(TunnelState.Mode.Global))
        assertEquals(TunnelState.Mode.Rule, TunnelModeApplier.parse("Direct"))
        assertEquals(TunnelState.Mode.Global, TunnelModeApplier.parse("Global"))
        assertNull(TunnelModeApplier.parse("Script"))
    }
}
