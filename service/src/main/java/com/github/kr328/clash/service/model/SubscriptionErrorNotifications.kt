package com.github.kr328.clash.service.model

/**
 * Which failed subscription updates get a system notification.
 *
 * - [All]: every failure, network errors included.
 * - [Important]: only failures the user has to act on: the subscription expired (E-41) or hit
 *   its device limit (E-40). Network errors and refusals usually fix themselves on the next
 *   update and are the ones that piled up in the shade.
 * - [Off]: none. The failure still reaches the app (broadcast), so an open app shows it.
 */
enum class SubscriptionErrorNotifications {
    All,
    Important,
    Off;

    fun allows(code: String?): Boolean = when (this) {
        All -> true
        Important -> code == "E-41" || code == "E-40"
        Off -> false
    }
}
