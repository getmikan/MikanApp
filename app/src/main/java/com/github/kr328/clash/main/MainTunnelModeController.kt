package com.github.kr328.clash.main

import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.TunnelModeApplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun normalizeTunnelMode(mode: TunnelState.Mode): TunnelState.Mode = TunnelModeApplier.normalize(mode)

internal fun parseTunnelMode(name: String): TunnelState.Mode? = TunnelModeApplier.parse(name)

/**
 * Rule / Global switching of the Home mode selector, plus the guard that keeps GLOBAL from
 * resting on DIRECT / REJECT. Owns [pendingTunnelMode], the optimistic mode the dashboard
 * shows until the core confirms it.
 */
internal class MainTunnelModeController(private val activity: MainActivity) {
    private val uiStore get() = activity.store
    private val clashRunning get() = activity.isClashRunning

    var pendingTunnelMode: TunnelState.Mode? = null

    suspend fun patchTunnelMode(design: MainDesign, mode: TunnelState.Mode, scheduleRefresh: () -> Unit) {
        design.applyTunnelMode(mode, scheduleRefresh)
    }

    private suspend fun MainDesign.applyTunnelMode(mode: TunnelState.Mode, scheduleRefresh: () -> Unit) {
        try {
            val normalized = normalizeTunnelMode(mode)
            pendingTunnelMode = normalized
            setMode(normalized)

            val running = clashRunning || withContext(Dispatchers.IO) { activity.resolveStatusSnapshot().ready }
            // The non-UI half (persist, patch the Session override, keep GLOBAL safe, verify and
            // re-apply) is shared with the companion gateway.
            val error = runCatching {
                if (TunnelModeApplier.apply(activity, normalized, running, remote = false)) {
                    pendingTunnelMode = null
                }
            }.exceptionOrNull()
            if (error != null && error !is CancellationException) {
                pendingTunnelMode = null
                showExceptionToast(error as? Exception ?: Exception(error))
            }
        } catch (e: Exception) {
            pendingTunnelMode = null
            if (e !is CancellationException) showExceptionToast(e)
        } finally {
            // Defer heavy dashboard work so mode UI updates immediately (Rule / Global switch).
            launch {
                delay(48)
                scheduleRefresh()
            }
        }
    }

    suspend fun ensureGlobalSelectionSafe() = TunnelModeApplier.ensureGlobalSelectionSafe()

    suspend fun ensureGlobalSelectionSafeWithRetry(
        attempts: Int = 10,
        delayMs: Long = 180L,
    ) = TunnelModeApplier.ensureGlobalSelectionSafeWithRetry(activity, attempts, delayMs)

    /**
     * A paired controller changed the mode: drop our own converging pick so the dashboard shows
     * the persisted mode instead of reverting it.
     */
    fun adoptExternalTunnelMode() {
        pendingTunnelMode = null
    }
}
