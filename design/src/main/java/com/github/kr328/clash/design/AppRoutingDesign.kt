package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.util.AppRoutingIssue
import com.github.kr328.clash.service.util.AppRoutingRules
import com.github.kr328.clash.service.util.AppRoutingSettings
import com.github.kr328.clash.service.util.CustomProxy
import com.google.android.material.color.MaterialColors

/**
 * "App proxies": the user's own SOCKS5/HTTP proxies and the route of each chosen app. Editing
 * happens in [CustomProxySheet] and [AppRouteSheet]; this screen lists, checks and explains.
 */
class AppRoutingDesign(context: Context) : Design<AppRoutingDesign.Request>(context) {
    sealed class Request {
        object AddProxy : Request()
        data class EditProxy(val id: String) : Request()
        object AddRoute : Request()
        data class EditRoute(val id: String) : Request()
        object CheckProxies : Request()
    }

    /** Last check of one proxy. */
    sealed class Health {
        object Checking : Health()
        object NeedsVpn : Health()
        data class Works(val delayMs: Int) : Health()
        object Down : Health()
    }

    private val view = context.layoutInflater.inflate(R.layout.design_app_routing, context.root, false)
    override val root: View get() = view
    private val loading: View = view.findViewById(R.id.app_routing_loading)
    private val content: View = view.findViewById(R.id.app_routing_content)
    private val issuesCard: View = view.findViewById(R.id.app_routing_issues)
    private val issueList: LinearLayout = view.findViewById(R.id.app_routing_issue_list)
    private val proxyList: LinearLayout = view.findViewById(R.id.app_routing_proxy_list)
    private val routeList: LinearLayout = view.findViewById(R.id.app_routing_route_list)
    private val check: View = view.findViewById(R.id.app_routing_check)

    private var settings = AppRoutingSettings()
    private var apps: Map<String, AppInfo> = emptyMap()
    private var issues: List<AppRoutingIssue> = emptyList()
    private var globalMode = false
    private var vpnMode = true
    private var health: Map<String, Health> = emptyMap()

    init {
        val scroll: NestedScrollView = view.findViewById(R.id.app_routing_scroll)
        val horizontalPadding = context.resources.getDimensionPixelSize(R.dimen.main_padding_horizontal)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            scroll.setPadding(horizontalPadding + bars.left, bars.top, horizontalPadding + bars.right, bars.bottom + horizontalPadding)
            insets
        }
        view.post { ViewCompat.requestApplyInsets(view) }
        view.findViewById<View>(R.id.app_routing_add_proxy).setOnClickListener { requests.trySend(Request.AddProxy) }
        view.findViewById<View>(R.id.app_routing_add_route).setOnClickListener { requests.trySend(Request.AddRoute) }
        check.setOnClickListener { requests.trySend(Request.CheckProxies) }
    }

    fun showUnsupported() {
        loading.visibility = View.GONE
        view.findViewById<View>(R.id.app_routing_unsupported).visibility = View.VISIBLE
    }

    /**
     * @param apps installed apps among the routed packages
     * @param issues what the active subscription cannot honour (see AppRoutingComposer.issues)
     * @param globalMode the running engine ignores rules right now
     * @param vpnMode Mikan runs as a VPN, the only way Android names the app behind a connection
     */
    fun bind(
        settings: AppRoutingSettings,
        apps: Map<String, AppInfo>,
        issues: List<AppRoutingIssue>,
        globalMode: Boolean,
        vpnMode: Boolean,
    ) {
        this.settings = settings
        this.apps = apps
        this.issues = issues
        this.globalMode = globalMode
        this.vpnMode = vpnMode
        health = health.filterKeys { id -> settings.proxies.any { it.id == id } }
        loading.visibility = View.GONE
        content.visibility = View.VISIBLE
        render()
    }

    fun patchHealth(id: String, value: Health) {
        health = health + (id to value)
        render()
    }

    private fun render() {
        renderIssues()
        renderProxies()
        renderRoutes()
    }

    private fun renderIssues() {
        val lines = ArrayList<String>()
        for (issue in issues) {
            when (issue.kind) {
                "keychain" -> lines += context.getString(R.string.app_routing_issue_keychain, issue.proxy)
                "noVpnGroup" -> lines += context.getString(R.string.app_routing_issue_no_vpn_group, issue.proxy)
            }
        }
        if (settings.routes.isNotEmpty() && !vpnMode) {
            lines += context.getString(R.string.app_routing_issue_no_vpn_mode)
        }
        if (settings.routes.isNotEmpty() && (globalMode || issues.any { it.kind == "globalMode" })) {
            lines += context.getString(R.string.app_routing_issue_global_mode)
        }
        issueList.removeAllViews()
        for (line in lines) {
            issueList.addView(TextView(context).apply {
                text = line
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_App_BodyMedium)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnErrorContainer))
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            })
        }
        issuesCard.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderProxies() {
        proxyList.removeAllViews()
        for (p in settings.proxies) {
            val row = inflateRow(proxyList)
            row.icon(R.drawable.ic_baseline_dns)
            row.title.text = p.name
            row.subtitle.text = listOf(
                p.type.uppercase(),
                "${if (':' in p.server) "[${p.server}]" else p.server}:${p.port}",
                context.getString(if (p.viaVpn) R.string.app_routing_via_vpn else R.string.app_routing_direct_dial),
            ).joinToString(" · ")
            when (val h = health[p.id]) {
                Health.Checking -> row.status(context.getString(R.string.app_routing_status_checking), error = false)
                Health.NeedsVpn -> row.status(context.getString(R.string.app_routing_status_needs_vpn), error = false)
                is Health.Works -> row.status(context.getString(R.string.app_routing_status_ok, h.delayMs), error = false)
                Health.Down -> row.status(context.getString(R.string.app_routing_status_down), error = true)
                null -> row.status(null, error = false)
            }
            row.view.setOnClickListener { requests.trySend(Request.EditProxy(p.id)) }
        }
        view.findViewById<View>(R.id.app_routing_proxies_empty).visibility =
            if (settings.proxies.isEmpty()) View.VISIBLE else View.GONE
        check.visibility = if (settings.proxies.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderRoutes() {
        routeList.removeAllViews()
        for (r in settings.routes) {
            val row = inflateRow(routeList)
            val app = apps[r.packageName]
            if (app != null) row.appIcon(app) else row.icon(R.drawable.ic_baseline_subscriptions)
            row.title.text = app?.label ?: r.packageName
            val proxy = r.target.takeIf { it.startsWith(AppRoutingRules.PROXY_PREFIX) }
                ?.let { t -> settings.proxies.firstOrNull { AppRoutingRules.PROXY_PREFIX + it.id == t } }
            row.subtitle.text = targetLabel(r.target, proxy)
            val blocked = blockedReason(r.packageName, proxy)
            when {
                blocked != null -> row.status(blocked, error = true)
                app == null -> row.status(context.getString(R.string.app_routing_app_missing), error = false)
                else -> row.status(null, error = false)
            }
            row.view.setOnClickListener { requests.trySend(Request.EditRoute(r.id)) }
        }
        view.findViewById<View>(R.id.app_routing_routes_empty).visibility =
            if (settings.routes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun targetLabel(target: String, proxy: CustomProxy?): String = when {
        target == AppRoutingRules.DIRECT -> context.getString(R.string.app_routing_target_direct)
        target == AppRoutingRules.REJECT -> context.getString(R.string.app_routing_target_block)
        target.startsWith(AppRoutingRules.SERVER_PREFIX) ->
            context.getString(R.string.app_routing_target_server, target.removePrefix(AppRoutingRules.SERVER_PREFIX))
        else -> context.getString(R.string.app_routing_target_proxy, proxy?.name.orEmpty())
    }

    /** Why this app is blocked against its route, or null when it goes where it should. */
    private fun blockedReason(packageName: String, proxy: CustomProxy?): String? {
        issues.firstOrNull { it.packageName == packageName && it.kind == "serverMissing" }
            ?.let { return context.getString(R.string.app_routing_blocked_server_missing, it.server) }
        issues.firstOrNull { it.packageName == packageName && it.kind == "proxyBlocked" }
            ?.let { return context.getString(R.string.app_routing_blocked_proxy_unusable, it.proxy) }
        if (proxy != null && health[proxy.id] == Health.Down) {
            return context.getString(R.string.app_routing_blocked_proxy_down, proxy.name)
        }
        return null
    }

    private class Row(val view: View) {
        val image: ImageView = view.findViewById(R.id.row_icon)
        val title: TextView = view.findViewById(R.id.row_title)
        val subtitle: TextView = view.findViewById(R.id.row_subtitle)
        val statusView: TextView = view.findViewById(R.id.row_status)

        fun icon(resId: Int) {
            val padding = (8 * view.resources.displayMetrics.density).toInt()
            image.setPadding(padding, padding, padding, padding)
            image.setImageResource(resId)
            image.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(image, com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
        }

        fun appIcon(app: AppInfo) {
            image.setPadding(0, 0, 0, 0)
            image.imageTintList = null
            image.setImageDrawable(app.icon)
        }

        fun status(text: String?, error: Boolean) {
            statusView.visibility = if (text == null) View.GONE else View.VISIBLE
            statusView.text = text
            statusView.setTextColor(
                MaterialColors.getColor(
                    statusView,
                    if (error) com.google.android.material.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant,
                ),
            )
        }
    }

    private fun inflateRow(parent: LinearLayout): Row {
        val view = context.layoutInflater.inflate(R.layout.item_app_routing_row, parent, false)
        parent.addView(view)
        return Row(view)
    }
}
