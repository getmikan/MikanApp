package com.github.kr328.clash.service

import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.clash.clashRuntime
import com.github.kr328.clash.service.clash.module.*
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.AppRoutingStore
import com.github.kr328.clash.service.util.TunStackResolver
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import com.github.kr328.clash.service.util.parseCIDR
import com.github.kr328.clash.service.util.ProxyPropertyGuard
import com.github.kr328.clash.service.util.sendClashStarted
import com.github.kr328.clash.service.util.sendClashStopped
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

class TunService : VpnService(), CoroutineScope by CoroutineScope(Dispatchers.Default) {
    private val self: TunService
        get() = this

    private var reason: String? = null

    /** A tunnel came up and the user has not asked to stop: from here on the kill switch may hold. */
    @Volatile
    private var armed = false

    /** The idle tunnel that holds traffic while the kill switch blocks; nothing ever reads it. */
    private var block: ParcelFileDescriptor? = null

    /** A runtime is starting or running, so a start command has nothing to resume. */
    @Volatile
    private var runtimeActive = false

    /** While blocked no CloseModule runs: "Disconnect" (app, tile, notification) lands here. */
    private val blockedStop = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i("Kill switch: the user turned the VPN off")
            armed = false
            releaseBlock()
            stopSelf()
        }
    }

    private val runtime = clashRuntime {
        val store = ServiceStore(self)

        val close = install(CloseModule(self))
        val tun = install(TunModule(self))
        val config = install(ConfigurationModule(self))
        val network = install(NetworkObserveModule(self))

        if (store.dynamicNotification)
            install(DynamicNotificationModule(self))
        else
            install(StaticNotificationModule(self))

        install(AppListCacheModule(self))
        install(TimeZoneModule(self))
        install(SuspendModule(self))
        install(SpeedWidgetModule(self))

        var stopRequested = false
        var cancelled = false
        try {
            config.awaitInitialLoad()
            tun.open()
            // The new tunnel replaced a kill-switch block, if there was one.
            releaseBlock()
            armed = true
            StatusProvider.serviceReady = true
            sendClashStarted()

            while (isActive) {
                val quit = select<Boolean> {
                    close.onEvent {
                        Log.i("TunService close requested by CloseModule")
                        stopRequested = true
                        true
                    }
                    config.onEvent {
                        reason = it.message
                        Log.e("TunService received fatal config event")

                        true
                    }
                    network.onEvent { n ->
                        if (Build.VERSION.SDK_INT in 22..28) @TargetApi(22) {
                            setUnderlyingNetworks(n?.let { arrayOf(it) })
                        }

                        false
                    }
                }

                if (quit) break
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Normal VPN stop cancels the runtime scope — not a failure. Rethrow so it isn't logged
            // as "Create clash runtime failed" on every stop, and structured cancellation works. (O-01)
            cancelled = true
            throw e
        } catch (e: Exception) {
            Log.e("Create clash runtime failed", e)

            reason = e.message
        } finally {
            withContext(NonCancellable) {
                // Kill switch: a session that dies unasked first swaps its tunnel for an idle one,
                // so no packet takes the real network on the way down.
                val held = armed && !stopRequested && !cancelled && store.killSwitch && holdTraffic()

                tun.close()

                // The tunnel is down at this point (runtime torn down + Clash.reset). Broadcast
                // "stopped" NOW so the UI flips to Disconnected immediately, instead of waiting for
                // onDestroy — Android delays onDestroy by up to a few seconds when a foreground
                // service is stopped right after it started (immediate connect→disconnect), which
                // otherwise leaves the dashboard stuck on "Connected". onDestroy re-sends it (the
                // broadcast is idempotent) to cover paths where the runtime never reached here.
                sendClashStopped(reason)

                runtimeActive = false
                if (!held) stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProxyPropertyGuard.clearGlobalProxyProperties()

        // Rapid Disconnect→Connect from the UI used to commit suicide here:
        // the previous instance was still inside onDestroy → cancelAndJoinBlocking,
        // so serviceRunning was true and the new instance stopSelf'd, leaving
        // the user disconnected silently. awaitServiceShutdown gives the
        // previous onDestroy up to 500ms to flip the flag back.
        if (!StatusProvider.awaitServiceShutdown()) {
            Log.w("TunService: previous instance still alive after handoff timeout, aborting")
            return stopSelf()
        }

        StatusProvider.serviceRunning = true

        StaticNotificationModule.createNotificationChannel(this)
        StaticNotificationModule.notifyLoadingNotification(this)

        runtimeActive = true
        runtime.launch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Connect in the app, the tile, or Reconnect on the notification while the kill switch
        // holds: start a new session. The idle tunnel stays until the new one replaces it.
        if (hasBlock() && !runtimeActive) {
            Log.i("Kill switch: reconnecting")
            reason = null
            runtimeActive = true
            StatusProvider.serviceRunning = true
            StaticNotificationModule.notifyLoadingNotification(this)
            runtime.launch()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        TunModule.requestStop()

        StatusProvider.serviceRunning = false

        sendClashStopped(reason)

        cancelAndJoinBlocking()

        releaseBlock()

        Log.i("TunService destroyed: ${reason ?: "successfully"}")

        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        runtime.requestGc()
    }

    @Synchronized
    private fun hasBlock(): Boolean = block != null

    /**
     * Swaps the live tunnel for an idle one with the same routes and app rules: apps stay inside
     * the VPN with nowhere to go until the user reconnects or turns the VPN off.
     */
    @Synchronized
    private fun holdTraffic(): Boolean {
        if (block == null) {
            block = runCatching { Builder().configureTunnel(ServiceStore(this)).establish() }
                .onFailure { Log.e("Kill switch: cannot hold the tunnel", it) }
                .getOrNull() ?: return false
            registerReceiverCompat(
                blockedStop,
                IntentFilter(Intents.ACTION_CLASH_REQUEST_STOP),
                Permissions.RECEIVE_SELF_BROADCASTS,
                null,
            )
        }
        Log.w("Kill switch: the VPN stopped (${reason ?: "no reason"}), traffic is blocked")
        // Not running as far as the app is concerned: Connect starts a session again (see
        // onStartCommand), and the notification says why nothing gets through.
        StatusProvider.serviceRunning = false
        notifyBlocked()
        return true
    }

    @Synchronized
    private fun releaseBlock() {
        val idle = block ?: return
        block = null
        unregisterReceiver(blockedStop)
        runCatching { idle.close() }
    }

    private fun notifyBlocked() {
        val text = reason?.takeIf { it.isNotBlank() }?.let { getString(R.string.kill_switch_blocked_reason, it) }
            ?: getString(R.string.kill_switch_blocked_text)
        val notification = NotificationCompat.Builder(this, StaticNotificationModule.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setColor(getColorCompat(R.color.color_clash))
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(getString(R.string.kill_switch_blocked_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    R.id.nf_clash_status,
                    Intent().setComponent(Components.MAIN_ACTIVITY)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
            .addAction(
                0,
                getString(R.string.kill_switch_reconnect),
                PendingIntent.getService(
                    this,
                    R.id.nf_action_reconnect,
                    Intent(this, TunService::class.java),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
            .addAction(
                0,
                getString(R.string.notification_action_disconnect),
                PendingIntent.getBroadcast(
                    this,
                    R.id.nf_action_stop,
                    Intent(Intents.ACTION_CLASH_REQUEST_STOP).setPackage(packageName),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
            .build()
        startForegroundCompat(R.id.nf_clash_status, notification)
    }

    /** Addresses, routes, per-app rules and DNS: what the live tunnel and the kill-switch block share. */
    private fun Builder.configureTunnel(store: ServiceStore): Builder {
        // Interface address
        addAddress(TUN_GATEWAY, TUN_SUBNET_PREFIX)
        if (store.allowIpv6) {
            addAddress(TUN_GATEWAY6, TUN_SUBNET_PREFIX6)
        }

        // Route
        if (store.bypassPrivateNetwork) {
            resources.getStringArray(R.array.bypass_private_route).map(::parseCIDR).forEach {
                addRoute(it.ip, it.prefix)
            }
            if (store.allowIpv6) {
                resources.getStringArray(R.array.bypass_private_route6).map(::parseCIDR).forEach {
                    addRoute(it.ip, it.prefix)
                }
            }

            // Route of virtual DNS
            addRoute(TUN_DNS, 32)
            if (store.allowIpv6) {
                addRoute(TUN_DNS6, 128)
            }
        } else {
            addRoute(NET_ANY, 0)
            if (store.allowIpv6) {
                addRoute(NET_ANY6, 0)
            }
        }

        // Access Control. Apps with a per-app route always stay inside the VPN: outside it
        // the engine never sees their traffic, so the route (block included) could not apply.
        val routedPackages = AppRoutingStore(filesDir).routedPackages()
        when (store.accessControlMode) {
            AccessControlMode.AcceptAll -> Unit
            AccessControlMode.AcceptSelected -> {
                (store.accessControlPackages + packageName + routedPackages).forEach {
                    runCatching { addAllowedApplication(it) }
                }
            }
            AccessControlMode.DenySelected -> {
                (store.accessControlPackages - packageName - routedPackages).forEach {
                    runCatching { addDisallowedApplication(it) }
                }
            }
        }

        // Session name shown in system VPN UI (not related to traffic encryption).
        setSession(getString(R.string.vpn_session_name))

        // Virtual Dns Server
        addDnsServer(TUN_DNS)
        if (store.allowIpv6) {
            addDnsServer(TUN_DNS6)
        }

        // Open MainActivity
        setConfigureIntent(
            PendingIntent.getActivity(
                self,
                R.id.nf_vpn_status,
                Intent().setComponent(Components.MAIN_ACTIVITY),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        )

        if (store.allowBypass) {
            allowBypass()
        }

        return this
    }

    private fun TunModule.open() {
        val store = ServiceStore(self)

        val device = with(Builder()) {
            configureTunnel(store)

            // Blocking
            setBlocking(false)

            // Mtu
            setMtu(TUN_MTU)

            // Metered
            if (Build.VERSION.SDK_INT >= 29) {
                setMetered(false)
            }

            // System Proxy
            if (Build.VERSION.SDK_INT >= 29 && store.systemProxy) {
                listenHttp()?.let {
                    setHttpProxy(
                        ProxyInfo.buildDirectProxy(
                            it.address.hostAddress,
                            it.port,
                            HTTP_PROXY_BLACK_LIST + if (store.bypassPrivateNetwork) HTTP_PROXY_LOCAL_LIST else emptyList()
                        )
                    )
                }
            }

            TunModule.TunDevice(
                fd = establish()?.detachFd()
                    ?: throw NullPointerException("Establish VPN rejected by system"),
                // Operator `X-Network-Stack` header locks the stack; else the user's app setting; else
                // (Auto) the subscription's tun.stack; else the `system` default. See TunStackResolver.
                stack = TunStackResolver.resolve(
                    store.tunStackMode,
                    store.activeProfile?.let { store.subscriptionNetworkStackFor(it) },
                    readActiveProfileConfigYaml(),
                ).also { store.appliedTunStack = it },
                gateway = "$TUN_GATEWAY/$TUN_SUBNET_PREFIX" + if (store.allowIpv6) ",$TUN_GATEWAY6/$TUN_SUBNET_PREFIX6" else "",
                portal = TUN_PORTAL + if (store.allowIpv6) ",$TUN_PORTAL6" else "",
                dns = buildTunDnsEndpoints(store.dnsHijacking, store.allowIpv6),
            )
        }

        // Settings shows whether Android itself blocks traffic without the VPN; only the running
        // VPN can ask.
        if (Build.VERSION.SDK_INT >= 29) {
            store.systemVpnLockdown = if (self.isAlwaysOn && self.isLockdownEnabled) 1 else 0
        }

        attach(device)
    }

    /** Composed `config.yaml` of the active profile (subscription as-is + user layer), or null. */
    private fun readActiveProfileConfigYaml(): String? {
        val uuid = ServiceStore(self).activeProfile ?: return null
        return runCatching {
            java.io.File(self.importedDir.resolve(uuid.toString()), "config.yaml").readText()
        }.getOrNull()
    }

    companion object {
        private const val TUN_MTU = 9000
        private const val TUN_SUBNET_PREFIX = 30
        private const val TUN_GATEWAY = "172.19.0.1"
        private const val TUN_SUBNET_PREFIX6 = 126
        private const val TUN_GATEWAY6 = "fdfe:dcba:9876::1"
        private const val TUN_PORTAL = "172.19.0.2"
        private const val TUN_PORTAL6 = "fdfe:dcba:9876::2"
        private const val TUN_DNS = TUN_PORTAL
        private const val TUN_DNS6 = TUN_PORTAL6
        private const val NET_ANY = "0.0.0.0"
        private const val NET_ANY6 = "::"

        internal fun buildTunDnsEndpoints(dnsHijacking: Boolean, allowIpv6: Boolean): String =
            if (dnsHijacking) {
                NET_ANY + if (allowIpv6) ",$NET_ANY6" else ""
            } else {
                TUN_DNS + if (allowIpv6) ",$TUN_DNS6" else ""
            }

        private val HTTP_PROXY_LOCAL_LIST: List<String> = listOf(
            "localhost",
            "*.local",
            "127.*",
            "10.*",
            "172.16.*",
            "172.17.*",
            "172.18.*",
            "172.19.*",
            "172.2*",
            "172.30.*",
            "172.31.*",
            "192.168.*"
        )
        private val HTTP_PROXY_BLACK_LIST: List<String> = listOf(
            "*zhihu.com",
            "*zhimg.com",
            "*jd.com",
            "100ime-iat-api.xfyun.cn",
            "*360buyimg.com",
        )
    }
}
