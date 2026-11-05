package com.github.kr328.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.AppRouteSheet
import com.github.kr328.clash.design.AppRoutingDesign
import com.github.kr328.clash.design.AppRoutingDesign.Health
import com.github.kr328.clash.design.CustomProxySheet
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.remote.IProxyDelayObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.AppRoutingComposer
import com.github.kr328.clash.service.util.AppRoutingRules
import com.github.kr328.clash.service.util.AppRoutingStore
import com.github.kr328.clash.service.util.CustomProxy
import com.github.kr328.clash.service.util.RoutableTargets
import com.github.kr328.clash.service.util.sendOverrideChanged
import com.github.kr328.clash.util.closeConnectionsAfterUserProxySwitchIfEnabled
import com.github.kr328.clash.util.internetApps
import com.github.kr328.clash.util.showAppPicker
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** "App proxies": the user's own proxies and per-app routes (see AppRoutingComposer). */
class AppRoutingActivity : BaseActivity<AppRoutingDesign>() {
    private val store by lazy { AppRoutingStore(filesDir) }
    private var targets = RoutableTargets(emptyList(), emptyList())
    private var apps: Map<String, AppInfo> = emptyMap()

    /** Close the connections opened under the old routes once the engine has the new ones. */
    private var flushOnLoad = false
    private var checkGeneration = 0
    private var pickerOpen = false

    override suspend fun main() {
        val design = AppRoutingDesign(this)
        setContentDesign(design)
        if (!AppRoutingStore.supported) {
            design.showUnsupported()
            return
        }
        reload(design)
        launch { checkProxies(design) }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ProfileLoaded && flushOnLoad) {
                        flushOnLoad = false
                        closeConnectionsAfterUserProxySwitchIfEnabled { message, duration -> design.showToast(message, duration) }
                    }
                    reload(design)
                    if (it == Event.ProfileLoaded || it == Event.ClashStart || it == Event.ClashStop) {
                        launch { checkProxies(design) }
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        AppRoutingDesign.Request.AddProxy -> editProxy(design, null)
                        is AppRoutingDesign.Request.EditProxy -> editProxy(design, it.id)
                        AppRoutingDesign.Request.AddRoute -> editRoute(design, null)
                        is AppRoutingDesign.Request.EditRoute -> editRoute(design, it.id)
                        AppRoutingDesign.Request.CheckProxies -> launch { checkProxies(design) }
                    }
                }
            }
        }
    }

    private suspend fun reload(design: AppRoutingDesign) {
        val config = withContext(Dispatchers.IO) { store.loadConfig() }
        val settings = config.settings
        // The composed config of the active subscription: what the routes can name and miss.
        val yaml = withProfile { queryActive()?.takeIf { it.imported }?.let { readConfigYaml(it.uuid) } }
        val issues = withContext(Dispatchers.Default) {
            targets = yaml?.let(AppRoutingRules::routableTargets) ?: RoutableTargets(emptyList(), emptyList())
            yaml?.let { AppRoutingComposer.issues(it, config) }.orEmpty()
        }
        apps = withContext(Dispatchers.IO) {
            settings.routes.mapNotNull { installedApp(it.packageName) }.associateBy { it.packageName }
        }
        val globalMode = clashRunning &&
            runCatching { withClash { queryTunnelState() }.mode == TunnelState.Mode.Global }.getOrDefault(false)
        design.bind(settings, apps, issues, globalMode, vpnMode = uiStore.enableVpn)
    }

    private fun installedApp(packageName: String): AppInfo? = try {
        packageManager.getPackageInfo(packageName, 0).toAppInfo(packageManager)
    } catch (_: Exception) {
        null
    }

    private suspend fun editProxy(design: AppRoutingDesign, id: String?) {
        val settings = withContext(Dispatchers.IO) { store.load() }
        val proxy = id?.let { settings.proxies.firstOrNull { p -> p.id == it } ?: return }
        val hasPassword = proxy != null && withContext(Dispatchers.IO) { store.hasPassword(proxy.id) }
        CustomProxySheet(
            context = this,
            proxy = proxy,
            hasPassword = hasPassword,
            others = settings.proxies,
            onSave = { p, password -> launch { commit(design) { store.saveProxy(p, password) } } },
            onDelete = proxy?.let { p -> fun() { launch { commit(design) { store.deleteProxy(p.id) } } } },
        ).show()
    }

    private suspend fun editRoute(design: AppRoutingDesign, id: String?) {
        val settings = withContext(Dispatchers.IO) { store.load() }
        val route = id?.let { settings.routes.firstOrNull { r -> r.id == it } ?: return }
        AppRouteSheet(
            context = this,
            route = route,
            proxies = settings.proxies,
            targets = targets,
            resolveApp = { packageName -> apps[packageName] ?: installedApp(packageName) },
            onPickApp = { picked -> launch { pickApp(design, picked) } },
            onSave = { r -> launch { commit(design) { store.saveRoute(r) } } },
            onDelete = route?.let { r -> fun() { launch { commit(design) { store.deleteRoute(r.id) } } } },
        ).show()
    }

    private suspend fun pickApp(design: AppRoutingDesign, picked: (String) -> Unit) {
        if (pickerOpen) return
        pickerOpen = true
        val list = try {
            internetApps()
        } catch (e: CancellationException) {
            pickerOpen = false
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (list.isEmpty()) {
            pickerOpen = false
            design.showToast(R.string.routing_apps_empty, ToastDuration.Long)
            return
        }
        showAppPicker(list, onDismiss = { pickerOpen = false }, onPicked = picked)
    }

    /**
     * Saves one change and hands it to the running engine: a config reload, or a VPN restart
     * when the set of apps the tunnel holds changes (routed apps are kept inside it).
     */
    private suspend fun commit(design: AppRoutingDesign, change: () -> Unit) {
        val before = withContext(Dispatchers.IO) { store.routedPackages() }
        val saved = withContext(Dispatchers.IO) { runCatching { change() } }
        if (saved.isFailure) {
            Log.w("App routing: saving failed", saved.exceptionOrNull())
            design.showToast(R.string.app_routing_error_save, ToastDuration.Long)
            reload(design)
            return
        }
        val after = withContext(Dispatchers.IO) { store.routedPackages() }
        when {
            !clashRunning -> {
                design.showToast(R.string.app_routing_saved, ToastDuration.Short)
                launch { checkProxies(design) }
            }
            tunnelAppsChange(before, after) -> {
                design.showToast(R.string.app_routing_applying, ToastDuration.Short)
                restartVpn()
            }
            else -> {
                flushOnLoad = true
                sendOverrideChanged()
            }
        }
        reload(design)
    }

    /** Mirrors TunService: routed apps join an allow list and leave a deny list. */
    private fun tunnelAppsChange(before: Set<String>, after: Set<String>): Boolean {
        val service = ServiceStore(this)
        val listed = service.accessControlPackages
        return when (service.accessControlMode) {
            AccessControlMode.AcceptAll -> false
            AccessControlMode.AcceptSelected -> listed + before != listed + after
            AccessControlMode.DenySelected -> listed - before != listed - after
        }
    }

    private suspend fun restartVpn() {
        stopClashService()
        withTimeoutOrNull(10_000L) { while (clashRunning) delay(200L) }
        startClashService()
    }

    private suspend fun checkProxies(design: AppRoutingDesign) {
        val generation = ++checkGeneration
        val config = withContext(Dispatchers.IO) { store.loadConfig() }
        val running = clashRunning
        coroutineScope {
            for (p in config.settings.proxies) {
                if (!running && p.viaVpn) {
                    design.patchHealth(p.id, Health.NeedsVpn)
                    continue
                }
                design.patchHealth(p.id, Health.Checking)
                launch {
                    val health = if (running) probeLoaded(p) else probeDirect(p, config.passwords[p.id])
                    if (generation == checkGeneration) design.patchHealth(p.id, health)
                }
            }
        }
    }

    /** Through the running engine, so a "via VPN" proxy is measured the way apps reach it. */
    private suspend fun probeLoaded(p: CustomProxy): Health {
        val result = CompletableDeferred<Health>()
        try {
            withClash {
                healthCheckProxy("", AppRoutingRules.configName(p), "", object : IProxyDelayObserver {
                    override fun onDelay(group: String, proxy: String, delayMs: Int, errMsg: String) {
                        result.complete(if (errMsg.isEmpty()) Health.Works(delayMs) else Health.Down)
                    }

                    // Without a delay first the engine does not have the proxy: it is unusable.
                    override fun onComplete(error: String?) {
                        result.complete(Health.Down)
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result.complete(Health.Down)
        }
        return result.await()
    }

    /** With the VPN off only a direct proxy can be reached; built from its own YAML block. */
    private suspend fun probeDirect(p: CustomProxy, password: String?): Health {
        if (p.username.isNotEmpty() && password == null) return Health.Down
        val delay = try {
            withClash { urlTestProxyYaml(AppRoutingRules.proxyYaml(p, password), "") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Int.MAX_VALUE
        }
        return if (delay in 0 until Int.MAX_VALUE) Health.Works(delay) else Health.Down
    }
}
