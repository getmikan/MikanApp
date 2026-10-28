package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.util.AppRoutingException.Reason
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Per-app routing: the user's own SOCKS5/HTTP proxies and one route per installed app (one of
 * those proxies, a server or group of the profile, DIRECT or REJECT). The proxies join
 * `proxies:`, the routes become `PROCESS-NAME,<package>,<target>` rules in front of every other
 * rule ([AppRoutingComposer]). A route that cannot be honoured blocks its app instead: there is
 * no fallback, the user asked for "block and say so".
 *
 * Global, not per profile: the routes follow the user across subscriptions.
 */
@Serializable
data class CustomProxy(
    val id: String,
    val name: String,
    /** `socks5` or `http`. */
    val type: String,
    val server: String,
    val port: Int,
    val username: String = "",
    /** Dials the proxy through the VPN group (mihomo `dialer-proxy`), so the network only ever
     *  sees VPN traffic. */
    val viaVpn: Boolean = true,
)

@Serializable
data class AppRoute(
    val id: String,
    val packageName: String,
    /** `proxy:<custom proxy id>`, `server:<proxy or group name>`, `direct` or `reject`. */
    val target: String,
)

@Serializable
data class AppRoutingSettings(
    val proxies: List<CustomProxy> = emptyList(),
    val routes: List<AppRoute> = emptyList(),
)

/** Something the last composed config could not honour; the UI words it from [kind]. */
@Serializable
data class AppRoutingIssue(
    /** keychain | noVpnGroup (the proxy is unusable), proxyBlocked | serverMissing (the app is
     *  blocked), globalMode (rules do not apply), processLookupOn (find-process-mode was off). */
    val kind: String,
    val proxy: String = "",
    val packageName: String = "",
    val server: String = "",
)

/** [AppRoutingSettings] plus the proxy passwords, as composition needs them. */
data class AppRoutingConfig(
    val settings: AppRoutingSettings,
    /** Proxy id → password; a proxy with a username but no entry here is unusable. */
    val passwords: Map<String, String>,
) {
    companion object {
        val Empty = AppRoutingConfig(AppRoutingSettings(), emptyMap())
    }
}

/** A setting the rules refuse; the UI words it from [reason]. */
class AppRoutingException(val reason: Reason, message: String) : IllegalArgumentException(message) {
    enum class Reason { NoName, BadType, BadServer, BadPort, DuplicateName, NoApp, ProxyGone, NoServer, BadTarget }
}

/** What a pasted proxy link holds; [type] is null for a bare `host:port`. */
data class ProxyLink(
    val type: String?,
    val server: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
)

/** Names a route can point at, from the composed config: groups first, then single servers. */
data class RoutableTargets(val groups: List<String>, val servers: List<String>)

object AppRoutingRules {
    const val DIRECT = "direct"
    const val REJECT = "reject"
    const val PROXY_PREFIX = "proxy:"
    const val SERVER_PREFIX = "server:"

    /** Keeps the user's proxies apart from subscription nodes of the same name. */
    const val PROXY_NAME_PREFIX = "👤 "

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val HOST = Regex("^[A-Za-z0-9.-]+$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")

    private fun validServer(server: String): Boolean =
        HOST.matches(server) || (server.count { it == ':' } >= 2 && IPV6.matches(server))

    fun configName(proxy: CustomProxy): String = PROXY_NAME_PREFIX + proxy.name

    /** The rule parser splits on commas. */
    private fun clean(value: String): String = value.replace(",", " ").trim()

    fun normalizeProxy(proxy: CustomProxy, others: List<CustomProxy>): CustomProxy {
        val server = proxy.server.trim().removePrefix("[").removeSuffix("]")
        val p = proxy.copy(
            id = proxy.id.ifBlank { UUID.randomUUID().toString() },
            name = clean(proxy.name),
            type = proxy.type.trim().lowercase(),
            server = server,
            username = proxy.username.trim(),
        )
        when {
            p.name.isEmpty() -> throw AppRoutingException(Reason.NoName, "the proxy needs a name")
            p.type != "socks5" && p.type != "http" -> throw AppRoutingException(Reason.BadType, "proxy type must be socks5 or http")
            !validServer(server) -> throw AppRoutingException(Reason.BadServer, "bad proxy server \"$server\"")
            p.port !in 1..65535 -> throw AppRoutingException(Reason.BadPort, "proxy port must be 1-65535")
            others.any { it.id != p.id && it.name == p.name } ->
                throw AppRoutingException(Reason.DuplicateName, "a proxy named \"${p.name}\" already exists")
        }
        return p
    }

    fun normalizeRoute(route: AppRoute, proxies: List<CustomProxy>): AppRoute {
        val r = route.copy(
            id = route.id.ifBlank { UUID.randomUUID().toString() },
            packageName = route.packageName.trim(),
            target = route.target.trim(),
        )
        if (!PACKAGE.matches(r.packageName)) throw AppRoutingException(Reason.NoApp, "pick an app")
        when {
            r.target == DIRECT || r.target == REJECT -> Unit
            r.target.startsWith(PROXY_PREFIX) ->
                if (proxies.none { PROXY_PREFIX + it.id == r.target }) throw AppRoutingException(Reason.ProxyGone, "that proxy no longer exists")
            r.target.startsWith(SERVER_PREFIX) -> {
                val name = r.target.removePrefix(SERVER_PREFIX)
                if (name.isBlank() || name.contains(',')) throw AppRoutingException(Reason.NoServer, "pick a server")
            }
            else -> throw AppRoutingException(Reason.BadTarget, "unknown route \"${r.target}\"")
        }
        return r
    }

    /** One route per app: a route for an app that already has one replaces it in place. */
    fun upsertRoute(routes: List<AppRoute>, route: AppRoute): List<AppRoute> {
        val index = routes.indexOfFirst { it.id == route.id || it.packageName == route.packageName }
        if (index < 0) return routes + route
        return routes.mapIndexedNotNull { i, r ->
            when {
                i == index -> route
                r.packageName == route.packageName -> null
                else -> r
            }
        }
    }

    /** Deleting a proxy takes the routes that use it along. */
    fun removeProxy(settings: AppRoutingSettings, id: String): AppRoutingSettings = settings.copy(
        proxies = settings.proxies.filterNot { it.id == id },
        routes = settings.routes.filterNot { it.target == PROXY_PREFIX + id },
    )

    /**
     * Reads the forms proxy shops and clients hand out: `socks5://user:pass@host:port` (also
     * `socks5h`, `socks`, `http`), `user:pass@host:port`, `host:port` and `host:port:user:pass`;
     * IPv6 hosts in brackets. Null when it is none of these.
     */
    fun parseProxyLink(text: String): ProxyLink? {
        var rest = text.trim()
        if (rest.isEmpty() || rest.any { it.isWhitespace() }) return null
        var type: String? = null
        if ("://" in rest) {
            type = when (rest.substringBefore("://").lowercase()) {
                "socks5", "socks5h", "socks" -> "socks5"
                "http" -> "http"
                else -> return null
            }
            rest = rest.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        }
        var username = ""
        var password = ""
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            val auth = rest.substring(0, at)
            username = percentDecode(auth.substringBefore(':')) ?: return null
            password = percentDecode(auth.substringAfter(':', "")) ?: return null
            rest = rest.substring(at + 1)
        }
        val server: String
        val tail: List<String>
        if (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            if (end < 0 || rest.getOrNull(end + 1) != ':') return null
            server = rest.substring(1, end)
            tail = rest.substring(end + 2).split(':', limit = 3)
        } else {
            val parts = rest.split(':', limit = 4)
            server = parts[0]
            tail = parts.drop(1)
        }
        val port = tail.firstOrNull()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        when (tail.size) {
            1 -> Unit
            3 -> {
                if (at >= 0) return null
                username = tail[1]
                password = tail[2]
            }
            else -> return null
        }
        if (!validServer(server)) return null
        return ProxyLink(type, server, port, username, password)
    }

    private fun percentDecode(value: String): String? {
        if ('%' !in value) return value
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            if (value[i] == '%') {
                val byte = value.substring(i + 1, minOf(i + 3, value.length)).takeIf { it.length == 2 }?.toIntOrNull(16)
                    ?: return null
                out.write(byte)
                i += 3
            } else {
                out.write(value[i].toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Groups and servers of [yaml] a route can name; the user's own proxies are listed apart. */
    fun routableTargets(yaml: String): RoutableTargets {
        val root = YamlFormatting.parseRootMap(yaml) ?: return RoutableTargets(emptyList(), emptyList())
        fun names(key: String) = (root[key] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
            .filter { it.isNotBlank() && !it.startsWith(PROXY_NAME_PREFIX) && ',' !in it }
            .distinct()
        return RoutableTargets(names("proxy-groups"), names("proxies"))
    }

    /** The `proxies:` entry of [proxy]; [dialer] sends it through that group. */
    fun proxyEntry(proxy: CustomProxy, password: String?, dialer: String?): Map<String, Any?> {
        val entry = linkedMapOf<String, Any?>(
            "name" to configName(proxy),
            "type" to proxy.type,
            "server" to proxy.server,
            "port" to proxy.port,
        )
        if (proxy.type == "socks5") entry["udp"] = true
        if (proxy.username.isNotEmpty()) {
            entry["username"] = proxy.username
            entry["password"] = password.orEmpty()
        }
        if (dialer != null) entry["dialer-proxy"] = dialer
        return entry
    }

    /** One proxy as its own YAML block, for a check straight from the device. */
    fun proxyYaml(proxy: CustomProxy, password: String?): String =
        YamlFormatting.blockYaml().dump(proxyEntry(proxy, password, null)).trimEnd()
}

/**
 * Pure text-in/text-out step of [ConfigComposer]: runs after the user script and the
 * subscription chain, so the routes win over every other rule, and before hardening.
 */
object AppRoutingComposer {
    data class Result(val yaml: String, val issues: List<AppRoutingIssue>)

    private val BUILTINS = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "GLOBAL")

    private class Plan(val proxies: List<Map<String, Any?>>, val rules: List<String>, val issues: List<AppRoutingIssue>)

    fun compose(yaml: String, config: AppRoutingConfig): Result {
        val settings = config.settings
        if (settings.proxies.isEmpty() && settings.routes.isEmpty()) return Result(yaml, emptyList())
        val document = MihomoConfigDocument.parseOrThrow(yaml)
        val root = document.root
        val plan = plan(root, config)
        val issues = plan.issues.toMutableList()

        root["proxies"] = (root["proxies"] as? List<*>).orEmpty() + plan.proxies
        val replaced = arrayListOf("proxies")
        if (plan.rules.isNotEmpty()) {
            root["rules"] = plan.rules + (root["rules"] as? List<*>).orEmpty()
            replaced += "rules"
            if ((root["find-process-mode"] as? String).equals("off", ignoreCase = true)) {
                root["find-process-mode"] = "strict"
                replaced += "find-process-mode"
                issues += AppRoutingIssue(kind = "processLookupOn")
            }
        }
        return Result(document.renderReplacing(*replaced.toTypedArray()), issues)
    }

    /**
     * What [config] cannot honour on [yaml], leaving it as is. [yaml] may be a composed
     * `config.yaml`: the user's proxies already in it do not count as servers.
     */
    fun issues(yaml: String, config: AppRoutingConfig): List<AppRoutingIssue> {
        val settings = config.settings
        if (settings.proxies.isEmpty() && settings.routes.isEmpty()) return emptyList()
        val root = YamlFormatting.parseRootMap(yaml) ?: return emptyList()
        return plan(root, config).issues
    }

    private fun plan(root: Map<String, Any?>, config: AppRoutingConfig): Plan {
        val settings = config.settings
        val known = BUILTINS + listOf("proxies", "proxy-groups")
            .flatMap { (root[it] as? List<*>).orEmpty() }
            .mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
            .filterNot { it.startsWith(AppRoutingRules.PROXY_NAME_PREFIX) }
        val vpnGroup = matchTarget((root["rules"] as? List<*>).orEmpty())
            ?.takeUnless { it in setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS") }

        val issues = ArrayList<AppRoutingIssue>()
        val usable = HashSet<String>()
        val added = ArrayList<Map<String, Any?>>()
        for (p in settings.proxies) {
            val password = config.passwords[p.id]
            if (p.username.isNotEmpty() && password == null) {
                issues += AppRoutingIssue(kind = "keychain", proxy = p.name)
                continue
            }
            if (p.viaVpn && vpnGroup == null) {
                issues += AppRoutingIssue(kind = "noVpnGroup", proxy = p.name)
                continue
            }
            added += AppRoutingRules.proxyEntry(p, password, vpnGroup.takeIf { p.viaVpn })
            usable += p.id
        }

        val routeRules = settings.routes.map { r ->
            val target = when {
                r.target == AppRoutingRules.DIRECT -> "DIRECT"
                r.target == AppRoutingRules.REJECT -> "REJECT"
                r.target.startsWith(AppRoutingRules.SERVER_PREFIX) -> {
                    val name = r.target.removePrefix(AppRoutingRules.SERVER_PREFIX)
                    if (name in known) name else {
                        issues += AppRoutingIssue(kind = "serverMissing", packageName = r.packageName, server = name)
                        "REJECT"
                    }
                }
                else -> {
                    val id = r.target.removePrefix(AppRoutingRules.PROXY_PREFIX)
                    val proxy = settings.proxies.firstOrNull { it.id == id }
                    if (proxy != null && id in usable) AppRoutingRules.configName(proxy) else {
                        issues += AppRoutingIssue(kind = "proxyBlocked", packageName = r.packageName, proxy = proxy?.name.orEmpty())
                        "REJECT"
                    }
                }
            }
            "PROCESS-NAME,${r.packageName},$target"
        }
        if (routeRules.isNotEmpty() && (root["mode"] as? String).equals("global", ignoreCase = true)) {
            issues += AppRoutingIssue(kind = "globalMode")
        }
        return Plan(added, routeRules, issues)
    }

    /** The policy of the final MATCH rule: where everything without a better rule goes. */
    private fun matchTarget(rules: List<*>): String? {
        for (raw in rules.asReversed()) {
            val parts = (raw as? String)?.split(',')?.map { it.trim() } ?: continue
            val head = parts.first().uppercase()
            if ((head == "MATCH" || head == "FINAL") && parts.size >= 2) return parts[1]
        }
        return null
    }
}
