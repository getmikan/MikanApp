package com.github.kr328.clash.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installed apps that ask for network access, Mikan aside: package to label, sorted by label. */
suspend fun Context.internetApps(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
    packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        .filter { it.packageName != packageName && it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
        .mapNotNull { pkg -> pkg.applicationInfo?.let { info -> pkg.packageName to packageManager.getApplicationLabel(info).toString() } }
        .sortedBy { it.second.lowercase() }
}

/** Search-as-you-type picker over [apps]; [onPicked] gets the package of the chosen one. */
fun Context.showAppPicker(apps: List<Pair<String, String>>, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val labels = apps.map { "${it.second} · ${it.first}" }
    val field = TextInputLayout(this).apply { hint = getString(R.string.routing_find_app) }
    val input = AutoCompleteTextView(this).apply {
        threshold = 0
        minHeight = (48 * resources.displayMetrics.density).toInt()
        setAdapter(ArrayAdapter(this@showAppPicker, android.R.layout.simple_dropdown_item_1line, labels))
    }
    field.addView(input)
    val container = LinearLayout(this).apply {
        val padding = (20 * resources.displayMetrics.density).toInt()
        setPadding(padding, 0, padding, padding)
        addView(field)
    }
    val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.routing_pick_app)
        .setView(container).setNegativeButton(R.string.cancel, null).create()
    input.setOnItemClickListener { parent, _, position, _ ->
        val label = parent.getItemAtPosition(position).toString()
        apps.getOrNull(labels.indexOf(label))?.let { onPicked(it.first) }
        dialog.dismiss()
    }
    input.setOnFocusChangeListener { _, focused -> if (focused) input.post { input.showDropDown() } }
    dialog.setOnDismissListener { onDismiss() }
    dialog.show()
    input.requestFocus()
}
