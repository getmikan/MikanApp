package com.github.kr328.clash.design

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.service.util.AppRoute
import com.github.kr328.clash.service.util.AppRoutingException
import com.github.kr328.clash.service.util.AppRoutingRules
import com.github.kr328.clash.service.util.CustomProxy
import com.github.kr328.clash.service.util.RoutableTargets
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Adds or edits the route of one app: one of the user's proxies, a server or group of the active
 * subscription, straight past the VPN, or blocked.
 */
class AppRouteSheet(
    private val context: Context,
    private val route: AppRoute?,
    private val proxies: List<CustomProxy>,
    private val targets: RoutableTargets,
    private val resolveApp: (String) -> AppInfo?,
    private val onPickApp: ((String) -> Unit) -> Unit,
    private val onSave: (AppRoute) -> Unit,
    private val onDelete: (() -> Unit)?,
) {
    private val dialog = AppBottomSheetDialog(context, fitContentHeight = true)
    private val root: View = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_app_route, null)
    private val appBox: TextInputLayout = root.findViewById(R.id.route_app_box)
    private val app: TextInputEditText = root.findViewById(R.id.route_app)
    private val options: RadioGroup = root.findViewById(R.id.route_targets)
    private val error: TextView = root.findViewById(R.id.route_error)

    /** The chosen target, as stored: `proxy:<id>`, `server:<name>`, `direct` or `reject`. */
    private var target: String? = route?.target ?: proxies.firstOrNull()?.let { AppRoutingRules.PROXY_PREFIX + it.id }
    private val buttons = LinkedHashMap<String, MaterialRadioButton>()
    private lateinit var serverButton: MaterialRadioButton

    fun show() {
        root.findViewById<TextView>(R.id.sheet_title)
            .setText(if (route == null) R.string.app_routing_route_title_new else R.string.app_routing_route_title)

        app.doAfterTextChanged {
            appBox.error = null
            error.visibility = View.GONE
            refreshApp()
        }
        appBox.setEndIconOnClickListener { onPickApp { packageName -> if (dialog.isShowing) app.setText(packageName) } }
        route?.let { app.setText(it.packageName) }
        refreshApp()

        for (p in proxies) {
            addOption(AppRoutingRules.PROXY_PREFIX + p.id, context.getString(R.string.app_routing_target_proxy, p.name))
        }
        serverButton = addOption(AppRoutingRules.SERVER_PREFIX, "")
        addOption(AppRoutingRules.DIRECT, context.getString(R.string.app_routing_target_direct))
        addOption(AppRoutingRules.REJECT, context.getString(R.string.app_routing_option_block))
        syncOptions()

        root.findViewById<MaterialButton>(R.id.btn_delete).apply {
            visibility = if (onDelete != null && route != null) View.VISIBLE else View.GONE
            setOnClickListener {
                onDelete?.invoke()
                dialog.dismiss()
            }
        }
        root.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        root.findViewById<MaterialButton>(R.id.btn_confirm).setOnClickListener { submit() }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun addOption(key: String, label: String): MaterialRadioButton {
        val button = MaterialRadioButton(context).apply {
            id = View.generateViewId()
            text = label
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { choose(key) }
        }
        options.addView(button, RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT))
        buttons[key] = button
        return button
    }

    private fun choose(key: String) {
        error.visibility = View.GONE
        if (key != AppRoutingRules.SERVER_PREFIX) {
            target = key
            syncOptions()
            return
        }
        // The server row only takes once something is picked from the list.
        syncOptions()
        val names = targets.groups + targets.servers
        if (names.isEmpty()) {
            showError(R.string.app_routing_no_servers)
            return
        }
        ChainNodeSheet(
            context = context,
            title = context.getString(R.string.app_routing_option_server),
            subtitle = context.getString(R.string.app_routing_server_sheet_subtitle),
            names = names,
            selected = currentServer().orEmpty(),
        ) { name ->
            target = AppRoutingRules.SERVER_PREFIX + name
            syncOptions()
        }.show()
    }

    private fun currentServer(): String? =
        target?.takeIf { it.startsWith(AppRoutingRules.SERVER_PREFIX) }?.removePrefix(AppRoutingRules.SERVER_PREFIX)

    private fun syncOptions() {
        val server = currentServer()
        serverButton.text = if (server != null) context.getString(R.string.app_routing_target_server, server)
        else context.getString(R.string.app_routing_option_server)
        val key = if (server != null) AppRoutingRules.SERVER_PREFIX else target
        val checked = buttons[key]
        if (checked != null) options.check(checked.id) else options.clearCheck()
    }

    private fun refreshApp() {
        val info = resolveApp(app.text.toString().trim())
        appBox.startIconDrawable = info?.icon
        appBox.setStartIconTintList(null)
        appBox.helperText = info?.label ?: context.getString(R.string.app_routing_app_helper)
    }

    private fun submit() {
        val chosen = target ?: return showError(R.string.app_routing_error_target)
        val normalized = try {
            AppRoutingRules.normalizeRoute(AppRoute(route?.id.orEmpty(), app.text.toString(), chosen), proxies)
        } catch (e: AppRoutingException) {
            when (e.reason) {
                AppRoutingException.Reason.NoApp -> appBox.error = context.getString(R.string.app_routing_error_app)
                AppRoutingException.Reason.ProxyGone -> showError(R.string.app_routing_error_proxy_gone)
                else -> showError(R.string.app_routing_error_target)
            }
            return
        }
        onSave(normalized)
        dialog.dismiss()
    }

    private fun showError(resId: Int) {
        error.setText(resId)
        error.visibility = View.VISIBLE
    }
}
