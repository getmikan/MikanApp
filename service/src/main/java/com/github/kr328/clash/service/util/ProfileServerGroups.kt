package com.github.kr328.clash.service.util

import com.github.kr328.clash.common.util.MikanGroups
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A subscription's server groups ([MikanGroups]) and its page on the panel, where traffic for
 * a group is bought, as the headers of its last download ([FetchHeadersFile]) gave them. The
 * download is the only request that carries the groups: the hourly update of a mikan
 * subscription keeps them current.
 */
data class ProfileServerGroups(val groups: MikanGroups, val pageUrl: String?) {
    companion object {
        // Parsed once per snapshot: the Home refresh asks for every profile's groups often.
        private val cache = ConcurrentHashMap<String, Pair<Long, ProfileServerGroups?>>()

        /** The groups of the profile kept in [profileDir]; null without any (or another panel). */
        fun read(profileDir: File): ProfileServerGroups? {
            val file = File(profileDir, FetchHeadersFile.FILE_NAME)
            val stamp = file.lastModified() // 0 for no snapshot
            val key = profileDir.absolutePath
            cache[key]?.let { (at, value) -> if (at == stamp) return value }
            val value = FetchHeadersFile.readFrom(profileDir)?.let(::of)
            cache[key] = stamp to value
            return value
        }

        /** The groups in a header snapshot; null when it has none to show. */
        fun of(headers: FetchHeadersFile): ProfileServerGroups? {
            val groups = MikanGroups.parse(headers.get(MikanGroups.HEADER))?.takeIf { it.groups.isNotEmpty() } ?: return null
            val page = headers.get("profile-web-page-url")?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) }
            return ProfileServerGroups(groups, page)
        }
    }
}
