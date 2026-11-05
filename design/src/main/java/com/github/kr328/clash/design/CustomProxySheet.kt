package com.github.kr328.clash.design

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.service.util.AppRoutingException
import com.github.kr328.clash.service.util.AppRoutingRules
import com.github.kr328.clash.service.util.CustomProxy
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Adds or edits one of the user's own proxies. A pasted link fills the fields; the password is
 * only shown as "keep" for a proxy that already has one stored.
 */
class CustomProxySheet(
    private val context: Context,
    private val proxy: CustomProxy?,
    private val hasPassword: Boolean,
    private val others: List<CustomProxy>,
    private val onSave: (CustomProxy, String) -> Unit,
    private val onDelete: (() -> Unit)?,
) {
    private val dialog = AppBottomSheetDialog(context, fitContentHeight = true)
    private val root: View = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_custom_proxy, null)
    private val linkBox: TextInputLayout = root.findViewById(R.id.proxy_link_box)
    private val link: TextInputEditText = root.findViewById(R.id.proxy_link)
    private val nameBox: TextInputLayout = root.findViewById(R.id.proxy_name_box)
    private val name: TextInputEditText = root.findViewById(R.id.proxy_name)
    private val type: MaterialButtonToggleGroup = root.findViewById(R.id.proxy_type)
    private val serverBox: TextInputLayout = root.findViewById(R.id.proxy_server_box)
    private val server: TextInputEditText = root.findViewById(R.id.proxy_server)
    private val portBox: TextInputLayout = root.findViewById(R.id.proxy_port_box)
    private val port: TextInputEditText = root.findViewById(R.id.proxy_port)
    private val username: TextInputEditText = root.findViewById(R.id.proxy_username)
    private val passwordBox: TextInputLayout = root.findViewById(R.id.proxy_password_box)
    private val password: TextInputEditText = root.findViewById(R.id.proxy_password)
    private val viaVpn: MaterialSwitch = root.findViewById(R.id.proxy_via_vpn)
    private val viaVpnHint: TextView = root.findViewById(R.id.proxy_via_vpn_hint)
    private val error: TextView = root.findViewById(R.id.proxy_error)

    /** A stored password stays unless the user types a new one; it only exists with a user name. */
    private val keepsPassword get() = proxy != null && hasPassword && proxy.username.isNotEmpty()

    fun show() {
        root.findViewById<TextView>(R.id.sheet_title)
            .setText(if (proxy == null) R.string.app_routing_new_proxy else R.string.app_routing_edit_proxy)
        if (proxy != null) {
            name.setText(proxy.name)
            server.setText(proxy.server)
            port.setText(proxy.port.toString())
            username.setText(proxy.username)
            viaVpn.isChecked = proxy.viaVpn
            type.check(if (proxy.type == "http") R.id.proxy_type_http else R.id.proxy_type_socks)
        }
        if (keepsPassword) passwordBox.helperText = context.getString(R.string.app_routing_proxy_password_keep)
        refreshViaVpnHint()

        link.doAfterTextChanged { applyLink(it?.toString().orEmpty(), final = false) }
        link.setOnFocusChangeListener { _, focused -> if (!focused) applyLink(link.text.toString(), final = true) }
        viaVpn.setOnCheckedChangeListener { _, _ -> refreshViaVpnHint() }
        for ((box, field) in listOf(nameBox to name, serverBox to server, portBox to port, passwordBox to password)) {
            field.doAfterTextChanged {
                box.error = null
                error.visibility = View.GONE
            }
        }

        root.findViewById<MaterialButton>(R.id.btn_delete).apply {
            visibility = if (onDelete != null && proxy != null) View.VISIBLE else View.GONE
            setOnClickListener { confirmDelete() }
        }
        root.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        root.findViewById<MaterialButton>(R.id.btn_confirm).setOnClickListener { submit() }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun refreshViaVpnHint() {
        viaVpnHint.setText(if (viaVpn.isChecked) R.string.app_routing_proxy_via_vpn_on else R.string.app_routing_proxy_via_vpn_off)
    }

    /** Fills the form from a link; an unreadable one is only flagged once the user leaves the field. */
    private fun applyLink(text: String, final: Boolean) {
        if (text.isBlank()) {
            linkBox.error = null
            return
        }
        val parsed = AppRoutingRules.parseProxyLink(text)
        if (parsed == null) {
            if (final) linkBox.error = context.getString(R.string.app_routing_proxy_link_bad)
            return
        }
        linkBox.error = null
        parsed.type?.let { type.check(if (it == "http") R.id.proxy_type_http else R.id.proxy_type_socks) }
        server.setText(parsed.server)
        port.setText(parsed.port.toString())
        username.setText(parsed.username)
        password.setText(parsed.password)
        if (name.text.isNullOrBlank()) name.setText(parsed.server)
    }

    private fun submit() {
        val candidate = CustomProxy(
            id = proxy?.id.orEmpty(),
            name = name.text.toString(),
            type = if (type.checkedButtonId == R.id.proxy_type_http) "http" else "socks5",
            server = server.text.toString(),
            port = port.text.toString().trim().toIntOrNull() ?: 0,
            username = username.text.toString(),
            viaVpn = viaVpn.isChecked,
        )
        val normalized = try {
            AppRoutingRules.normalizeProxy(candidate, others)
        } catch (e: AppRoutingException) {
            when (e.reason) {
                AppRoutingException.Reason.NoName -> nameBox.error = context.getString(R.string.app_routing_error_name)
                AppRoutingException.Reason.DuplicateName -> nameBox.error = context.getString(R.string.app_routing_error_duplicate)
                AppRoutingException.Reason.BadServer -> serverBox.error = context.getString(R.string.app_routing_error_server)
                AppRoutingException.Reason.BadPort -> portBox.error = context.getString(R.string.app_routing_error_port)
                else -> showError(R.string.app_routing_error_save)
            }
            return
        }
        val secret = password.text.toString()
        // A user name with no password would leave the proxy unusable and its apps blocked.
        if (normalized.username.isNotEmpty() && secret.isEmpty() && !(keepsPassword && normalized.username == proxy?.username)) {
            passwordBox.error = context.getString(R.string.app_routing_error_password)
            return
        }
        onSave(normalized, secret)
        dialog.dismiss()
    }

    private fun showError(resId: Int) {
        error.setText(resId)
        error.visibility = View.VISIBLE
    }

    private fun confirmDelete() {
        val current = proxy ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.app_routing_delete_proxy_title, current.name))
            .setMessage(R.string.app_routing_delete_proxy_text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                onDelete?.invoke()
                dialog.dismiss()
            }
            .show()
    }
}
