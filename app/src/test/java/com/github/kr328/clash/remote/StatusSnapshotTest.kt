package com.github.kr328.clash.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusSnapshotTest {
    @Test
    fun runningServiceIsNotReadyBeforeProfileAndTunnel() {
        assertFalse(StatusClient.StatusSnapshot(true, null, false).ready)
        assertFalse(StatusClient.StatusSnapshot(true, "profile", false).ready)
    }

    @Test
    fun readyRequiresRunningServiceAndLoadedProfile() {
        assertFalse(StatusClient.StatusSnapshot(false, "profile", true).ready)
        assertTrue(StatusClient.StatusSnapshot(true, "profile", true).ready)
    }
}
