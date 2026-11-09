package com.github.kr328.clash.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.id.UndefinedIds
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.service.branding.BrandNotificationChrome
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.SubscriptionFetchException
import com.github.kr328.clash.service.util.UpdateNotices
import com.github.kr328.clash.service.util.updateNotices
import com.github.kr328.clash.service.util.sendProfileUpdateCompleted
import com.github.kr328.clash.service.util.sendProfileUpdateFailed
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.*

class ProfileWorker : BaseService() {
    private val service: ProfileWorker
        get() = this

    private val jobs = mutableListOf<Job>()
    private var stopJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        createChannels()

        foreground()
        stopWhenIdle()
    }

    override fun onDestroy() {
        stopForeground(true)

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            Intents.ACTION_PROFILE_REQUEST_UPDATE -> {
                intent.uuid?.also {
                    val job = launch {
                        run(it)
                    }

                    enqueue(job)
                }
            }
            Intents.ACTION_PROFILE_SCHEDULE_UPDATES -> {
                val force = intent.getBooleanExtra(Intents.EXTRA_FORCE_RESCHEDULE, false)
                val job = launch {
                    ProfileReceiver.rescheduleAll(service, force)
                }

                enqueue(job)
            }
        }

        return START_NOT_STICKY
    }

    private fun enqueue(job: Job) {
        stopJob?.cancel()
        jobs.add(job)
        job.invokeOnCompletion {
            stopWhenIdle()
        }
    }

    private fun stopWhenIdle() {
        stopJob?.cancel()
        stopJob = launch {
            // Was 500ms; reduced to 150ms so the foreground service shuts itself down
            // sooner when no more profile-update jobs are queued.
            delay(150)
            jobs.removeAll { it.isCompleted }
            if (jobs.isEmpty()) {
                stopSelf()
            }
        }
    }

    private suspend fun run(uuid: UUID) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        try {
            processing(imported.name) {
                ProfileProcessor.update(this, imported.uuid, null)
            }

            completed(imported.uuid, imported.name)

            ProfileReceiver.scheduleNext(this, imported)
        } catch (e: Exception) {
            failed(imported.uuid, imported.name, e)
        }
    }

    private fun createChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    SERVICE_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_service_status)).build(),
                NotificationChannelCompat.Builder(
                    STATUS_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_process_status)).build(),
                NotificationChannelCompat.Builder(
                    RESULT_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(R.string.profile_process_result)).build()
            )
        )
    }

    private fun foreground() {
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.profile_updater))
            .setContentText(getString(R.string.running))
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForegroundCompat(R.id.nf_profile_worker, notification)
    }

    private suspend inline fun processing(name: String, block: () -> Unit) {
        if (!ServiceStore(this).subscriptionUpdateProgressNotification) {
            block()
            return
        }

        val id = UndefinedIds.next()

        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setContentTitle(getString(R.string.profile_updating))
            .setContentText(name)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(STATUS_CHANNEL)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(id, notification)
        try {
            block()
        } finally {
            withContext(NonCancellable) {
                NotificationManagerCompat.from(applicationContext)
                    .cancel(id)
            }
        }
    }

    private fun resultBuilder(id: Int, uuid: UUID): NotificationCompat.Builder {
        val intent = PendingIntent.getActivity(
            this,
            id,
            Intent().setComponent(Components.PROPERTIES_ACTIVITY).setUUID(uuid),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
        val chrome = BrandNotificationChrome.forProfile(this, uuid)

        return NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setColor(chrome.accentColor ?: getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setGroup(RESULT_CHANNEL)
            .also { builder -> chrome.logo()?.let(builder::setLargeIcon) }
    }

    private fun completed(uuid: UUID, name: String) {
        val store = ServiceStore(this)
        val notices = updateNotices(
            success = true,
            errorCode = null,
            successNotification = store.subscriptionUpdateSuccessNotification,
            errorNotifications = store.subscriptionUpdateErrorNotifications,
        )
        val id = resultIdOf(uuid)
        applyCancels(notices, id)

        if (notices.postSuccess) {
            val builder = resultBuilder(id, uuid)
                .setContentTitle(getString(R.string.update_succeeded_title))
                .setContentText(name)
            NotificationManagerCompat.from(this).notify(SUCCESS_TAG, id, builder.build())
        }

        sendProfileUpdateCompleted(uuid)
    }

    private fun applyCancels(notices: UpdateNotices, id: Int) {
        val notifications = NotificationManagerCompat.from(this)
        if (notices.cancelSuccess) notifications.cancel(SUCCESS_TAG, id)
        if (notices.cancelFailure) notifications.cancel(FAILURE_TAG, id)
    }

    /**
     * The failure notification speaks the user's language and offers the fix: the classifier's
     * English reason (with its `[E-xx]` code, see docs/errors.md) stays the machine-readable
     * broadcast payload, while the notification picks a localized title/body per code and an
     * action — Renew (expired), Support (device limit) and Retry.
     */
    private fun failed(uuid: UUID, name: String, error: Throwable) {
        // One failure notification per subscription: a newer failure replaces the older one
        // instead of piling up, and the next success removes it (see [completed]).
        val id = resultIdOf(uuid)
        val reason = error.message ?: "Unknown"
        val fetch = error as? SubscriptionFetchException
        val code = fetch?.code ?: SubscriptionFetchException.codeOf(reason)
        val store = ServiceStore(this)
        val notices = updateNotices(
            success = false,
            errorCode = code,
            successNotification = store.subscriptionUpdateSuccessNotification,
            errorNotifications = store.subscriptionUpdateErrorNotifications,
        )
        applyCancels(notices, id)

        if (!notices.postFailure) {
            sendProfileUpdateFailed(uuid, reason)
            return
        }

        val title: String
        val body: String
        when (code) {
            "E-41" -> {
                title = getString(R.string.update_failed_expired_title)
                val date = fetch?.expireAtSeconds
                    ?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000L)) }
                body = if (date != null) getString(R.string.update_failed_expired_body, date)
                else getString(R.string.update_failed_expired_body_nodate)
            }
            "E-40" -> {
                title = getString(R.string.update_failed_devices_title)
                body = getString(R.string.update_failed_devices_body)
            }
            "E-20" -> {
                title = getString(R.string.update_failed_network_title)
                body = getString(R.string.update_failed_network_body)
            }
            "E-21" -> {
                title = getString(R.string.update_failed_refused_title)
                body = fetch?.httpStatus?.let { getString(R.string.update_failed_refused_body, it) }
                    ?: getString(R.string.update_failed_refused_body_nostatus)
            }
            else -> {
                title = getString(R.string.update_failure)
                body = reason
            }
        }
        val codeLine = code?.let { getString(R.string.update_failed_code, it) }
        val expanded = listOfNotNull(body, codeLine).joinToString("\n")

        val builder = resultBuilder(id, uuid)
            .setContentTitle(title)
            .setSubText(name)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded).setSummaryText(name))

        val actionUrl = BrandNotificationChrome.actionUrl(this, uuid, fetch?.supportUrl)
        if (actionUrl != null && (code == "E-41" || code == "E-40")) {
            val label = if (code == "E-41") R.string.update_failed_action_renew else R.string.update_failed_action_support
            builder.addAction(
                0,
                getString(label),
                PendingIntent.getActivity(
                    this,
                    // Per-profile request codes: UndefinedIds are sequential, so id+1 would collide
                    // with the next result notification's content intent under FLAG_UPDATE_CURRENT.
                    "renew:$uuid".hashCode(),
                    Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
        }
        builder.addAction(
            0,
            getString(R.string.update_failed_action_retry),
            PendingIntent.getBroadcast(
                this,
                // Same request code + intent as ProfileReceiver's alarm, so both resolve to one
                // PendingIntent instead of clobbering each other.
                uuid.hashCode(),
                Intent(Intents.ACTION_PROFILE_REQUEST_UPDATE)
                    .setComponent(ProfileReceiver::class.componentName)
                    .setUUID(uuid),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
            ),
        )

        NotificationManagerCompat.from(this).notify(FAILURE_TAG, id, builder.build())

        sendProfileUpdateFailed(uuid, reason)
    }

    companion object {
        private const val SERVICE_CHANNEL = "profile_service_channel"
        private const val STATUS_CHANNEL = "profile_status_channel"
        private const val RESULT_CHANNEL = "profile_result_channel"

        // Result notifications are keyed by (tag, subscription) so each subscription has at most
        // one failure and one success entry; tags keep them apart from UndefinedIds' counter.
        private const val FAILURE_TAG = "profile_update_failure"
        private const val SUCCESS_TAG = "profile_update_success"

        private fun resultIdOf(uuid: UUID): Int = uuid.hashCode()

        /** The subscription updated fine some other way (from the app): its failure is stale. */
        fun clearFailureNotification(context: Context, uuid: UUID) {
            NotificationManagerCompat.from(context).cancel(FAILURE_TAG, resultIdOf(uuid))
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }
}
