package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.SubscriptionErrorNotifications
import java.util.concurrent.TimeUnit

/** Shortest interval a subscription can be scheduled with; anything below means "no schedule". */
internal val MIN_SUBSCRIPTION_UPDATE_INTERVAL = TimeUnit.MINUTES.toMillis(15)

/**
 * Milliseconds until a subscription's next scheduled update, or null when no alarm should be
 * armed: auto-update is off, the subscription has no interval, or it was never fetched.
 * Counted from the last fetch, so a reconnect does not push the update back.
 */
internal fun nextSubscriptionUpdateDelay(
    autoUpdate: Boolean,
    intervalMillis: Long,
    lastFetchAt: Long,
    now: Long,
): Long? {
    if (!autoUpdate) return null
    if (intervalMillis < MIN_SUBSCRIPTION_UPDATE_INTERVAL) return null
    if (lastFetchAt <= 0) return null
    return (intervalMillis - (now - lastFetchAt)).coerceAtLeast(0)
}

/** What a finished background update does to its subscription's notifications. */
internal data class UpdateNotices(
    val postSuccess: Boolean,
    val postFailure: Boolean,
    val cancelSuccess: Boolean,
    val cancelFailure: Boolean,
)

/**
 * Each subscription keeps at most one result notification. A success removes the last failure
 * (it is stale now); a failure replaces the last success. A muted result also removes the old
 * entry of its own kind, so nothing outdated stays in the shade.
 */
internal fun updateNotices(
    success: Boolean,
    errorCode: String?,
    successNotification: Boolean,
    errorNotifications: SubscriptionErrorNotifications,
): UpdateNotices = if (success) {
    UpdateNotices(
        postSuccess = successNotification,
        postFailure = false,
        cancelSuccess = !successNotification,
        cancelFailure = true,
    )
} else {
    val post = errorNotifications.allows(errorCode)
    UpdateNotices(
        postSuccess = false,
        postFailure = post,
        cancelSuccess = true,
        cancelFailure = !post,
    )
}
