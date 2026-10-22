package com.github.kr328.clash.common.util

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Server groups with traffic of their own, as a mikan panel sends them in `X-Mikan-Groups`.
 * The app asks for them with `X-Mikan-Features: groups` ([SubscriptionRequestHeaders]); a
 * panel that does not know the extension sends nothing and the app shows no groups. The
 * value is `base64:` and a JSON object, version 1:
 *
 *     {"v":1,"resets_at":1793491200,"groups":[{"id":3,"name":"Premium +","mode":"own",
 *      "used":3221225472,"limit":16106127360,"left":12884901888,"state":"active",
 *      "proxies":["🇺🇸 VLESS Reality","🇺🇸 Hysteria2"]}]}
 *
 * Whatever this parser cannot read with certainty (another major version, a broken value)
 * gives null: the app shows no groups rather than wrong ones.
 */
data class MikanGroups(
    /** Unix seconds when the period's traffic, the groups' with it, starts over; null: never. */
    val resetsAt: Long?,
    val groups: List<Group>,
) {
    enum class Mode {
        /** A limit of its own, apart from the main traffic. */
        Own,

        /** Counted apart from the main traffic, without a limit. */
        Unlimited,

        /** The group's servers spend the main traffic: only their label is the group's. */
        Main,
    }

    data class Group(
        val id: Long,
        val name: String,
        val mode: Mode,
        /** Bytes of the period; 0 for [Mode.Main], whose traffic is the main traffic. */
        val used: Long,
        /** The base limit of [Mode.Own]; null for the other modes. */
        val limit: Long?,
        /** Bytes left of the traffic bought or given for the group ([Mode.Own]). */
        val extra: Long,
        /** Bytes left with [extra] ([Mode.Own]); null for the other modes. */
        val left: Long?,
        /** An own limit with nothing left: its servers are out of the profile until it starts over. */
        val exhausted: Boolean,
        /** Names of the group's servers in the profile; null when the panel left them out (too many). */
        val proxies: List<String>?,
    ) {
        /** What the group had in this period: the limit and its packages; null without a limit. */
        val total: Long? get() = if (limit == null || left == null) null else used + left

        /** The used share of [total], 0..1; null without a limit. */
        val usedFraction: Float?
            get() = total?.let { t -> if (t <= 0L) 1f else (used.toDouble() / t).coerceIn(0.0, 1.0).toFloat() }
    }

    /** The groups worth a line of their own: those with traffic apart from the main traffic. */
    val withTraffic: List<Group> get() = groups.filter { it.mode != Mode.Main }

    /** The group a server of the profile is in, by its name; null for the main servers. */
    fun groupOf(proxyName: String): Group? = byProxy[proxyName]

    private val byProxy: Map<String, Group> by lazy {
        buildMap { groups.forEach { g -> g.proxies?.forEach { putIfAbsent(it, g) } } }
    }

    companion object {
        /** The response header, lowercased as `fetch-headers.json` keeps its keys. */
        const val HEADER = "x-mikan-groups"

        /** The request header that lists the extensions the app shows, and this one's token. */
        const val FEATURES_HEADER = "x-mikan-features"
        const val FEATURE = "groups"

        private const val VERSION = 1
        private const val MAX_GROUPS = 64
        private const val MAX_NAME = 64
        private const val MAX_PROXIES = 4096
        private const val MAX_RAW = 64 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        @OptIn(ExperimentalEncodingApi::class)
        fun parse(raw: String?): MikanGroups? {
            val value = raw?.trim()?.trim('"')?.takeIf { it.isNotEmpty() && it.length <= MAX_RAW } ?: return null
            val text = when {
                value.startsWith("{") -> value
                value.startsWith("base64:", ignoreCase = true) -> runCatching {
                    Base64.Default.decode(value.substring("base64:".length).trim()).decodeToString()
                }.getOrNull() ?: return null
                else -> return null
            }
            val wire = runCatching { json.decodeFromString(Wire.serializer(), text) }.getOrNull() ?: return null
            if (wire.v != VERSION) return null
            val groups = wire.groups.orEmpty().take(MAX_GROUPS).mapNotNull { it.toGroup() }
            return MikanGroups(resetsAt = wire.resetsAt?.takeIf { it > 0L }, groups = groups)
        }

        private fun WireGroup.toGroup(): Group? {
            val mode = when (mode) {
                "own" -> Mode.Own
                "unlimited" -> Mode.Unlimited
                "main" -> Mode.Main
                else -> return null // a mode added later: not shown rather than shown wrong
            }
            val title = cleanName(name) ?: return null
            val own = mode == Mode.Own
            val limit = limit?.takeIf { own && it >= 0L }
            return Group(
                id = id,
                name = title,
                mode = mode,
                used = if (mode == Mode.Main) 0L else used.coerceAtLeast(0L),
                limit = limit,
                extra = if (own) extra.coerceAtLeast(0L) else 0L,
                left = left?.takeIf { own && limit != null && it >= 0L },
                exhausted = own && state == "exhausted",
                proxies = proxies?.asSequence()?.filter { it.isNotBlank() }?.take(MAX_PROXIES)?.toList(),
            )
        }

        private fun cleanName(raw: String): String? {
            val s = raw.filterNot { it.isISOControl() }.trim()
            if (s.isEmpty()) return null
            return if (s.codePointCount(0, s.length) <= MAX_NAME) s else s.substring(0, s.offsetByCodePoints(0, MAX_NAME))
        }
    }

    @Serializable
    private data class Wire(
        val v: Int = 0,
        @SerialName("resets_at") val resetsAt: Long? = null,
        val groups: List<WireGroup>? = null,
    )

    @Serializable
    private data class WireGroup(
        val id: Long = 0,
        val name: String = "",
        val mode: String = "",
        val used: Long = 0,
        val limit: Long? = null,
        val extra: Long = 0,
        val left: Long? = null,
        val state: String = "active",
        val proxies: List<String>? = null,
    )
}
