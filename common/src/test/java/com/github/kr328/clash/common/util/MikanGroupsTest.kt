package com.github.kr328.clash.common.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class MikanGroupsTest {
    private fun header(json: String) = "base64:" + Base64.Default.encode(json.encodeToByteArray())

    private val gib = 1L shl 30

    @Test
    fun readsWhatThePanelSends() {
        // Byte for byte what mikan's TestAppGroupsHeader pins on the panel's side.
        val raw = """{"v":1,"resets_at":1793491200,"groups":[{"id":3,"name":"Премиум +","mode":"own","used":3221225472,"limit":16106127360,"left":12884901888,"state":"active","proxies":["🇺🇸 VLESS Reality"]}]}"""
        val g = MikanGroups.parse(header(raw))!!
        assertEquals(1793491200L, g.resetsAt)
        val premium = g.groups.single()
        assertEquals(MikanGroups.Group(3, "Премиум +", MikanGroups.Mode.Own, 3 * gib, 15 * gib, 0, 12 * gib, false, listOf("🇺🇸 VLESS Reality")), premium)
        assertEquals(15 * gib, premium.total)
        assertEquals(0.2f, premium.usedFraction!!, 0.0001f)
        assertEquals(premium, g.groupOf("🇺🇸 VLESS Reality"))
        assertNull(g.groupOf("🇳🇱 VLESS"))
    }

    @Test
    fun everyModeAndAnExhaustedGroup() {
        val g = MikanGroups.parse(
            header(
                """{"v":1,"groups":[
                {"id":1,"name":"Белые списки","mode":"unlimited","used":1073741824,"state":"active","proxies":["🇷🇺 VLESS"]},
                {"id":2,"name":"Игры","mode":"own","used":5368709120,"limit":5368709120,"left":0,"state":"exhausted","proxies":[]},
                {"id":3,"name":"Премиум +","mode":"own","used":3221225472,"limit":16106127360,"extra":2147483648,"left":15032385536,"state":"active","proxies":null},
                {"id":5,"name":"Европа","mode":"main","used":77,"limit":5,"state":"active","proxies":["🇩🇪 Trojan"]}]}""",
            ),
        )!!
        assertNull(g.resetsAt)
        val (wl, games, premium, europe) = g.groups
        assertEquals(MikanGroups.Mode.Unlimited, wl.mode)
        assertNull(wl.limit)
        assertNull(wl.usedFraction)
        assertTrue(games.exhausted)
        assertEquals(1f, games.usedFraction)
        assertEquals(emptyList<String>(), games.proxies)
        assertEquals(2 * gib, premium.extra)
        assertEquals(17 * gib, premium.total)
        assertNull(premium.proxies, "null: the names did not fit in the header")
        // A group on the main traffic is a label: nothing of the traffic is its own.
        assertEquals(MikanGroups.Mode.Main, europe.mode)
        assertEquals(0L, europe.used)
        assertNull(europe.limit)
        assertEquals(listOf(wl, games, premium), g.withTraffic)
        assertEquals(europe, g.groupOf("🇩🇪 Trojan"))
    }

    @Test
    fun whatItCannotReadItDoesNotShow() {
        assertNull(MikanGroups.parse(null))
        assertNull(MikanGroups.parse(""))
        assertNull(MikanGroups.parse("base64:%%%"))
        assertNull(MikanGroups.parse("base64:" + Base64.Default.encode("not json".encodeToByteArray())))
        assertNull(MikanGroups.parse(header("""{"v":2,"groups":[]}""")), "another major version")
        assertNull(MikanGroups.parse(header("""{"groups":[]}""")), "no version")
        assertNull(MikanGroups.parse("plain text"))
        // A mode added later leaves that group out, not the others.
        val g = MikanGroups.parse(header("""{"v":1,"groups":[{"id":1,"name":"A","mode":"later"},{"id":2,"name":"B","mode":"unlimited","new_field":true}]}"""))!!
        assertEquals(listOf("B"), g.groups.map { it.name })
    }

    @Test
    fun namesAreCleanedAndCut() {
        val long = "Группа".repeat(20)
        val g = MikanGroups.parse(header("""{"v":1,"groups":[{"id":1,"name":"A\u0007B","mode":"main","proxies":["x"]},{"id":2,"name":"$long","mode":"main","proxies":["y"]},{"id":3,"name":"  ","mode":"main"}]}"""))!!
        assertEquals("AB", g.groups[0].name)
        assertEquals(64, g.groups[1].name.length)
        assertEquals(2, g.groups.size)
    }

    @Test
    fun plainJsonIsReadToo() {
        assertEquals("A", MikanGroups.parse("""{"v":1,"groups":[{"id":1,"name":"A","mode":"unlimited"}]}""")!!.groups.single().name)
    }
}
