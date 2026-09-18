package io.github.romanvht.byedpi.vpn

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** REST API контроллера работающего ядра mihomo. */
object MihomoApi {
    private val TEST_URLS = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Перечитать конфиг с диска без перезапуска ядра. */
    fun reload(configPath: String): Boolean {
        val ports = KkmVpnService.corePorts ?: return false
        val request = Request.Builder()
            .url("http://127.0.0.1:${ports.controller}/configs?force=true")
            .header("Authorization", "Bearer ${ports.secret}")
            .put(
                JSONObject().put("path", configPath).toString()
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()
        return runCatching {
            reloadClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private val reloadClient by lazy {
        client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Задержка узла в мс, 0 — узел не ответил, null — ядро не запущено. Не в главном потоке. */
    fun delay(nodeName: String, timeoutMs: Int = 5_000): Int? {
        for (testUrl in TEST_URLS) {
            val result = delay(nodeName, timeoutMs, testUrl) ?: return null
            if (result > 0) return result
        }
        return 0
    }

    private fun delay(nodeName: String, timeoutMs: Int, testUrl: String): Int? {
        val ports = KkmVpnService.corePorts ?: return null
        val url = HttpUrl.Builder()
            .scheme("http")
            .host("127.0.0.1")
            .port(ports.controller)
            .addPathSegment("proxies")
            .addPathSegment(nodeName)
            .addPathSegment("delay")
            .addQueryParameter("timeout", timeoutMs.toString())
            .addQueryParameter("url", testUrl)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${ports.secret}")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use 0
                JSONObject(response.body?.string().orEmpty()).optInt("delay", 0)
            }
        }.getOrDefault(0)
    }
}
