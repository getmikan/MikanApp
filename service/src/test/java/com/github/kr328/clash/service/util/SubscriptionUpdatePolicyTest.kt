package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.SubscriptionErrorNotifications
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionUpdatePolicyTest {
    private val hour = TimeUnit.HOURS.toMillis(1)
    private val now = 1_800_000_000_000L

    @Test
    fun autoUpdateOffArmsNoAlarm() {
        assertNull(nextSubscriptionUpdateDelay(false, 12 * hour, now - hour, now))
    }

    @Test
    fun autoUpdateOnCountsFromTheLastFetch() {
        assertEquals(11 * hour, nextSubscriptionUpdateDelay(true, 12 * hour, now - hour, now))
    }

    @Test
    fun anOverdueSubscriptionUpdatesAtOnce() {
        assertEquals(0L, nextSubscriptionUpdateDelay(true, 12 * hour, now - 30 * hour, now))
    }

    @Test
    fun noIntervalOrNoFetchArmsNoAlarm() {
        assertNull(nextSubscriptionUpdateDelay(true, 0, now - hour, now))
        assertNull(nextSubscriptionUpdateDelay(true, TimeUnit.MINUTES.toMillis(14), now - hour, now))
        assertNull(nextSubscriptionUpdateDelay(true, 12 * hour, 0, now))
    }

    @Test
    fun errorPolicyFiltersByCode() {
        val all = SubscriptionErrorNotifications.All
        val important = SubscriptionErrorNotifications.Important
        val off = SubscriptionErrorNotifications.Off
        for (code in listOf("E-20", "E-21", "E-41", "E-40", null)) {
            assertTrue(all.allows(code), "All allows $code")
            assertFalse(off.allows(code), "Off mutes $code")
        }
        assertTrue(important.allows("E-41"))
        assertTrue(important.allows("E-40"))
        assertFalse(important.allows("E-20"))
        assertFalse(important.allows("E-21"))
        assertFalse(important.allows(null))
    }

    @Test
    fun successRemovesTheStaleFailure() {
        val quiet = updateNotices(true, null, false, SubscriptionErrorNotifications.All)
        assertTrue(quiet.cancelFailure)
        assertTrue(quiet.cancelSuccess)
        assertFalse(quiet.postSuccess)
        assertFalse(quiet.postFailure)

        val loud = updateNotices(true, null, true, SubscriptionErrorNotifications.All)
        assertTrue(loud.cancelFailure)
        assertTrue(loud.postSuccess)
        assertFalse(loud.cancelSuccess)
    }

    @Test
    fun failureReplacesTheSuccessAndRespectsThePolicy() {
        val shown = updateNotices(false, "E-20", true, SubscriptionErrorNotifications.All)
        assertTrue(shown.postFailure)
        assertTrue(shown.cancelSuccess)
        assertFalse(shown.cancelFailure)
        assertFalse(shown.postSuccess)

        val muted = updateNotices(false, "E-20", true, SubscriptionErrorNotifications.Important)
        assertFalse(muted.postFailure)
        assertTrue(muted.cancelFailure, "a muted failure also clears an older one")
        assertTrue(muted.cancelSuccess)

        val expired = updateNotices(false, "E-41", false, SubscriptionErrorNotifications.Important)
        assertTrue(expired.postFailure)
    }
}
