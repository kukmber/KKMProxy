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
