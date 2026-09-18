package io.github.romanvht.byedpi.vpn

import android.util.Base64
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Разбор подписок (Clash/Mihomo YAML, base64 или список ссылок vless:// / hysteria2://)
 * и сборка конфига для ядра mihomo.
 */
object VpnConfigParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT = "clash.meta/1.19.31 KKMProxy"
    private const val REGIONAL_INDICATOR_A = 0x1F1E6
    private val FLAG_REGEX = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")

    const val TYPE_HYSTERIA2 = "hysteria2"
    const val TYPE_VLESS = "vless"

    private val SUPPORTED_TYPES = setOf(TYPE_HYSTERIA2, TYPE_VLESS)
    private val SUPPORTED_VLESS_NETWORKS = setOf("tcp", "ws", "grpc", "h2", "http", "xhttp")

    /** Один узел подписки в Clash-формате (ключи как в YAML mihomo). */
    data class VpnNode(
        val name: String,
        val type: String,
        val server: String,
        val port: Int,
        val rawYaml: Map<String, Any?>,
    ) {
        /** Причина, по которой узел нельзя запустить, или null. */
        val unsupportedReason: String?
            get() = when {
                type !in SUPPORTED_TYPES -> "тип $type"
                type == TYPE_VLESS && network !in SUPPORTED_VLESS_NETWORKS -> "транспорт $network"
                else -> null
            }

        val isSupported: Boolean get() = unsupportedReason == null

        val network: String
            get() = (rawYaml["network"] as? String)?.lowercase()?.ifBlank { null } ?: "tcp"

        /** Код страны из имени узла ("NL"), если он там есть */
        val countryCode: String?
            get() {
                FLAG_REGEX.find(name)?.value?.let { return flagToCode(it) }
                return name.split(' ', '-', '_', '|', '[', ']', '(', ')', ',')
                    .firstOrNull { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            }
    }

    fun flagToCode(flag: String): String =
        "${'A' + (flag.codePointAt(0) - REGIONAL_INDICATOR_A)}${'A' + (flag.codePointAt(2) - REGIONAL_INDICATOR_A)}"

    fun codeToFlag(code: String): String? {
        if (code.length != 2 || !code.all { it.uppercaseChar() in 'A'..'Z' }) return null
        return code.uppercase().map { String(Character.toChars(REGIONAL_INDICATOR_A + (it - 'A'))) }
            .joinToString("")
    }

    fun resolveServer(host: String): String? =
        runCatching { InetAddress.getAllByName(host).firstOrNull { it.address.size == 4 }?.hostAddress }
            .getOrNull()

    // ------------------------------------------------------------------ загрузка

    data class Subscription(
        val content: String,
        val title: String?,
        val userInfo: String?,
    )

    /**
     * Скачивает подписку по одной ссылке. Логин/пароль, если нужны, берутся из
     * самой ссылки (https://user:pass@host/...), как в FlClash.
     */
    fun download(url: String): Subscription {
        val httpUrl = url.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Некорректная ссылка")
        val builder = Request.Builder()
            .url(httpUrl.newBuilder().username("").password("").build())
            .header("User-Agent", USER_AGENT)
        if (httpUrl.username.isNotEmpty()) {
            builder.header("Authorization", Credentials.basic(httpUrl.username, httpUrl.password))
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Сервер подписки ответил ${response.code}")
            }
            val body = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Пустой ответ от сервера подписки")
            return Subscription(
                content = body,
                title = decodeTitle(response.header("profile-title")),
                userInfo = response.header("subscription-userinfo"),
            )
        }
    }

    private fun decodeTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (!raw.startsWith("base64:")) return raw.trim()
        return runCatching {
            String(Base64.decode(raw.removePrefix("base64:"), Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()
    }

    // ------------------------------------------------------------------ разбор

    /** Разбирает содержимое подписки любого поддерживаемого формата. */
    fun parse(content: String): List<VpnNode> {
        val text = normalize(content)
        if (text.isEmpty()) return emptyList()

        if (!looksLikeLinks(text)) {
            runCatching { parseYaml(text) }.getOrNull()?.let { if (it.isNotEmpty()) return it }
            decodeBase64(text)?.let { decoded ->
                if (looksLikeLinks(decoded)) return parseLinks(decoded)
            }
        }
        return parseLinks(text)
    }

    private fun normalize(content: String) = content.trim().removePrefix("﻿")

    private fun looksLikeLinks(text: String) =
        text.lineSequence().any { line -> URI_SCHEMES.any { line.trim().startsWith(it, ignoreCase = true) } }

    private val URI_SCHEMES = listOf("vless://", "hysteria2://", "hy2://")

    private fun decodeBase64(text: String): String? {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty() || !compact.all { it.isLetterOrDigit() || it in "+/=-_" }) return null
        return runCatching {
            String(Base64.decode(compact, Base64.DEFAULT or Base64.URL_SAFE), Charsets.UTF_8)
        }.recoverCatching {
            String(Base64.decode(compact, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadYamlMap(text: String): MutableMap<String, Any?>? =
        runCatching { Yaml().load<Any?>(text) as? MutableMap<String, Any?> }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    fun parseYaml(yamlText: String): List<VpnNode> {
        val proxies = loadYamlMap(yamlText)?.get("proxies") as? List<Map<String, Any?>> ?: return emptyList()
        return proxies.mapNotNull { entry ->
            val name = entry["name"]?.toString() ?: return@mapNotNull null
            val type = entry["type"]?.toString()?.lowercase() ?: return@mapNotNull null
            val server = entry["server"]?.toString() ?: return@mapNotNull null
            val port = entry["port"]?.toString()?.toIntOrNull() ?: return@mapNotNull null
            VpnNode(name, type, server, port, entry)
        }
    }

    fun parseLinks(text: String): List<VpnNode> =
        text.lineSequence().mapNotNull { runCatching { parseLink(it.trim()) }.getOrNull() }.toList()

    /** vless://uuid@host:port?params#name, hysteria2://password@host:port?params#name */
    fun parseLink(link: String): VpnNode? {
        val schemeEnd = link.indexOf("://")
        if (schemeEnd <= 0) return null
        val type = when (link.substring(0, schemeEnd).lowercase()) {
            "vless" -> TYPE_VLESS
            "hysteria2", "hy2" -> TYPE_HYSTERIA2
            else -> return null
        }

        var rest = link.substring(schemeEnd + 3)
        val name = rest.substringAfter('#', "").let(::decode)
        rest = rest.substringBefore('#')
        val params = rest.substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() }
            .associate { decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', "")) }
        rest = rest.substringBefore('?').trimEnd('/')

        val userInfo = decode(rest.substringBeforeLast('@', ""))
        val hostPort = rest.substringAfterLast('@')
        val (host, port) = if (hostPort.startsWith("[")) {
            hostPort.substringAfter('[').substringBefore(']') to hostPort.substringAfterLast("]:", "")
        } else {
            hostPort.substringBeforeLast(':') to hostPort.substringAfterLast(':', "")
        }
        val portNumber = port.toIntOrNull() ?: 443
        if (host.isBlank()) return null

        val nodeName = name.ifBlank { "$host:$portNumber" }
        val raw = linkedMapOf<String, Any?>(
            "name" to nodeName,
            "type" to type,
            "server" to host,
            "port" to portNumber,
            "udp" to true,
        )
        val sni = params["sni"] ?: params["peer"]
        val insecure = params["insecure"] == "1" || params["allowinsecure"] == "1" ||
            params["allowinsecure"] == "true"
        if (insecure) raw["skip-cert-verify"] = true
        params["alpn"]?.takeIf { it.isNotBlank() }?.let { raw["alpn"] = it.split(',') }

        when (type) {
            TYPE_HYSTERIA2 -> {
                raw["password"] = userInfo
                sni?.let { raw["sni"] = it }
                params["obfs"]?.takeIf { it.isNotBlank() && it != "none" }?.let { raw["obfs"] = it }
                params["obfs-password"]?.let { raw["obfs-password"] = it }
                params["mport"]?.let { raw["ports"] = it }
            }

            TYPE_VLESS -> {
                raw["uuid"] = userInfo
                val security = params["security"]?.lowercase()
                raw["tls"] = security == "tls" || security == "reality"
                params["flow"]?.takeIf { it.isNotBlank() }?.let { raw["flow"] = it }
                sni?.let { raw["servername"] = it }
                params["fp"]?.takeIf { it.isNotBlank() }?.let { raw["client-fingerprint"] = it }
                if (security == "reality") {
                    raw["reality-opts"] = linkedMapOf(
                        "public-key" to (params["pbk"] ?: ""),
                        "short-id" to (params["sid"] ?: ""),
                    )
                    if (raw["client-fingerprint"] == null) raw["client-fingerprint"] = "chrome"
                }

                val path = params["path"]
                val hostHeader = params["host"]
                when (params["type"]?.lowercase() ?: "tcp") {
                    "ws" -> {
                        raw["network"] = "ws"
                        raw["ws-opts"] = wsOpts(path, hostHeader, upgrade = false)
                    }
                    "httpupgrade" -> {
                        raw["network"] = "ws"
                        raw["ws-opts"] = wsOpts(path, hostHeader, upgrade = true)
                    }
                    "grpc" -> {
                        raw["network"] = "grpc"
                        raw["grpc-opts"] = linkedMapOf("grpc-service-name" to (params["servicename"] ?: ""))
                    }
                    "h2", "http" -> {
                        raw["network"] = "h2"
                        raw["h2-opts"] = linkedMapOf<String, Any?>("path" to (path ?: "/")).apply {
                            hostHeader?.let { put("host", it.split(',')) }
                        }
                    }
                    "xhttp", "splithttp" -> {
                        raw["network"] = "xhttp"
                        raw["xhttp-opts"] = linkedMapOf<String, Any?>("path" to (path ?: "/")).apply {
                            hostHeader?.let { put("host", it) }
                            params["mode"]?.let { put("mode", it) }
                        }
                    }
                    else -> raw["network"] = "tcp"
                }
            }
        }
        return VpnNode(nodeName, type, host, portNumber, raw)
    }

    private fun wsOpts(path: String?, host: String?, upgrade: Boolean) = linkedMapOf<String, Any?>(
        "path" to (path ?: "/"),
    ).apply {
        host?.let { put("headers", linkedMapOf("Host" to it)) }
        if (upgrade) put("v2ray-http-upgrade", true)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    // ------------------------------------------------------------------ mihomo

    data class CorePorts(val mixed: Int, val controller: Int, val secret: String)

    private const val PROBE_RULE = "IP-CIDR,127.0.0.1/32,DIRECT,no-resolve"

    // Подменные адреса DNS туннеля (Private DNS пробует :853) — сразу отказ вместо таймаута
    private const val MAPDNS_RULE = "IP-CIDR,198.18.0.0/15,REJECT,no-resolve"

    /** Файл rule-provider'а, который приложение может скачать заранее. */
    data class ProviderFile(val name: String, val url: String, val path: String, val intervalSec: Long)

    data class MihomoConfig(val yaml: String, val providers: List<ProviderFile>)

    /** Ключи подписки, которые приложение задаёт само. */
    private val DROPPED_KEYS = setOf(
        "port", "socks-port", "redir-port", "tproxy-port", "mixed-port", "allow-lan", "bind-address",
        "authentication", "skip-auth-prefixes", "lan-allowed-ips", "lan-disallowed-ips",
        "external-controller", "external-controller-tls", "external-controller-unix",
        "external-controller-pipe", "external-controller-cors", "external-ui", "external-ui-name",
        "external-ui-url", "secret", "tun", "interface-name", "routing-mark", "enable-process",
        "find-process-mode", "log-level", "profile", "listeners", "tunnels", "iptables", "ebpf",
        "auto-redir",
    )

    /**
     * Конфиг mihomo. Если профиль — полноценный Clash YAML, берутся его группы,
     * правила и rule-providers (как в FlClashX), а выбранный узел ставится первым
     * во всех select-группах. Для одиночных ссылок — минимальный конфиг.
     */
    @Suppress("UNCHECKED_CAST")
    fun buildMihomoConfig(
        content: String,
        node: VpnNode,
        ports: CorePorts,
        skipProviders: Set<String> = emptySet(),
    ): MihomoConfig {
        node.unsupportedReason?.let { throw IllegalArgumentException("Узел не поддерживается: $it") }

        val subscription = if (looksLikeLinks(normalize(content))) {
            null
        } else {
            loadYamlMap(normalize(content))?.takeIf { it["proxies"] is List<*> }
        }
        val config = linkedMapOf<String, Any?>(
            "mixed-port" to ports.mixed,
            "allow-lan" to false,
            "bind-address" to "127.0.0.1",
            "external-controller" to "127.0.0.1:${ports.controller}",
            "secret" to ports.secret,
            "log-level" to "info",
            "find-process-mode" to "off",
            "ipv6" to false,
            "unified-delay" to true,
            "tcp-concurrent" to true,
            "profile" to linkedMapOf("store-selected" to false, "store-fake-ip" to false),
            "tun" to linkedMapOf("enable" to false),
        )

        val proxies: MutableList<MutableMap<String, Any?>>
        if (subscription != null) {
            subscription.forEach { (key, value) -> if (key !in DROPPED_KEYS && key !in config) config[key] = value }
            proxies = (subscription["proxies"] as List<*>).filterIsInstance<MutableMap<String, Any?>>().toMutableList()
            config["proxies"] = proxies

            val groups = (config["proxy-groups"] as? List<*>)?.filterIsInstance<MutableMap<String, Any?>>().orEmpty()
            groups.forEach { group ->
                val members = group["proxies"] as? MutableList<Any?> ?: return@forEach
                if (group["type"] == "select" && members.remove(node.name)) members.add(0, node.name)
            }
            val rules = config["rules"] as? List<*>
            if (rules.isNullOrEmpty()) {
                config["mode"] = "rule"
                config["rules"] = mutableListOf("MATCH,${node.name}")
            }
        } else {
            // Все узлы профиля (для замера пинга), трафик — через выбранный
            val others = parse(content).filter { it.isSupported && it.name != node.name }
                .distinctBy { it.name }
            proxies = (listOf(node) + others).map { LinkedHashMap(it.rawYaml) }.toMutableList()
            config["mode"] = "rule"
            config["proxies"] = proxies
            config["rules"] = mutableListOf("MATCH,${node.name}")
        }
        config["mode"] = config["mode"] ?: "rule"

        // Правила, которые не удалось скачать, временно выключаем — иначе ядро ждёт их загрузку
        if (skipProviders.isNotEmpty()) {
            (config["rule-providers"] as? MutableMap<String, Any?>)?.keys?.removeAll(skipProviders)
            config["rules"] = (config["rules"] as? List<*>).orEmpty().filterNot { rule ->
                val parts = rule.toString().split(',').map { it.trim() }
                parts.firstOrNull() == "RULE-SET" && parts.getOrNull(1) in skipProviders
            }.toMutableList()
        }
        // Проверка готовности ядра идёт на его же контроллер — всегда напрямую
        config["rules"] = (listOf(PROBE_RULE, MAPDNS_RULE) + (config["rules"] as? List<*>).orEmpty()).toMutableList()

        // UDP (QUIC, игры, звонки) через туннель
        proxies.forEach { proxy ->
            if (proxy["name"] == node.name && proxy["udp"] == null) proxy["udp"] = true
        }

        config["dns"] = buildDns(config["dns"] as? MutableMap<String, Any?>)
        val providers = prepareRuleProviders(config["rule-providers"] as? Map<String, Any?>)

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
            indent = 2
            width = 4096
            splitLines = false
        }
        return MihomoConfig(Yaml(options).dump(config), providers)
    }

    /**
     * Проставляет явные пути http-провайдерам и возвращает список для предзагрузки:
     * пока mihomo качает правила, он не пропускает трафик, а через VPN-узел
     * загрузка с GitHub бывает очень медленной.
     */
    @Suppress("UNCHECKED_CAST")
    private fun prepareRuleProviders(providers: Map<String, Any?>?): List<ProviderFile> {
        providers ?: return emptyList()
        return providers.mapNotNull { (name, value) ->
            val provider = value as? MutableMap<String, Any?> ?: return@mapNotNull null
            if (provider["type"] != "http") return@mapNotNull null
            val url = provider["url"]?.toString() ?: return@mapNotNull null
            val path = provider["path"]?.toString()?.takeIf { it.isNotBlank() && !it.contains("..") }
                ?: run {
                    val ext = when (provider["format"]?.toString()) {
                        "mrs" -> "mrs"
                        "text" -> "list"
                        else -> "yaml"
                    }
                    "./rule-sets/${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.$ext"
                }.also { provider["path"] = it }
            val interval = provider["interval"]?.toString()?.toLongOrNull() ?: 86_400L
            ProviderFile(name, url, path, interval)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDns(source: MutableMap<String, Any?>?): MutableMap<String, Any?> {
        val dns: MutableMap<String, Any?> = source ?: linkedMapOf<String, Any?>(
            "default-nameserver" to mutableListOf("77.88.8.8", "1.1.1.1"),
            "nameserver" to mutableListOf("https://1.1.1.1/dns-query"),
        )
        dns["enable"] = true
        dns["ipv6"] = false
        dns["enhanced-mode"] = "redir-host"
        // Запросы ядра к DNS идут по правилам (т.е. через VPN) — зарубежный DoH напрямую часто недоступен
        dns["respect-rules"] = true
        dns.remove("listen")
        dns.remove("fake-ip-range")
        // Запасной резолвер для адресов самих серверов
        val proxyServerNs = (dns["proxy-server-nameserver"] as? MutableList<Any?>) ?: mutableListOf()
        if ("77.88.8.8" !in proxyServerNs) proxyServerNs.add("77.88.8.8")
        dns["proxy-server-nameserver"] = proxyServerNs
        if (dns["default-nameserver"] == null) dns["default-nameserver"] = mutableListOf("77.88.8.8", "1.1.1.1")
        return dns
    }
}
