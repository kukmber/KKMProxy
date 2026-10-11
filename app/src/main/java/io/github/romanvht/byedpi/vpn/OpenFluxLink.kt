package io.github.romanvht.byedpi.vpn

import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Ссылка на узел OpenFlux: openflux://v1/<base64url(DEFLATE(JSON))>
 * (формат из docs/links.md и пакета share оригинала).
 *
 * Ядро OpenFlux работает отдельным процессом (libopenflux.so) как SOCKS5-клиент,
 * а mihomo отправляет в него трафик как в обычный socks5-узел. Транспорты:
 * direct — прямое TCP-соединение с узлом, mailru — через документ Mail.ru
 * (работает и при белых списках). Остальные из ссылки пропускаются.
 */
data class OpenFluxLink(
    val name: String,
    val secret: String,
    val context: String,
    val negotiate: Boolean,
    val codec: String,
    val transports: List<Transport>,
) {
    data class Transport(val type: String, val url: String, val dial: String, val priority: Int)

    /** Транспорты, которые умеет запускать приложение */
    val supported: List<Transport> get() = transports.filter { it.type in SUPPORTED }

    /** Адрес узла для direct (host:port), null — в ссылке нет direct */
    val directDial: String? get() = transports.firstOrNull { it.type == DIRECT }?.dial?.ifBlank { null }

    val host: String get() = directDial?.substringBeforeLast(':').orEmpty()
    val port: Int get() = directDial?.substringAfterLast(':')?.toIntOrNull() ?: 0

    /** Как транспорты показываются в списке узлов: "mailru+direct" */
    val label: String get() = supported.sortedByDescending { it.priority }.joinToString("+") { it.type }

    /** Аргументы клиента; ключ передаётся файлом, как требует ядро */
    fun clientArgs(socksPort: Int, keyFile: String): List<String> = buildList {
        add("--role=client")
        add("--inbound=socks5")
        add("--socks5=127.0.0.1:$socksPort")
        val used = supported
        if (used.size == 1) {
            add("--transport=${used[0].type}")
        } else {
            // Сессия с несколькими транспортами: трафик идёт по самому приоритетному из рабочих
            add("--transports=" + used.joinToString(",") { "${it.type}:${it.priority}" })
        }
        used.forEach { transport ->
            when (transport.type) {
                DIRECT -> add("--direct-dial=${transport.dial}")
                MAILRU -> add("--mailru-url=${transport.url}")
            }
        }
        add("--encryption-key-file=$keyFile")
        if (context.isNotBlank()) add("--url=$context")
        if (negotiate) add("--negotiate")
        if (codec.isNotBlank()) add("--codec=$codec")
    }

    companion object {
        const val SCHEME = "openflux://"
        private const val PREFIX = "openflux://v1/"
        private const val DIRECT = "direct"
        private const val MAILRU = "mailru"
        private val SUPPORTED = setOf(DIRECT, MAILRU)

        // Приоритеты по умолчанию — как у мастера узлов оригинала (provision/transports.go)
        private val DEFAULT_PRIORITY = mapOf(MAILRU to 90, DIRECT to 50)

        fun parse(link: String): OpenFluxLink? {
            if (!link.startsWith(PREFIX, ignoreCase = true)) return null
            val packed = link.substring(PREFIX.length).substringBefore('#').trim()
            val bytes = packed.decodeBase64()?.toByteArray() ?: return null
            val json = JSONObject(String(inflate(bytes), Charsets.UTF_8))

            val array = json.optJSONArray("transports")
            val transports = mutableListOf<Transport>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val type = entry.optString("type")
                    // Каждый тип — один раз, как в мастере оригинала
                    if (type.isBlank() || transports.any { it.type == type }) continue
                    val transport = Transport(
                        type = type,
                        url = entry.optString("url"),
                        dial = entry.optString("dial"),
                        priority = entry.optInt("priority").takeIf { it > 0 } ?: DEFAULT_PRIORITY[type] ?: 10,
                    )
                    val usable = when (type) {
                        DIRECT -> transport.dial.isNotBlank()
                        MAILRU -> transport.url.isNotBlank()
                        else -> true
                    }
                    if (usable) transports += transport
                }
            }
            val secret = json.optString("secret")
            if (secret.isBlank()) return null
            return OpenFluxLink(
                name = json.optString("name"),
                secret = secret,
                context = json.optString("context"),
                negotiate = json.optBoolean("negotiate"),
                codec = json.optString("codec"),
                transports = transports,
            )
        }

        private fun inflate(data: ByteArray): ByteArray {
            val inflater = Inflater(true)
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }
}
