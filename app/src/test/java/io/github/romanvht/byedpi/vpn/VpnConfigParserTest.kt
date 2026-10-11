package io.github.romanvht.byedpi.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpnConfigParserTest {

    private val sampleYaml = """
        mixed-port: 7890
        proxies:
          - name: ⚡️ Hysteria2
            type: hysteria2
            server: example.com
            port: 443
            password: secret
            sni: example.com
            skip-cert-verify: false
            up: 100
            down: "50 Mbps"
          - name: 🇳🇱 VLESS-Reality
            type: vless
            server: 1.2.3.4
            port: 443
            uuid: 11111111-2222-3333-4444-555555555555
            network: tcp
            tls: true
            flow: xtls-rprx-vision
            servername: yandex.ru
            client-fingerprint: chrome
            reality-opts:
              public-key: AAAA
              short-id: abcd
          - name: ☁️ VLESS-CDN
            type: vless
            server: cdn.example.com
            port: 443
            uuid: 11111111-2222-3333-4444-555555555555
            network: xhttp
            tls: true
          - name: Trojan
            type: trojan
            server: t.example.com
            port: 443
            password: p
        proxy-groups:
          - name: VPN
            type: select
    """.trimIndent()

    @Test
    fun parsesClashYaml() {
        val nodes = VpnConfigParser.parseYaml(sampleYaml)
        assertEquals(4, nodes.size)
        assertTrue(nodes[0].isSupported)
        assertNull(nodes[0].countryCode)
        assertEquals("NL", nodes[1].countryCode)
        assertTrue("xhttp поддерживается ядром mihomo", nodes[2].isSupported)
        assertFalse(nodes[3].isSupported)
    }

    @Test
    fun parsesLinks() {
        val vless = VpnConfigParser.parseLink(
            "vless://11111111-2222-3333-4444-555555555555@[2001:db8::1]:8443" +
                "?type=ws&security=tls&sni=a.example&path=%2Fws&host=b.example&fp=chrome#DE%20Berlin",
        )!!
        assertEquals("DE Berlin", vless.name)
        assertEquals("2001:db8::1", vless.server)
        assertEquals(8443, vless.port)
        assertEquals("ws", vless.network)
        assertEquals("DE", vless.countryCode)

        val hy2 = VpnConfigParser.parseLink("hy2://pa+ss@example.com:443/?sni=x.example&insecure=1&obfs=salamander&obfs-password=o#Test")!!
        assertEquals("hysteria2", hy2.type)
        assertEquals("pa+ss", hy2.rawYaml["password"])
        assertEquals(true, hy2.rawYaml["skip-cert-verify"])
    }

    private val ports = VpnConfigParser.CorePorts(17890, 17891, "test-secret")

    @Test
    @Suppress("UNCHECKED_CAST")
    fun buildsConfigFromSubscription() {
        val node = VpnConfigParser.parseYaml(sampleYaml)[1]
        val subscription = sampleYaml.replace(
            "    type: select",
            "    type: select\n    proxies: [\"⚡️ Hysteria2\", \"🇳🇱 VLESS-Reality\"]",
        )
        val yaml = VpnConfigParser.buildMihomoConfig(subscription, node, ports).yaml
        val config = org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(yaml)
        assertEquals(17890, config["mixed-port"])
        assertEquals(false, (config["tun"] as Map<*, *>)["enable"])
        val group = (config["proxy-groups"] as List<Map<String, Any?>>).first()
        assertEquals(node.name, (group["proxies"] as List<*>).first())
        assertEquals("MATCH,${node.name}", (config["rules"] as List<*>).last())
        assertEquals("IP-CIDR,127.0.0.1/32,DIRECT,no-resolve", (config["rules"] as List<*>).first())
    }

    // Собраны самим ядром: openflux --make-link (ключ фиктивный)
    private val openFluxDirect =
        "openflux://v1/lM5NCsIwFEXhvVynoX1p_X1bEQcxfWpB05JcQSndu2QJDs_k8C1I4WVQ0ArhkOw-cQw0KPPbHIrFbIRCfNdvd_vD8RSucbDbvw2HOCXap84e5Kxtu4EDc0hlnjIL9LyA37lyhjFbrKBhDE8oOukbabzvGy_aiYhgvay_AAAA__8"
    private val openFluxYandexOnly =
        "openflux://v1/lMzLCsIwEEbhd_nXoeP9Mq8iLmIyYlHSMDOFlNJ3F3EvuDxw-GaYJBUHY7XebHf7w_F0jreU5f5vIyANxaV9sId7NSbKvT27KZYsrdORemoIcI3F6qBu4MsMn6qA8b0QMOrrt7Bcl3cAAAD__w"
    private val openFluxKey = "0123456789abcdef".repeat(4)

    @Test
    fun parsesOpenFluxLink() {
        val link = OpenFluxLink.parse(openFluxDirect)!!
        assertEquals("test", link.name)
        assertEquals(openFluxKey, link.secret)
        assertEquals("http://#", link.context)
        assertTrue(link.negotiate)
        assertEquals("203.0.113.10:20000", link.directDial)

        val args = link.clientArgs(1080, "/k")
        assertTrue("--transport=direct" in args)
        assertTrue("--direct-dial=203.0.113.10:20000" in args)
        assertTrue("--encryption-key-file=/k" in args)
        assertTrue("--negotiate" in args)
        assertTrue("--socks5=127.0.0.1:1080" in args)

        val node = VpnConfigParser.parse(openFluxDirect).single()
        assertEquals(VpnConfigParser.TYPE_OPENFLUX, node.type)
        assertEquals("203.0.113.10", node.server)
        assertEquals(20000, node.port)
        assertTrue(node.isSupported)
    }

    // Mail.ru (приоритет 90) + direct (50), контекст — ссылка на документ
    private val openFluxMailru =
        "openflux://v1/nM5BSjQxFATgu9Q6dF66__l1shMREREPIC7SScaJpJPw-gUchr67jAvBpS6Lgq_qjOKWCIuFoVDiW5XkJMIK96iwRs9RYEFmnP7t_l9d793sQzz8NkPB1yLx44IdRdpqtfa59jAsLuWBu259zsnrm_k23B3uj0Y_vD_mp_LcRigIu7K2yrLCvpwhp_Z12qXMHQqd89_cxqlykhPsnjb1DYfE0cuPfkcKIbnLzkjTQIMx02DIjkRE2F63zwAAAP__"

    @Test
    fun openFluxMailruWithDirectBackup() {
        val mailru = "https://cloud.mail.ru/public/AbCdEfGh1/IjKlMnOp2"
        val link = OpenFluxLink.parse(openFluxMailru)!!
        assertEquals(mailru, link.context)
        assertEquals("mailru+direct", link.label)

        val args = link.clientArgs(1080, "/k")
        assertTrue("--transports=mailru:90,direct:50" in args)
        assertTrue("--mailru-url=$mailru" in args)
        assertTrue("--direct-dial=203.0.113.10:20000" in args)
        assertTrue("--url=$mailru" in args)
        assertFalse(args.any { it.startsWith("--transport=") })

        val node = VpnConfigParser.parse(openFluxMailru).single()
        assertTrue(node.isSupported)
        assertEquals("203.0.113.10", node.server)
        assertEquals("mailru+direct", node.network)
    }

    @Test
    fun openFluxNameFromFragment() {
        assertEquals("🇫🇮 OpenFlux", VpnConfigParser.parse("$openFluxDirect#🇫🇮 OpenFlux").single().name)
        assertEquals("🇫🇮 OpenFlux", VpnConfigParser.parse("$openFluxDirect#%F0%9F%87%AB%F0%9F%87%AE%20OpenFlux").single().name)
    }

    @Test
    fun openFluxWithoutDirectIsUnsupported() {
        val node = VpnConfigParser.parse(openFluxYandexOnly).single()
        assertEquals(VpnConfigParser.TYPE_OPENFLUX, node.type)
        assertFalse(node.isSupported)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun openFluxNodeBecomesLocalSocks() {
        val content = openFluxDirect + "\n" +
            "hysteria2://pw@example.com:443?sni=x#hy"
        val node = VpnConfigParser.parse(content).first { it.type == VpnConfigParser.TYPE_OPENFLUX }
        val yaml = VpnConfigParser.buildMihomoConfig(content, node, ports.copy(openflux = 17892)).yaml
        val config = org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(yaml)
        val proxies = config["proxies"] as List<Map<String, Any?>>
        val proxy = proxies.first()
        assertEquals("socks5", proxy["type"])
        assertEquals("127.0.0.1", proxy["server"])
        assertEquals(17892, proxy["port"])
        assertFalse(yaml.contains(openFluxKey))
        assertEquals(listOf("test", "hy"), proxies.map { it["name"] })
    }

    /** Пишет конфиги в MIHOMO_CHECK_DIR, чтобы проверить их бинарником mihomo. */
    @Test
    fun buildsConfigs() {
        val subscription = System.getenv("SUBSCRIPTION_FILE")?.let { File(it).readText() } ?: sampleYaml
        val links = listOf(
            "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=grpc&security=tls&serviceName=svc#grpc",
            "hysteria2://pw@example.com:443?sni=x&obfs=salamander&obfs-password=o&mport=20000-30000#hop",
            "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=xhttp&security=tls&path=%2Fx&mode=auto#xh",
        ).joinToString("\n")
        val outDir = System.getenv("MIHOMO_CHECK_DIR")?.let(::File)
        outDir?.mkdirs()

        listOf("sub" to subscription, "links" to links).forEach { (kind, content) ->
            VpnConfigParser.parseLinks(content).ifEmpty { VpnConfigParser.parseYaml(content) }
                .filter { it.isSupported }
                .forEachIndexed { index, node ->
                    var config = VpnConfigParser.buildMihomoConfig(content, node, ports)
                    // Как в приложении: провайдеры без скачанного файла пропускаются
                    System.getenv("MIHOMO_WORK_DIR")?.let { work ->
                        val missing = config.providers.filter { !File(work, it.path).exists() }.map { it.name }.toSet()
                        if (missing.isNotEmpty()) {
                            config = VpnConfigParser.buildMihomoConfig(content, node, ports, missing)
                            missing.forEach { name -> assertFalse(config.yaml.contains("RULE-SET,$name,")) }
                        }
                    }
                    assertTrue(config.yaml.contains("mixed-port: 17890"))
                    config.providers.forEach { assertTrue(config.yaml.contains(it.path)) }
                    outDir?.let {
                        File(it, "${kind}_$index.yaml").writeText(config.yaml)
                        File(it, "${kind}_$index.providers").writeText(
                            config.providers.joinToString("\n") { p -> "${p.path} ${p.url}" },
                        )
                    }
                }
        }
    }
}
