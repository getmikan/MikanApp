package com.github.kr328.clash.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.common.network.AppNetworkDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * GitHub Releases API helper for Mikan updates (same endpoint as About / periodic checker).
 */
object GitHubReleaseUpdate {

    private const val PREFS_NAME = "app_update"
    private const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
    private const val API = "https://api.github.com/repos/getmikan/MikanApp"

    data class Info(
        val tagName: String,
        val body: String,
        val htmlUrl: String,
        val apkUrl: String?,
        val apkName: String?,
    )

    /** The release this build came from: its tag in CI, else its version name as a stable one. */
    val installedTag: String
        get() = BuildConfig.RELEASE_TAG.ifBlank { "v${BuildConfig.VERSION_NAME}" }

    fun isNewer(tag: String): Boolean = compareTags(tag, installedTag) > 0

    fun isPrerelease(tag: String): Boolean = '-' in tag.removePrefix("v")

    /**
     * The newest release of the channel: the latest stable one, or with [beta] whatever is
     * newest, betas included. Null when GitHub cannot be reached.
     */
    suspend fun fetchNewest(beta: Boolean): Info? = withContext(Dispatchers.IO) {
        runCatching<Info?> {
            if (!beta) return@runCatching parse(JSONObject(get("$API/releases/latest")))
            val list = JSONArray(get("$API/releases?per_page=10"))
            val releases = (0 until list.length()).mapNotNull(list::optJSONObject).filterNot { it.optBoolean("draft") }
            val newest = newestTag(releases.map { it.optString("tag_name") }) ?: return@runCatching null
            parse(releases.first { it.optString("tag_name") == newest })
        }.getOrNull()
    }

    private fun get(url: String): String = HttpTextFetcher.fetchUtf8(
        url,
        connectTimeoutMs = AppNetworkDefaults.CONNECT_TIMEOUT_MS,
        readTimeoutMs = AppNetworkDefaults.READ_TIMEOUT_MS,
        headers = mapOf("User-Agent" to "Mikan/${BuildConfig.VERSION_NAME}"),
    )

    private fun parse(json: JSONObject): Info {
        val assets = json.optJSONArray("assets")
        val apks = (0 until (assets?.length() ?: 0))
            .mapNotNull { assets?.optJSONObject(it) }
            .map { it.optString("name") to it.optString("browser_download_url") }
            .filter { (name, url) -> name.endsWith(".apk", ignoreCase = true) && UpdateApkVerifier.isTrustedDownloadUrl(url) }
        val apkName = pickApk(apks.map { it.first }, Build.SUPPORTED_ABIS.firstOrNull())
        return Info(
            tagName = json.optString("tag_name"),
            body = json.optString("body"),
            htmlUrl = json.optString("html_url")
                .takeIf(UpdateApkVerifier::isTrustedReleasePageUrl)
                .orEmpty(),
            apkUrl = apks.firstOrNull { it.first == apkName }?.second,
            apkName = apkName,
        )
    }

    /** The APK for this phone's main ABI (a few times smaller), else the universal one, else any. */
    internal fun pickApk(names: List<String>, abi: String?): String? {
        val alpha = names.filter { it.lowercase(Locale.ROOT).contains("alpha") }.ifEmpty { names }
        return abi?.let { a -> alpha.firstOrNull { it.lowercase(Locale.ROOT).endsWith("-${a.lowercase(Locale.ROOT)}.apk") } }
            ?: alpha.firstOrNull { it.lowercase(Locale.ROOT).contains("universal") }
            ?: names.firstOrNull()
    }

    internal fun newestTag(tags: List<String>): String? =
        tags.filter { it.isNotBlank() }.maxWithOrNull(::compareTags)

    /**
     * Enqueues an APK download via [DownloadManager]. Persists [KEY_PENDING_DOWNLOAD_ID] for install flow.
     * @return download id, or -1 if enqueue failed.
     */
    fun enqueueApkDownload(context: Context, info: Info): Long {
        val url = info.apkUrl ?: return -1L
        return enqueueApkDownload(
            context = context,
            tagName = info.tagName,
            apkUrl = url,
            apkName = info.apkName,
        )
    }

    fun enqueueApkDownload(
        context: Context,
        tagName: String,
        apkUrl: String,
        apkName: String?,
    ): Long {
        if (!UpdateApkVerifier.isTrustedDownloadUrl(apkUrl)) return -1L
        val dm = context.getSystemService(DownloadManager::class.java) ?: return -1L
        val fileName = (apkName ?: "mikan-$tagName.apk")
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        // Every release names its APKs the same, so the older ones would pile up as "-1", "-2".
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.listFiles { file -> file.name.endsWith(".apk", ignoreCase = true) }
            ?.forEach { it.delete() }
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(context.getString(com.github.kr328.clash.design.R.string.about_update_available, tagName))
            .setDescription(context.getString(com.github.kr328.clash.design.R.string.about_download_started))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

        val id = dm.enqueue(request)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PENDING_DOWNLOAD_ID, id)
            .apply()
        return id
    }

    /**
     * Orders release tags the way they ship: 0.1.5-beta.3 < 0.1.5-beta.10 < 0.1.5 < 0.1.6-beta.1.
     * A version name without a suffix (a local build) counts as that stable release.
     */
    internal fun compareTags(left: String, right: String): Int {
        val a = TagVersion.parse(left)
        val b = TagVersion.parse(right)
        if (a == null || b == null) return left.compareTo(right)
        for (i in 0..2) {
            if (a.core[i] != b.core[i]) return a.core[i].compareTo(b.core[i])
        }
        return comparePre(a.pre, b.pre)
    }

    private class TagVersion(val core: IntArray, val pre: List<String>?) {
        companion object {
            private val PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?""")

            fun parse(tag: String): TagVersion? {
                val m = PATTERN.find(tag) ?: return null
                val core = IntArray(3) { m.groupValues[it + 1].toIntOrNull() ?: 0 }
                return TagVersion(core, m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.'))
            }
        }
    }

    /** Semver pre-release order: a release outranks its betas, beta.10 outranks beta.9. */
    private fun comparePre(a: List<String>?, b: List<String>?): Int {
        if (a == null || b == null) return if (a == b) 0 else if (a == null) 1 else -1
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toIntOrNull()
            val y = b[i].toIntOrNull()
            val order = when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a[i].compareTo(b[i])
            }
            if (order != 0) return order
        }
        return a.size.compareTo(b.size)
    }
}
