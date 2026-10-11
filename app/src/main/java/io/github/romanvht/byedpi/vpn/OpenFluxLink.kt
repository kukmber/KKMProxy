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
 * а mihomo отправляет в него трафик как в обычный socks5-узел.
 * Поддерживается только транспорт direct — прямое TCP-соединение с узлом.
 */
data class OpenFluxLink(
    val name: String,
    val secret: String,
    val context: String,
    val negotiate: Boolean,
    val codec: String,
    /** Адрес узла для direct (host:port), null — в ссылке нет direct */
    val directDial: String?,
    val transports: List<String>,
) {
    val host: String get() = directDial?.substringBeforeLast(':').orEmpty()
    val port: Int get() = directDial?.substringAfterLast(':')?.toIntOrNull() ?: 0

    /** Аргументы клиента; ключ передаётся файлом, как требует ядро */
    fun clientArgs(socksPort: Int, keyFile: String): List<String> = buildList {
        add("--role=client")
        add("--inbound=socks5")
        add("--socks5=127.0.0.1:$socksPort")
        add("--transport=direct")
        add("--direct-dial=$directDial")
        add("--encryption-key-file=$keyFile")
        if (context.isNotBlank()) add("--url=$context")
        if (negotiate) add("--negotiate")
        if (codec.isNotBlank()) add("--codec=$codec")
    }

    companion object {
        const val SCHEME = "openflux://"
        private const val PREFIX = "openflux://v1/"

        fun parse(link: String): OpenFluxLink? {
            if (!link.startsWith(PREFIX, ignoreCase = true)) return null
            val packed = link.substring(PREFIX.length).substringBefore('#').trim()
            val bytes = packed.decodeBase64()?.toByteArray() ?: return null
            val json = JSONObject(String(inflate(bytes), Charsets.UTF_8))

            val transports = json.optJSONArray("transports")
            val types = mutableListOf<String>()
            var dial: String? = null
            if (transports != null) {
                for (i in 0 until transports.length()) {
                    val entry = transports.optJSONObject(i) ?: continue
                    val type = entry.optString("type")
                    types += type
                    if (type == "direct" && dial == null) dial = entry.optString("dial").ifBlank { null }
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
                directDial = dial,
                transports = types,
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
