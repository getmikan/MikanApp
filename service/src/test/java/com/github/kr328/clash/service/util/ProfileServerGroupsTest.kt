package com.github.kr328.clash.service.util

import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
class ProfileServerGroupsTest {
    private fun groups(json: String) = "base64:" + Base64.Default.encode(json.encodeToByteArray())

    private fun dirWith(headers: Map<String, String>?): File {
        val dir = Files.createTempDirectory("psg").toFile()
        if (headers != null) {
            val body = headers.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
            File(dir, FetchHeadersFile.FILE_NAME).writeText(body)
        }
        return dir
    }

    private val premium = """{"v":1,"groups":[{"id":3,"name":"Premium","mode":"own","used":1,"limit":10,"left":9,"state":"active","proxies":["a"]}]}"""

    @Test
    fun groupsAndThePageComeFromTheLastDownload() {
        val read = ProfileServerGroups.read(
            dirWith(mapOf("x-mikan-groups" to groups(premium), "profile-web-page-url" to "https://panel.example/sub/token")),
        )!!
        assertEquals("Premium", read.groups.groupOf("a")?.name)
        assertEquals("https://panel.example/sub/token", read.pageUrl)
    }

    @Test
    fun noGroupsNoSnapshotOrAnotherPanel() {
        assertNull(ProfileServerGroups.read(dirWith(null)))
        assertNull(ProfileServerGroups.read(dirWith(mapOf("subscription-userinfo" to "upload=1"))))
        assertNull(ProfileServerGroups.read(dirWith(mapOf("x-mikan-groups" to groups("""{"v":1,"groups":[]}""")))))
    }

    @Test
    fun aPageThatIsNotHttpsIsNoPlaceToBuy() {
        val read = ProfileServerGroups.read(
            dirWith(mapOf("x-mikan-groups" to groups(premium), "profile-web-page-url" to "http://panel.example/sub/token")),
        )!!
        assertNull(read.pageUrl)
    }

    @Test
    fun aNewDownloadIsReadAgain() {
        val dir = dirWith(mapOf("x-mikan-groups" to groups(premium)))
        val file = File(dir, FetchHeadersFile.FILE_NAME)
        file.setLastModified(1_000_000L)
        assertEquals("Premium", ProfileServerGroups.read(dir)?.groups?.groups?.single()?.name)
        file.writeText("""{"x-mikan-groups":"${groups(premium.replace("Premium", "Games"))}"}""")
        file.setLastModified(2_000_000L)
        assertEquals("Games", ProfileServerGroups.read(dir)?.groups?.groups?.single()?.name)
    }
}
