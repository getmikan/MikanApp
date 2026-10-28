package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.ProxyHardeningMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppRoutingTest {
    private val geo = GeoDataUrls(geoIp = "", geoSite = "", mmdb = "", asn = "")

    private val fetched = """
        mixed-port: 7890
        mode: rule
        proxies:
          - {name: NL, type: ss, server: 1.1.1.1, port: 443, cipher: aes-128-gcm, password: a}
        proxy-groups:
          - {name: "🍊 Mikan", type: select, proxies: [NL]}
        rules:
          - DOMAIN-SUFFIX,example.ru,DIRECT
          - MATCH,🍊 Mikan
    """.trimIndent()

    private val work = CustomProxy(id = "p1", name = "Work", type = "socks5", server = "10.0.0.2", port = 1080, username = "me")
    private val plain = CustomProxy(id = "p2", name = "Plain", type = "http", server = "proxy.example", port = 8080, viaVpn = false)
    private val settings = AppRoutingSettings(
        proxies = listOf(work, plain),
        routes = listOf(
            AppRoute("r1", "org.telegram.messenger", "proxy:p1"),
            AppRoute("r2", "com.android.chrome", "server:NL"),
            AppRoute("r3", "com.valvesoftware.android.steam.community", "direct"),
            AppRoute("r4", "com.game.shooter", "reject"),
            AppRoute("r5", "com.old.app", "server:Gone"),
        ),
    )

    private fun root(yaml: String) = requireNotNull(YamlFormatting.parseRootMap(yaml))

    private fun proxy(yaml: String, name: String): Map<*, *>? =
        (root(yaml)["proxies"] as List<*>).map { it as Map<*, *> }.firstOrNull { it["name"] == name }

    @Test fun routes_come_first_and_own_proxies_join_the_config() {
        val result = AppRoutingComposer.compose(fetched, AppRoutingConfig(settings, mapOf("p1" to "secret")))

        assertEquals(
            listOf(
                "PROCESS-NAME,org.telegram.messenger,👤 Work",
                "PROCESS-NAME,com.android.chrome,NL",
                "PROCESS-NAME,com.valvesoftware.android.steam.community,DIRECT",
                "PROCESS-NAME,com.game.shooter,REJECT",
                "PROCESS-NAME,com.old.app,REJECT",
                "DOMAIN-SUFFIX,example.ru,DIRECT",
                "MATCH,🍊 Mikan",
            ),
            root(result.yaml)["rules"],
        )
        assertEquals(listOf(AppRoutingIssue(kind = "serverMissing", packageName = "com.old.app", server = "Gone")), result.issues)
        val workEntry = proxy(result.yaml, "👤 Work")!!
        assertEquals("🍊 Mikan", workEntry["dialer-proxy"])
        assertEquals("secret", workEntry["password"])
        assertEquals(true, workEntry["udp"])
        val plainEntry = proxy(result.yaml, "👤 Plain")!!
        assertTrue(plainEntry["dialer-proxy"] == null && plainEntry["username"] == null && plainEntry["udp"] == null)
    }

    @Test fun unusable_proxies_block_their_apps() {
        val noPassword = AppRoutingComposer.compose(fetched, AppRoutingConfig(settings, emptyMap()))
        assertEquals(null, proxy(noPassword.yaml, "👤 Work"))
        assertEquals("PROCESS-NAME,org.telegram.messenger,REJECT", (root(noPassword.yaml)["rules"] as List<*>).first())
        assertTrue(AppRoutingIssue(kind = "keychain", proxy = "Work") in noPassword.issues)
        assertTrue(AppRoutingIssue(kind = "proxyBlocked", packageName = "org.telegram.messenger", proxy = "Work") in noPassword.issues)

        // "Via VPN" with nowhere to go through: left out, never dialled straight.
        val direct = fetched.replace("MATCH,🍊 Mikan", "MATCH,DIRECT")
        val noVpn = AppRoutingComposer.compose(direct, AppRoutingConfig(settings, mapOf("p1" to "secret")))
        assertEquals(null, proxy(noVpn.yaml, "👤 Work"))
        assertTrue(AppRoutingIssue(kind = "noVpnGroup", proxy = "Work") in noVpn.issues)
        assertTrue(proxy(noVpn.yaml, "👤 Plain") != null)
    }

    @Test fun process_lookup_off_is_turned_on_and_global_mode_is_reported() {
        val yaml = fetched.replace("mode: rule", "mode: global\nfind-process-mode: off")
        val result = AppRoutingComposer.compose(yaml, AppRoutingConfig(settings, mapOf("p1" to "secret")))
        assertEquals("strict", root(result.yaml)["find-process-mode"])
        assertTrue(AppRoutingIssue(kind = "processLookupOn") in result.issues)
        assertTrue(AppRoutingIssue(kind = "globalMode") in result.issues)
    }

    @Test fun nothing_configured_leaves_the_config_alone() {
        assertEquals(fetched, AppRoutingComposer.compose(fetched, AppRoutingConfig.Empty).yaml)
    }

    @Test fun routes_win_over_the_user_script_in_full_composition() {
        val script = ConfigScriptRunner { yaml, _ -> yaml.replace("- DOMAIN-SUFFIX,example.ru,DIRECT", "- PROCESS-NAME,org.telegram.messenger,DIRECT\n  - DOMAIN-SUFFIX,example.ru,DIRECT") }
        val config = AppRoutingConfig(settings, mapOf("p1" to "secret"))
        val composed = ConfigComposer.compose(
            fetched, UserLayer(script = UserScript(source = "x")), geo, ProxyHardeningMode.Off,
            scriptRunner = script,
            appRouting = config,
        )
        assertEquals("PROCESS-NAME,org.telegram.messenger,👤 Work", (root(composed)["rules"] as List<*>).first())
        // The screen reads the issues off the composed config: the same answer as composition gave.
        assertEquals(
            listOf(AppRoutingIssue(kind = "serverMissing", packageName = "com.old.app", server = "Gone")),
            AppRoutingComposer.issues(composed, config),
        )
        assertEquals(AppRoutingComposer.compose(fetched, config).issues, AppRoutingComposer.issues(composed, config))
    }

    @Test fun rules_validate_and_keep_one_route_per_app() {
        val p = AppRoutingRules.normalizeProxy(work.copy(id = "", name = " Work, SOCKS ", type = "SOCKS5", server = "[2001:db8::1]"), emptyList())
        assertEquals("Work  SOCKS", p.name)
        assertEquals("socks5", p.type)
        assertEquals("2001:db8::1", p.server)
        assertTrue(p.id.isNotEmpty())
        for (bad in listOf(work.copy(name = ""), work.copy(type = "vless"), work.copy(server = "host:8080"), work.copy(port = 0))) {
            assertFailsWith<AppRoutingException> { AppRoutingRules.normalizeProxy(bad, emptyList()) }
        }
        val duplicate = assertFailsWith<AppRoutingException> { AppRoutingRules.normalizeProxy(work.copy(id = "p9"), listOf(work)) }
        assertEquals(AppRoutingException.Reason.DuplicateName, duplicate.reason)

        val noApp = assertFailsWith<AppRoutingException> { AppRoutingRules.normalizeRoute(AppRoute("", "not a package", "direct"), emptyList()) }
        assertEquals(AppRoutingException.Reason.NoApp, noApp.reason)
        val gone = assertFailsWith<AppRoutingException> { AppRoutingRules.normalizeRoute(AppRoute("", "org.telegram.messenger", "proxy:gone"), emptyList()) }
        assertEquals(AppRoutingException.Reason.ProxyGone, gone.reason)

        val routes = AppRoutingRules.upsertRoute(settings.routes, AppRoute("new", "com.android.chrome", "reject"))
        assertEquals(settings.routes.size, routes.size)
        assertEquals("reject", routes[1].target)

        val withoutWork = AppRoutingRules.removeProxy(settings, "p1")
        assertTrue(withoutWork.routes.none { it.target == "proxy:p1" } && withoutWork.proxies == listOf(plain))
    }

    @Test fun proxy_links_fill_the_form() {
        assertEquals(ProxyLink("socks5", "1.2.3.4", 1080, "me", "p@ss:w"), AppRoutingRules.parseProxyLink("socks5h://me:p%40ss%3Aw@1.2.3.4:1080/"))
        assertEquals(ProxyLink("http", "proxy.example", 80), AppRoutingRules.parseProxyLink(" http://proxy.example:80 "))
        assertEquals(ProxyLink(null, "1.2.3.4", 8000, "user", "pa:ss"), AppRoutingRules.parseProxyLink("1.2.3.4:8000:user:pa:ss"))
        assertEquals(ProxyLink(null, "2001:db8::1", 1080), AppRoutingRules.parseProxyLink("[2001:db8::1]:1080"))
        assertEquals(ProxyLink(null, "host.example", 3128, "u", "p"), AppRoutingRules.parseProxyLink("u:p@host.example:3128"))
        for (bad in listOf("", "vless://x@h:1", "host", "host:0", "host:99999", "a b:1", "u:p@host:1:x:y", "[::1]1080", "u:%zz@h:1")) {
            assertEquals(null, AppRoutingRules.parseProxyLink(bad), bad)
        }
    }

    @Test fun route_targets_and_the_check_block_leave_the_dialer_out() {
        val composed = AppRoutingComposer.compose(fetched, AppRoutingConfig(settings, mapOf("p1" to "secret"))).yaml
        assertEquals(RoutableTargets(listOf("🍊 Mikan"), listOf("NL")), AppRoutingRules.routableTargets(composed))
        assertEquals<Map<String, Any?>?>(
            mapOf("name" to "👤 Work", "type" to "socks5", "server" to "10.0.0.2", "port" to 1080, "udp" to true, "username" to "me", "password" to "secret"),
            YamlFormatting.parseRootMap(AppRoutingRules.proxyYaml(work, "secret")),
        )
    }

    @Test fun recovery_keeps_the_dialer_of_own_proxies() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        java.io.File(dir, "config.yaml").writeText(
            """
            proxies:
              - {name: "👤 Work", type: socks5, server: 10.0.0.2, port: 1080, dialer-proxy: "🍊 Mikan"}
              - {name: Chained, type: ss, server: 2.2.2.2, port: 1, cipher: aes-128-gcm, password: b, dialer-proxy: Missing}
            proxy-groups:
              - {name: "🍊 Mikan", type: select, proxies: [Chained]}
            rules:
              - MATCH,🍊 Mikan
            """.trimIndent(),
        )
        ProxyDialerYamlEdit.clearAllDialerProxies(dir)
        val after = java.io.File(dir, "config.yaml").readText()
        assertEquals("🍊 Mikan", proxy(after, "👤 Work")?.get("dialer-proxy"))
        assertEquals(null, proxy(after, "Chained")?.get("dialer-proxy"))
        dir.deleteRecursively()
    }
}
