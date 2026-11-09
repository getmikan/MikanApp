package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.model.AppLanguage
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.model.SubscriptionErrorNotifications
import com.github.kr328.clash.service.store.ServiceStore

class AppSettingsDesign(
    context: Context,
    uiStore: UiStore,
    srvStore: ServiceStore,
    behavior: Behavior,
    running: Boolean,
    onHideIconChange: (hide: Boolean) -> Unit,
) : Design<AppSettingsDesign.Request>(context) {
    enum class Request {
        ReCreateAllActivities,
        ApplyLanguage,
        CheckUpdates,
        RescheduleSubscriptionUpdates,
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface
        binding.header.screenTitle.text = (context as? Activity)?.title?.toString().orEmpty()

        val screen = preferenceScreen(context) {
            category(R.string.behavior)

            switch(
                value = behavior::autoRestart,
                icon = R.drawable.ic_baseline_restore,
                title = R.string.auto_restart,
                summary = R.string.allow_clash_auto_restart,
            )

            switch(
                value = uiStore::betaUpdates,
                icon = R.drawable.ic_outline_system_update,
                title = R.string.beta_updates,
                summary = R.string.beta_updates_summary,
            ) {
                // Switched on, look for a beta now rather than at the next launch.
                listener = OnChangedListener {
                    if (uiStore.betaUpdates) requests.trySend(Request.CheckUpdates)
                }
            }

            category(R.string.subscription_updates)

            switch(
                value = srvStore::subscriptionAutoUpdate,
                icon = R.drawable.ic_baseline_sync,
                title = R.string.subscription_auto_update_title,
                summary = R.string.subscription_auto_update_summary,
            ) {
                // Arm or cancel every subscription's alarm now, not at the next reboot.
                listener = OnChangedListener {
                    requests.trySend(Request.RescheduleSubscriptionUpdates)
                }
            }

            switch(
                value = uiStore::updateProfileBeforeConnect,
                icon = R.drawable.ic_baseline_update,
                title = R.string.update_before_connect_title,
                summary = R.string.update_before_connect_summary,
            )

            selectableList(
                value = srvStore::subscriptionUpdateErrorNotifications,
                values = SubscriptionErrorNotifications.values(),
                valuesText = arrayOf(
                    R.string.subscription_notify_errors_all,
                    R.string.subscription_notify_errors_important,
                    R.string.subscription_notify_errors_off,
                ),
                icon = R.drawable.ic_outline_error,
                title = R.string.subscription_notify_errors_title,
            )

            switch(
                value = srvStore::subscriptionUpdateSuccessNotification,
                icon = R.drawable.ic_outline_notifications,
                title = R.string.subscription_notify_success_title,
                summary = R.string.subscription_notify_success_summary,
            )

            switch(
                value = srvStore::subscriptionUpdateProgressNotification,
                icon = R.drawable.ic_baseline_sync,
                title = R.string.subscription_notify_progress_title,
                summary = R.string.subscription_notify_progress_summary,
            )

            switch(
                value = uiStore::subscriptionUpdateInAppMessages,
                icon = R.drawable.ic_outline_chat_bubble,
                title = R.string.subscription_in_app_messages_title,
                summary = R.string.subscription_in_app_messages_summary,
            )

            category(R.string.interface_)

            selectableList(
                value = uiStore::appLanguage,
                values = AppLanguage.values(),
                valuesText = arrayOf(
                    R.string.app_language_system,
                    R.string.app_language_en,
                    R.string.app_language_ru,
                    R.string.app_language_zh,
                ),
                icon = R.drawable.ic_baseline_language,
                title = R.string.app_language,
            ) {
                listener = OnChangedListener {
                    requests.trySend(Request.ApplyLanguage)
                }
            }

            switch(
                value = uiStore::hideAppIcon,
                icon = R.drawable.ic_baseline_hide,
                title = R.string.hide_app_icon_title,
                summary = R.string.hide_app_icon_desc,
            ) {
                listener = OnChangedListener {
                    onHideIconChange(uiStore::hideAppIcon.get())
                }
            }

            switch(
                value = uiStore::hideFromRecents,
                icon = R.drawable.ic_baseline_stack,
                title = R.string.hide_from_recents_title,
                summary = R.string.hide_from_recents_desc,
            ) {
                listener = OnChangedListener {
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            switch(
                value = uiStore::dnsHostsEnabled,
                icon = R.drawable.ic_baseline_language,
                title = R.string.dns_hosts_experimental_title,
                summary = R.string.dns_hosts_experimental_summary,
            )

            switch(
                value = uiStore::tunnelsEnabled,
                icon = R.drawable.ic_baseline_swap_horiz,
                title = R.string.tunnels_experimental_title,
                summary = R.string.tunnels_experimental_summary,
            )

            switch(
                value = uiStore::expertEnabled,
                icon = R.drawable.ic_baseline_bolt,
                title = R.string.expert_features_title,
                summary = R.string.expert_features_summary,
            )

            category(R.string.service)

            switch(
                value = srvStore::dynamicNotification,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.show_traffic,
                summary = R.string.show_traffic_summary
            ) {
                enabled = !running
            }

            switch(
                value = srvStore::allowExternalControl,
                icon = R.drawable.ic_baseline_stack,
                title = R.string.allow_external_control_title,
                summary = R.string.allow_external_control_summary,
            )
        }

        binding.content.addView(screen.root)
    }
}
