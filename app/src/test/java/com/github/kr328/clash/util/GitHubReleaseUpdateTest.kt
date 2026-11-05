package com.github.kr328.clash.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubReleaseUpdateTest {
    @Test
    fun tagsOrderTheWayReleasesShip() {
        val shipped = listOf(
            "v0.1.3-beta.10",
            "v0.1.4",
            "v0.1.5-beta.2",
            "v0.1.5-beta.3",
            "v0.1.5-beta.10",
            "v0.1.5",
            "v0.1.6-beta.1",
        )
        assertEquals(shipped, shipped.shuffled(kotlin.random.Random(7)).sortedWith(GitHubReleaseUpdate::compareTags))

        // A local build has only its version name ("0.1.5", debug "0.1.5.debug"): that stable release.
        assertEquals(0, GitHubReleaseUpdate.compareTags("v0.1.5.debug", "v0.1.5"))
        assertTrue(GitHubReleaseUpdate.compareTags("v0.1.5", "v0.1.5-beta.3") > 0)
        assertTrue(GitHubReleaseUpdate.compareTags("v0.1.5-beta.4", "v0.1.5-beta.3") > 0)
    }

    @Test
    fun theNewestTagWinsWhateverOrderGitHubListsThemIn() {
        assertEquals("v0.1.5-beta.10", GitHubReleaseUpdate.newestTag(listOf("v0.1.5-beta.2", "", "v0.1.5-beta.10", "v0.1.4")))
        assertEquals(null, GitHubReleaseUpdate.newestTag(emptyList()))
        assertTrue(GitHubReleaseUpdate.isPrerelease("v0.1.5-beta.3"))
        assertFalse(GitHubReleaseUpdate.isPrerelease("v0.1.5"))
    }

    @Test
    fun thePhonesOwnApkIsPickedOverTheUniversalOne() {
        val names = listOf(
            "mikan-alpha-armeabi-v7a.apk",
            "mikan-alpha-arm64-v8a.apk",
            "mikan-alpha-universal.apk",
            "mikan-alpha-x86.apk",
            "mikan-alpha-x86_64.apk",
        )
        assertEquals("mikan-alpha-arm64-v8a.apk", GitHubReleaseUpdate.pickApk(names, "arm64-v8a"))
        assertEquals("mikan-alpha-x86.apk", GitHubReleaseUpdate.pickApk(names, "x86"))
        assertEquals("mikan-alpha-universal.apk", GitHubReleaseUpdate.pickApk(names, "mips"))
        assertEquals("mikan-alpha-universal.apk", GitHubReleaseUpdate.pickApk(names, null))
        assertEquals("mikan-meta-universal.apk", GitHubReleaseUpdate.pickApk(listOf("mikan-meta-universal.apk"), "arm64-v8a"))
        assertEquals(null, GitHubReleaseUpdate.pickApk(emptyList(), "arm64-v8a"))
    }
}
